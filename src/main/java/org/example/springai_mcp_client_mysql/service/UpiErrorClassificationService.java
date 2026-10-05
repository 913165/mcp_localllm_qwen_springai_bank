package org.example.springai_mcp_client_mysql.service;

import org.example.springai_mcp_client_mysql.model.ClassificationResult;
import org.example.springai_mcp_client_mysql.model.UpiErrorEvent;
import org.example.springai_mcp_client_mysql.tools.ClassificationTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class UpiErrorClassificationService {

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

    public UpiErrorClassificationService(ChatClient chatClient, ClassificationTools classificationTools) {
        this.chatClient = chatClient;
        this.classificationTools = classificationTools;
    }

    public ClassificationResult classify(UpiErrorEvent e) {
        String userMessage = """
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

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .tools(classificationTools)
                .call()
                .entity(ClassificationResult.class);
    }
}