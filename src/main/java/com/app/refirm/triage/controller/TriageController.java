package com.app.refirm.triage.controller;

import com.app.refirm.core.service.LegalRetrievalService;
import com.app.refirm.triage.dto.CategorizationResult;
import com.app.refirm.triage.dto.FullIntakeResponse;
import com.app.refirm.triage.dto.IntakeMessageRequest;
import com.app.refirm.triage.dto.IntakeMessageResponse;
import com.app.refirm.triage.dto.TriageEvaluation;
import com.app.refirm.triage.entities.LegalCategory;
import com.app.refirm.triage.entities.TriageSession;
import com.app.refirm.triage.pipeline.TriagePipelineOrchestrator;
import com.app.refirm.triage.service.TriageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for multi-turn AI legal triage intake and direct classification.
 */
@RestController
@RequestMapping({"/triage", "/api/triage", "/api/v1.0/triage"})
@Slf4j
public class TriageController {

    private final TriageService triageService;
    private final LegalRetrievalService legalRetrievalService;
    private final TriagePipelineOrchestrator pipelineOrchestrator;

    public TriageController(
            TriageService triageService,
            LegalRetrievalService legalRetrievalService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TriagePipelineOrchestrator pipelineOrchestrator) {
        this.triageService = triageService;
        this.legalRetrievalService = legalRetrievalService;
        this.pipelineOrchestrator = pipelineOrchestrator;
    }

    // ──────────────────────────────────────────────
    //  POST /triage/v2/pipeline — Two-Pass Deterministic Legal Pipeline
    // ──────────────────────────────────────────────

    @PostMapping({"/v2/pipeline", "/pipeline/v2", "/pipeline-brief"})
    public ResponseEntity<Map<String, String>> processTwoPassPipeline(@RequestBody Map<String, String> payload) {
        String story = payload.get("userNarrative");
        if (story == null || story.isBlank()) {
            story = payload.get("story");
        }
        if (story == null || story.isBlank()) {
            story = payload.get("narrative");
        }
        if (story == null || story.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Story or narrative cannot be empty"));
        }
        String location = payload.getOrDefault("location", "").trim();
        if (pipelineOrchestrator == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Two-pass pipeline orchestrator is not configured"));
        }
        String brief = pipelineOrchestrator.processCase(story, location);
        return ResponseEntity.ok(Map.of("brief", brief, "location", location.isEmpty() ? "Dynamic (From Narrative)" : location));
    }

    // ──────────────────────────────────────────────
    //  POST /triage/classify or /api/triage/classify — Direct intake classification
    // ──────────────────────────────────────────────

    @PostMapping("/classify")
    public ResponseEntity<CategorizationResult> classify(@RequestBody Map<String, String> payload) {
        String story = payload.get("story");
        if (story == null || story.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        CategorizationResult result = triageService.classifyIntake(story);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────
    //  POST /triage/process or /pipeline — End-to-end legal triage & drafting pipeline
    // ──────────────────────────────────────────────

    @PostMapping({"/process", "/pipeline"})
    public ResponseEntity<FullIntakeResponse> processIntake(@RequestBody Map<String, String> payload) {
        String story = payload.get("story");
        if (story == null || story.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        FullIntakeResponse response = triageService.processIntakePipeline(story);
        return ResponseEntity.ok(response);
    }

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
    //  POST /triage/{sessionId}/force-complete — Soft Gate override
    // ──────────────────────────────────────────────

    @PostMapping("/{sessionId}/force-complete")
    public ResponseEntity<Map<String, Object>> forceCompleteSession(@PathVariable UUID sessionId) {
        String clerkUserId = getAuthenticatedUserId();
        try {
            triageService.forceCompleteSession(sessionId, clerkUserId);
            return ResponseEntity.ok(Map.of(
                    "sessionId", sessionId,
                    "status", "READY_FOR_DRAFTING",
                    "message", "Session marked as complete at user's request. You may now generate the brief."
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    //  GET /triage/internal/test/resolver — Temporary test endpoint for Phase 2 OKF Resolver
    // ──────────────────────────────────────────────

    @GetMapping("/internal/test/resolver")
    public ResponseEntity<String> testResolver() {
        List<String> mockTriggers = List.of("Rent delay", "Lockout by landlord", "Disconnection of electricity");
        String context = legalRetrievalService.resolveStatutePack(LegalCategory.TENANCY, mockTriggers);
        return ResponseEntity.ok(context);
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
