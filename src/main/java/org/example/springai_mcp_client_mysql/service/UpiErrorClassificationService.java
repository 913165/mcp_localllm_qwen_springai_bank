package org.example.springai_mcp_client_mysql.service;

import org.example.springai_mcp_client_mysql.model.ClassificationResult;
import org.example.springai_mcp_client_mysql.model.UpiErrorEvent;
import org.example.springai_mcp_client_mysql.tools.ClassificationTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class UpiErrorClassificationService {

    private static final Logger log = LoggerFactory.getLogger(UpiErrorClassificationService.class);

    private static final String SYSTEM_PROMPT = """
            You are a UPI operations assistant for ABC Bank.
            For each UPI error event you receive:
            1. Call the classify_error tool with the event's respCode and stage.
            2. If a rule matched, use its category and default severity. Do not downgrade it.
               You may raise severity by one level only if the event clearly shows wider impact
               (for example a very large amount, or a message about money not being reversed).
            3. If no rule matched, judge category and severity from the reason text, set
               confidence at or below 0.6, and say so in reasoning.
            4. createIncident is true only for MEDIUM, HIGH or CRITICAL.
            5. Write probableCause in one short sentence and reasoning in at most two sentences.
            Use runbookRef from the rule, or null if none.
            """;

    private final ChatClient chatClient;
    private final ClassificationTools classificationTools;

    public UpiErrorClassificationService(
            ChatClient chatClient,
            ClassificationTools classificationTools) {
        this.chatClient = chatClient;
        this.classificationTools = classificationTools;
    }

    public ClassificationResult classify(UpiErrorEvent e) {
        long start = System.currentTimeMillis();
        System.out.println("=== [LLM START] classify respCode=" + e.respCode() + " stage=" + e.stage() + " ===");
        log.info("[LLM START] classify(respCode={}, stage={})", e.respCode(), e.stage());

        ClassificationResult result = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(formatEvent(e))
                .tools(classificationTools)
                .call()
                .entity(ClassificationResult.class);

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("=== [LLM DONE ] classify -> " + result.category()
                + " / " + result.severity()
                + " createIncident=" + result.createIncident()
                + " (" + elapsed + "ms) ===");
        log.info("[LLM DONE] classify -> {} / {} createIncident={} ({}ms)",
                result.category(), result.severity(), result.createIncident(), elapsed);
        return result;
    }

    private static String formatEvent(UpiErrorEvent e) {
        return """
                New UPI error event:
                timestamp=%s
                component=%s
                stage=%s
                respCode=%s
                reason="%s"
                rrn=%s
                txnId=%s
                amount=%s
                extra=%s
                """.formatted(e.timestamp(), e.component(), e.stage(), e.respCode(),
                e.reason(), e.rrn(), e.txnId(), e.amount(), e.extra());
    }
}
