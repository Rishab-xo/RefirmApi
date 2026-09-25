package com.app.refirm.core.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic controller to test AI LLM connectivity, model responses, and latency.
 */
@RestController
@RequestMapping("/ai")
@Slf4j
public class AiTestController {

    private final ChatClient chatClient;
    private final ChatClient deepSeekChatClient;

    public AiTestController(
            ChatClient.Builder chatClientBuilder,
            @org.springframework.beans.factory.annotation.Qualifier("deepSeekChatClientBuilder") ChatClient.Builder deepSeekChatClientBuilder
    ) {
        this.chatClient = chatClientBuilder.build();
        this.deepSeekChatClient = deepSeekChatClientBuilder.build();
    }

    /**
     * GET /api/v1.0/ai/test?message=...&model=deepseek
     * Accepts ?message=... or ?prompt=... and optional &model=deepseek.
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testAiGet(
            @RequestParam(required = false) Map<String, String> allParams
    ) {
        String prompt = "Hello! Please reply in one short sentence.";
        String selectedModel = "default (openrouter/free)";

        if (allParams != null) {
            for (Map.Entry<String, String> entry : allParams.entrySet()) {
                String key = entry.getKey().trim();
                String val = entry.getValue() != null ? entry.getValue().trim() : "";
                if ((key.equalsIgnoreCase("message") || key.equalsIgnoreCase("prompt")) && !val.isBlank()) {
                    prompt = val;
                } else if (key.equalsIgnoreCase("model") && !val.isBlank()) {
                    selectedModel = val;
                }
            }
        }
        return executeAiCall(prompt, selectedModel);
    }

    /**
     * POST /api/v1.0/ai/test
     * JSON payload: { "message": "Why is the sky blue?", "model": "deepseek" }
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testAiPost(
            @RequestBody(required = false) Map<String, String> body
    ) {
        String message = (body != null && body.containsKey("message") && !body.get("message").isBlank())
                ? body.get("message")
                : "Hello! Please respond in one short sentence.";
        String selectedModel = (body != null && body.containsKey("model") && !body.get("model").isBlank())
                ? body.get("model")
                : "default (openrouter/free)";

        return executeAiCall(message, selectedModel);
    }

    private ResponseEntity<Map<String, Object>> executeAiCall(String prompt, String selectedModel) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prompt", prompt);
        result.put("modelRequested", selectedModel);

        ChatClient clientToUse = selectedModel.toLowerCase().contains("deepseek")
                ? deepSeekChatClient
                : chatClient;

        long start = System.currentTimeMillis();
        try {
            log.info("Sending AI test prompt using model [{}]: [{}]", selectedModel, prompt);
            String response = clientToUse.prompt()
                    .user(prompt)
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - start;
            result.put("status", "SUCCESS");
            result.put("latencyMs", elapsed);
            result.put("aiResponse", response);

            log.info("AI test completed successfully in {}ms", elapsed);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            result.put("status", "FAILED");
            result.put("latencyMs", elapsed);
            result.put("errorType", e.getClass().getSimpleName());
            result.put("errorMessage", e.getMessage());

            log.error("AI test failed in {}ms: {}", elapsed, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }
}
