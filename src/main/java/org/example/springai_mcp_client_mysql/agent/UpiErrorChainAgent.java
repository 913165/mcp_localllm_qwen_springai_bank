package org.example.springai_mcp_client_mysql.agent;

import org.example.springai_mcp_client_mysql.model.ClassificationResult;
import org.example.springai_mcp_client_mysql.model.HandleResult;
import org.example.springai_mcp_client_mysql.model.IncidentActionResult;
import org.example.springai_mcp_client_mysql.model.UpiErrorEvent;
import org.example.springai_mcp_client_mysql.service.UpiErrorClassificationService;
import org.example.springai_mcp_client_mysql.tools.IncidentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simple Chain workflow (Spring AI agentic pattern):
 *   Step 1 — LLM classifies the error (one LLM call)
 *   Step 2 — Java creates/updates the incident from that result (no LLM)
 *
 * Incident creation is driven by classification severity, not by a second LLM tool call.
 */
@Component
public class UpiErrorChainAgent {

    private static final Logger log = LoggerFactory.getLogger(UpiErrorChainAgent.class);

    private final UpiErrorClassificationService classificationService;
    private final IncidentTools incidentTools;

    public UpiErrorChainAgent(
            UpiErrorClassificationService classificationService,
            IncidentTools incidentTools) {
        this.classificationService = classificationService;
        this.incidentTools = incidentTools;
    }

    public HandleResult handle(UpiErrorEvent event) {
        System.out.println("=== [CHAIN START] step1=classify, step2=maybe create_incident ===");
        log.info("[CHAIN START] respCode={} stage={}", event.respCode(), event.stage());

        // Step 1: one LLM classification call
        ClassificationResult c = classificationService.classify(event);

        // Step 2: create incident in code when severity says so (no second LLM call)
        IncidentActionResult incident;
        if (c.createIncident()) {
            System.out.println("--- [CHAIN] step2: create_incident (severity=" + c.severity() + ") ---");
            String title = "UPI " + event.stage() + " failures: " + event.respCode() + " on " + event.component();
            String ts = event.timestamp() != null ? event.timestamp().toString() : null;

            incident = incidentTools.createIncident(
                    title,
                    c.category().name(),
                    c.severity().name(),
                    event.component(),
                    event.stage(),
                    event.respCode(),
                    event.reason(),
                    event.rrn(),
                    c.probableCause(),
                    c.runbookRef(),
                    ts);
        } else {
            System.out.println("--- [CHAIN] step2: SKIP incident (createIncident=false) ---");
            incident = IncidentActionResult.skipped("Classification createIncident=false");
        }

        HandleResult result = new HandleResult(
                c.category(),
                c.severity(),
                c.createIncident(),
                c.confidence(),
                c.probableCause(),
                c.runbookRef(),
                c.reasoning(),
                incident.action(),
                incident.incidentId(),
                incident.incidentNo(),
                incident.affectedCount() > 0 ? incident.affectedCount() : null,
                incident.message());

        System.out.println("=== [CHAIN DONE ] " + result.category() + " / " + result.severity()
                + " incident=" + result.incidentAction() + " " + result.incidentNo() + " ===");
        log.info("[CHAIN DONE] {} / {} incident={} {}",
                result.category(), result.severity(), result.incidentAction(), result.incidentNo());
        return result;
    }
}
