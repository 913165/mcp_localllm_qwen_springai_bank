package org.example.springai_mcp_client_mysql.model;

/** Structured output returned by the agent for every event. */
public record ClassificationResult(
        Category category,
        Severity severity,
        boolean createIncident,
        double confidence,
        String probableCause,
        String runbookRef,
        String reasoning) {

    public enum Category {
        TECHNICAL_CBS, INFRA_NPCI, INFRA_HSM, ISSUER_DECLINE,
        BENEFICIARY_DECLINE, CUSTOMER_DECLINE, OTHER
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
}