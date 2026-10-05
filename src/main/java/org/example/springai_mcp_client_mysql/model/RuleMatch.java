package org.example.springai_mcp_client_mysql.model;

/** What the classify_error tool returns to the LLM. */
public record RuleMatch(
        boolean matched,
        String category,
        String defaultSeverity,
        boolean createIncident,
        String runbookRef,
        String description,
        String guidance) {

    public static RuleMatch noMatch() {
        return new RuleMatch(false, null, null, false, null,
                "No rule found for this response code and stage",
                "Unknown error pattern. Judge category and severity from the reason text.");
    }
}