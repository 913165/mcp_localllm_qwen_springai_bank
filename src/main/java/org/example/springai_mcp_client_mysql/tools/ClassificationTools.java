package org.example.springai_mcp_client_mysql.tools;

import org.example.springai_mcp_client_mysql.model.RuleMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tool 1: classification. Looks up the bank's rule for (respCode, stage)
 * in upi_response_rules. Deterministic: the DB decides the default severity.
 */
@Component
public class ClassificationTools {

    private static final Logger log = LoggerFactory.getLogger(ClassificationTools.class);

    private final JdbcClient jdbc;

    public ClassificationTools(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(name = "classify_error",
          description = "Look up the bank's classification rule for a UPI error. "
                  + "Returns category, default severity, whether an incident is required, "
                  + "runbook reference and guidance. Always call this first for every error event.")
    public RuleMatch classifyError(
            @ToolParam(description = "UPI response code, e.g. 51, 91, 96, U30, ZM") String respCode,
            @ToolParam(description = "Processing stage, e.g. CBS_DEBIT, NPCI_FORWARD, CBS_REVERSAL, BENEFICIARY_CREDIT, HSM_VERIFY") String stage) {

        long start = System.currentTimeMillis();
        RuleMatch result = jdbc.sql("""
                        SELECT category, default_severity, create_incident,
                               runbook_ref, description, guidance
                        FROM upi_response_rules
                        WHERE resp_code = :code AND stage = :stage
                        """)
                .param("code", respCode)
                .param("stage", stage)
                .query((rs, i) -> new RuleMatch(
                        true,
                        rs.getString("category"),
                        rs.getString("default_severity"),
                        rs.getBoolean("create_incident"),
                        rs.getString("runbook_ref"),
                        rs.getString("description"),
                        rs.getString("guidance")))
                .optional()
                .orElseGet(RuleMatch::noMatch);

        log.info("classify_error(respCode={}, stage={}) -> matched={} severity={} in {}ms",
                respCode, stage, result.matched(), result.defaultSeverity(),
                System.currentTimeMillis() - start);
        return result;
    }
}