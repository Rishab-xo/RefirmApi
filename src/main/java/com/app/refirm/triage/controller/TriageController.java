package com.app.refirm.triage.controller;

import com.app.refirm.triage.dto.IntakeMessageRequest;
import com.app.refirm.triage.dto.IntakeMessageResponse;
import com.app.refirm.triage.dto.TriageEvaluation;
import com.app.refirm.triage.entities.TriageSession;
import com.app.refirm.triage.service.TriageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for multi-turn AI legal triage intake with chat memory.
 * Endpoints are JWT-protected (Zone 2 in SecurityConfig).
 */
@RestController
@RequestMapping("/triage")
@RequiredArgsConstructor
@Slf4j
public class TriageController {

    private final TriageService triageService;

    // ──────────────────────────────────────────────
    //  POST /triage/chat — Multi-turn chat with memory
    // ──────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<IntakeMessageResponse> chat(
            @RequestBody IntakeMessageRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();

        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message cannot be empty");
        }

        // If sessionId not provided in payload, create a persistent session
        UUID activeSessionId = request.sessionId();
        if (activeSessionId == null) {
            TriageSession newSession = triageService.createSession(clerkUserId);
            activeSessionId = newSession.getSessionId();
        }

        try {
            TriageEvaluation evaluation = triageService.processIntakeMessage(
                    activeSessionId, clerkUserId, request.message().trim()
            );

            IntakeMessageResponse response = new IntakeMessageResponse(
                    activeSessionId,
                    evaluation.nextQuestion(),
                    evaluation.isComplete() ? "READY_FOR_DRAFTING" : "IN_PROGRESS",
                    evaluation.isComplete(),
                    evaluation.issueCategory(),
                    evaluation.isEmergency(),
                    evaluation.extractedFacts(),
                    evaluation.factChecklist()
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    //  POST /triage/start — Create a new session
    // ──────────────────────────────────────────────

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startSession() {
        String clerkUserId = getAuthenticatedUserId();

        TriageSession session = triageService.createSession(clerkUserId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                        "sessionId", session.getSessionId(),
                        "status", session.getStatus(),
                        "message", "Triage session created. Send your first message to begin intake."
                ));
    }

    // ──────────────────────────────────────────────
    //  POST /triage/{sessionId}/message — Send a message
    // ──────────────────────────────────────────────

    @PostMapping("/{sessionId}/message")
    public ResponseEntity<IntakeMessageResponse> sendMessage(
            @PathVariable UUID sessionId,
            @RequestBody IntakeMessageRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();

        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message cannot be empty");
        }

        try {
            TriageEvaluation evaluation = triageService.processIntakeMessage(
                    sessionId, clerkUserId, request.message().trim()
            );

            IntakeMessageResponse response = new IntakeMessageResponse(
                    sessionId,
                    evaluation.nextQuestion(),
                    evaluation.isComplete() ? "READY_FOR_DRAFTING" : "IN_PROGRESS",
                    evaluation.isComplete(),
                    evaluation.issueCategory(),
                    evaluation.isEmergency(),
                    evaluation.extractedFacts(),
                    evaluation.factChecklist()
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    //  GET /triage/{sessionId} — Get session state
    // ──────────────────────────────────────────────

    @GetMapping("/{sessionId}")
    public ResponseEntity<TriageSession> getSession(@PathVariable UUID sessionId) {
        String clerkUserId = getAuthenticatedUserId();

        try {
            TriageSession session = triageService.getSession(sessionId, clerkUserId);
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ──────────────────────────────────────────────

    private String getAuthenticatedUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            return "test_user_dev";
        }
        return authentication.getName();
    }
}
