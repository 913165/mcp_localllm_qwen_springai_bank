package org.example.springai_mcp_client_mysql.model;

/** What the create_incident tool returns to the LLM. */
public record IncidentActionResult(
        String action,       // CREATED | UPDATED | SKIPPED
        Long incidentId,
        String incidentNo,   // INC-xxxxx
        int affectedCount,
        String message) {

    public static IncidentActionResult skipped(String reason) {
        return new IncidentActionResult("SKIPPED", null, null, 0, reason);
    }
}
