package org.example.springai_mcp_client_mysql.model;

/** API response for classify + optional incident creation. */
public record HandleResult(
        ClassificationResult.Category category,
        ClassificationResult.Severity severity,
        boolean createIncident,
        double confidence,
        String probableCause,
        String runbookRef,
        String reasoning,
        String incidentAction,
        Long incidentId,
        String incidentNo,
        Integer affectedCount,
        String incidentMessage) {
}
