package org.example.springai_mcp_client_mysql.web;

import org.example.springai_mcp_client_mysql.model.ClassificationResult;
import org.example.springai_mcp_client_mysql.model.UpiErrorEvent;
import org.example.springai_mcp_client_mysql.service.UpiErrorClassificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/errors")
public class UpiErrorController {

    private final UpiErrorClassificationService service;

    public UpiErrorController(UpiErrorClassificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> receive(@RequestBody UpiErrorEvent event) {
        if (event.respCode() == null || event.stage() == null || event.reason() == null) {
            return ResponseEntity.badRequest().body("respCode, stage and reason are required");
        }
        ClassificationResult result = service.classify(event);
        return ResponseEntity.ok(result);
    }

    // print ai model function
    @PostMapping("/print")
    public ResponseEntity<?> printModel(@RequestBody UpiErrorEvent event) {
        System.out.println(event);
        return ResponseEntity.ok("ok");
    }
}