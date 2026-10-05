package org.example.springai_mcp_client_mysql.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.springai_mcp_client_mysql.model.IncidentActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tool 2: incident creation. Creates a new OPEN incident or updates an existing
 * OPEN/IN_PROGRESS one for the same error_signature (dedup via active_signature).
 */
@Component
public class IncidentTools {

    private static final Logger log = LoggerFactory.getLogger(IncidentTools.class);
    private static final Set<String> INCIDENT_SEVERITIES = Set.of("MEDIUM", "HIGH", "CRITICAL");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IncidentTools(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(name = "create_incident",
          description = "Create or update an incident ticket for a UPI error. "
                  + "Call ONLY when classification says createIncident=true "
                  + "(severity MEDIUM, HIGH or CRITICAL). "
                  + "Deduplicates by error_signature: if an OPEN/IN_PROGRESS incident exists, "
                  + "increments affected_count and updates last_seen / sample RRNs; "
                  + "otherwise inserts a new incident. Returns the action taken and INC-xxxxx number.")
    public IncidentActionResult createIncident(
            @ToolParam(description = "Short incident title, e.g. 'UPI debit failures: CBS response timeouts'") String title,
            @ToolParam(description = "Category from classification, e.g. TECHNICAL_CBS") String category,
            @ToolParam(description = "Severity from classification: MEDIUM, HIGH or CRITICAL") String severity,
            @ToolParam(description = "Component that logged the error, e.g. upi-switch") String component,
            @ToolParam(description = "Processing stage, e.g. CBS_DEBIT") String stage,
            @ToolParam(description = "UPI response code, e.g. 91") String respCode,
            @ToolParam(description = "Raw reason text from the error event") String reason,
            @ToolParam(description = "RRN of this event, or null if none") String rrn,
            @ToolParam(description = "One-sentence probable cause") String probableCause,
            @ToolParam(description = "Runbook reference from the rule, or null") String runbookRef,
            @ToolParam(description = "Event timestamp as ISO-8601 local datetime, e.g. 2026-10-05T10:42:17") String eventTimestamp) {

        long start = System.currentTimeMillis();
        System.out.println(">>> [TOOL START] create_incident(title=" + title
                + ", severity=" + severity + ", stage=" + stage + ", respCode=" + respCode + ")");
        log.info("[TOOL START] create_incident(title={}, severity={}, stage={}, respCode={})",
                title, severity, stage, respCode);

        if (severity == null || !INCIDENT_SEVERITIES.contains(severity.toUpperCase())) {
            IncidentActionResult skipped = IncidentActionResult.skipped(
                    "Severity " + severity + " does not require an incident (need MEDIUM/HIGH/CRITICAL)");
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("<<< [TOOL DONE ] create_incident -> SKIPPED: " + skipped.message()
                    + " (" + elapsed + "ms)");
            log.info("[TOOL DONE] create_incident skipped: {} ({}ms)", skipped.message(), elapsed);
            return skipped;
        }

        String errorSignature = buildErrorSignature(component, stage, respCode, reason);
        LocalDateTime seenAt = parseTimestamp(eventTimestamp);

        Optional<ExistingIncident> existing = jdbc.sql("""
                        SELECT id, affected_count, sample_rrns
                        FROM incidents
                        WHERE status IN ('OPEN', 'IN_PROGRESS')
                          AND error_signature = :sig
                        LIMIT 1
                        """)
                .param("sig", errorSignature)
                .query((rs, i) -> new ExistingIncident(
                        rs.getLong("id"),
                        rs.getInt("affected_count"),
                        rs.getString("sample_rrns")))
                .optional();

        IncidentActionResult result;
        if (existing.isPresent()) {
            result = updateExisting(existing.get(), rrn, seenAt, probableCause, severity);
        } else {
            result = insertNew(title, category, severity.toUpperCase(), errorSignature,
                    rrn, seenAt, probableCause, runbookRef);
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("<<< [TOOL DONE ] create_incident -> " + result.action()
                + " " + result.incidentNo()
                + " affected=" + result.affectedCount()
                + " (" + elapsed + "ms)");
        log.info("[TOOL DONE] create_incident(sig={}) -> {} {} affected={} in {}ms",
                errorSignature, result.action(), result.incidentNo(), result.affectedCount(), elapsed);
        return result;
    }

    private IncidentActionResult updateExisting(ExistingIncident existing, String rrn,
                                                LocalDateTime seenAt, String probableCause,
                                                String severity) {
        int newCount = existing.affectedCount() + 1;
        String sampleRrns = mergeRrn(existing.sampleRrnsJson(), rrn);

        jdbc.sql("""
                UPDATE incidents
                SET affected_count = :count,
                    last_seen = :seen,
                    sample_rrns = CAST(:rrns AS JSON),
                    probable_cause = COALESCE(:cause, probable_cause),
                    severity = CASE
                        WHEN :sev = 'CRITICAL' THEN 'CRITICAL'
                        WHEN :sev = 'HIGH' AND severity <> 'CRITICAL' THEN 'HIGH'
                        ELSE severity
                    END
                WHERE id = :id
                """)
                .param("count", newCount)
                .param("seen", seenAt)
                .param("rrns", sampleRrns)
                .param("cause", probableCause)
                .param("sev", severity.toUpperCase())
                .param("id", existing.id())
                .update();

        return new IncidentActionResult(
                "UPDATED",
                existing.id(),
                formatIncidentNo(existing.id()),
                newCount,
                "Updated existing open incident for this error signature");
    }

    private IncidentActionResult insertNew(String title, String category, String severity,
                                           String errorSignature, String rrn,
                                           LocalDateTime seenAt, String probableCause,
                                           String runbookRef) {
        String sampleRrns = rrn != null && !rrn.isBlank()
                ? toJsonArray(List.of(rrn))
                : "[]";

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO incidents
                    (title, category, severity, status, error_signature, affected_count,
                     sample_rrns, first_seen, last_seen, probable_cause, runbook_ref, created_by)
                VALUES
                    (:title, :category, :severity, 'OPEN', :sig, 1,
                     CAST(:rrns AS JSON), :seen, :seen, :cause, :runbook, 'upi-ops-agent')
                """)
                .param("title", title)
                .param("category", category)
                .param("severity", severity)
                .param("sig", errorSignature)
                .param("rrns", sampleRrns)
                .param("seen", seenAt)
                .param("cause", probableCause)
                .param("runbook", runbookRef)
                .update(keyHolder);

        // MySQL returns BigInteger for AUTO_INCREMENT; convert via Number
        Number key = keyHolder.getKey();
        Long id = key != null
                ? key.longValue()
                : jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        return new IncidentActionResult(
                "CREATED",
                id,
                formatIncidentNo(id),
                1,
                "Created new OPEN incident");
    }

    static String buildErrorSignature(String component, String stage, String respCode, String reason) {
        String reasonNorm = reason == null ? "" : reason.replaceAll("[0-9]+", "#");
        return String.join("|",
                nullToEmpty(component),
                nullToEmpty(stage),
                nullToEmpty(respCode),
                reasonNorm);
    }

    private String mergeRrn(String existingJson, String rrn) {
        List<String> rrns = new ArrayList<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                rrns.addAll(objectMapper.readValue(existingJson, new TypeReference<>() {}));
            } catch (Exception e) {
                log.warn("Could not parse sample_rrns JSON: {}", existingJson);
            }
        }
        if (rrn != null && !rrn.isBlank() && !rrns.contains(rrn)) {
            rrns.add(rrn);
            // keep a bounded sample
            if (rrns.size() > 20) {
                rrns = rrns.subList(rrns.size() - 20, rrns.size());
            }
        }
        return toJsonArray(rrns);
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static String formatIncidentNo(long id) {
        return "INC-%05d".formatted(id);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record ExistingIncident(long id, int affectedCount, String sampleRrnsJson) {}
}
