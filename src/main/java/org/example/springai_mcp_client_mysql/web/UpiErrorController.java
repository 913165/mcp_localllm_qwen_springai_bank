package org.example.springai_mcp_client_mysql.web;

import org.example.springai_mcp_client_mysql.model.ClassificationResult;
import org.example.springai_mcp_client_mysql.model.UpiErrorEvent;
import org.example.springai_mcp_client_mysql.service.UpiErrorClassificationService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/errors")
public class UpiErrorController {

    private final UpiErrorClassificationService service;
    private final ChatModel chatModel;
    private final String configuredModel;
    private final String baseUrl;

    public UpiErrorController(
            UpiErrorClassificationService service,
            ChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model}") String configuredModel,
            @Value("${spring.ai.openai.base-url}") String baseUrl) {
        this.service = service;
        this.chatModel = chatModel;
        this.configuredModel = configuredModel;
        this.baseUrl = baseUrl;
    }

    @PostMapping
    public ResponseEntity<?> receive(@RequestBody UpiErrorEvent event) {
        if (event.respCode() == null || event.stage() == null || event.reason() == null) {
            return ResponseEntity.badRequest().body("respCode, stage and reason are required");
        }
        ClassificationResult result = service.classify(event);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/print")
    public ResponseEntity<Map<String, String>> printModel() {
        String runtimeModel = chatModel.getOptions().getModel();
        String model = runtimeModel != null ? runtimeModel : configuredModel;

        System.out.println("AI model: " + model + " (base-url: " + baseUrl + ")");

        return ResponseEntity.ok(Map.of(
                "model", model,
                "baseUrl", baseUrl,
                "chatModel", chatModel.getClass().getSimpleName()));
    }
}