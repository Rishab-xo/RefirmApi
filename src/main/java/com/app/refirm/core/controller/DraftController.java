package com.app.refirm.core.controller;

import com.app.refirm.core.dto.GenerateBriefRequest;
import com.app.refirm.core.dto.GenerateBriefResponse;
import com.app.refirm.core.service.BriefGenerationService;
import com.app.refirm.triage.entities.LegalCategory;
import com.app.refirm.triage.entities.TriageSession;
import com.app.refirm.triage.repo.TriageSessionRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/draft")
public class DraftController {

    private final TriageSessionRepo sessionRepo;
    private final BriefGenerationService briefGenerationService;
    private final ObjectMapper objectMapper;

    public DraftController(TriageSessionRepo sessionRepo, BriefGenerationService briefGenerationService, ObjectMapper objectMapper) {
        this.sessionRepo = sessionRepo;
        this.briefGenerationService = briefGenerationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Primary endpoint invoked by DocumentFlow.
     * Accepts structured facts, domain, and optional sessionId in the request body.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateBriefResponse> generateBriefFromRequest(@RequestBody GenerateBriefRequest request) {

        // 1. Get the raw string from the frontend payload
        String rawDomain = (request.domain() != null && !request.domain().isBlank())
                ? request.domain()
                : "CIVIL_PROPERTY";

        // 2. THE FIX: Safely parse the frontend string into your Enum, then extract the correct DB Domain
        String legalDomain = LegalCategory.fromStringSafe(rawDomain).getDbDomain();

        String categoryLabel = (request.categoryLabel() != null && !request.categoryLabel().isBlank())
                ? request.categoryLabel()
                : LegalCategory.fromStringSafe(rawDomain).getDisplayLabel();

        Map<String, String> factsMap = new java.util.HashMap<>();

        // Read facts from the request payload initially
        if (request.gatheredFacts() != null) {
            for (Map.Entry<String, ?> entry : request.gatheredFacts().entrySet()) {
                Object rawVal = entry.getValue();
                if (rawVal != null) {
                    String strVal = String.valueOf(rawVal).trim();
                    if (!strVal.isEmpty() && !"null".equalsIgnoreCase(strVal)) {
                        factsMap.put(entry.getKey(), strVal);
                    }
                }
            }
        }

        // SESSION-FIRST LOGIC: If a sessionId is passed, it completely overrides the frontend payload
        if (request.sessionId() != null) {
            TriageSession session = sessionRepo.findById(request.sessionId()).orElse(null);

            if (session != null) {
                // 1. Force the domain to the one securely stored in the DB
                if (session.getIssueCategory() != null) {
                    legalDomain = session.getIssueCategory().getDbDomain();
                    categoryLabel = session.getIssueCategory().getDisplayLabel();
                }

                // 2. Force the facts to the ones securely stored in the DB
                try {
                    String rawFacts = session.getGatheredFacts();
                    if (rawFacts != null && !rawFacts.isBlank()) {
                        Map<String, Object> rawMap = objectMapper.readValue(rawFacts, new TypeReference<Map<String, Object>>() {});
                        if (rawMap != null) {
                            // Clear any unverified facts sent by the frontend
                            factsMap.clear();

                            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                                Object rawVal = entry.getValue();
                                if (rawVal != null) {
                                    String strVal = String.valueOf(rawVal).trim();
                                    if (!strVal.isEmpty() && !"null".equalsIgnoreCase(strVal)) {
                                        factsMap.put(entry.getKey(), strVal);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Ignored intentionally, will fall back to frontend facts if DB facts fail to parse
                }
            }
        }

        GenerateBriefResponse draftResponse;
        if (factsMap != null && !factsMap.isEmpty()) {
            draftResponse = briefGenerationService.generateDraftFromFacts(
                    factsMap,
                    legalDomain,
                    categoryLabel
            );
        } else {
            String fallbackFacts = (request.facts() != null && !request.facts().isBlank())
                    ? request.facts()
                    : "No specific facts provided.";
            draftResponse = briefGenerationService.generateDraft(
                    fallbackFacts,
                    legalDomain
            );
        }

        return ResponseEntity.ok(draftResponse);
    }

    /**
     * Session-based endpoint. Enforces that the session is marked READY_FOR_DRAFTING.
     */
    @PostMapping({"/generate/{sessionId}", "/generate-from-session/{sessionId}"})
    public ResponseEntity<GenerateBriefResponse> generateBrief(@PathVariable UUID sessionId) {
        // 1. Fetch the completed session
        TriageSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        // 2. Enforce the completion gate
        if (!"READY_FOR_DRAFTING".equals(session.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Intake is not yet complete. Current status: " + session.getStatus()
            );
        }

        // 3. Extract metadata labels using DB Domain mapping
        String legalDomain = session.getIssueCategory().getDbDomain();
        String categoryLabel = session.getIssueCategory().getDisplayLabel();

        // 4. Deserialize JSON facts string using TypeReference<Map<String, Object>> armor
        Map<String, String> factsMap = new java.util.HashMap<>();
        try {
            String rawFacts = session.getGatheredFacts();
            if (rawFacts != null && !rawFacts.isBlank()) {
                Map<String, Object> rawMap = objectMapper.readValue(rawFacts, new TypeReference<Map<String, Object>>() {});
                if (rawMap != null) {
                    for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                        Object rawVal = entry.getValue();
                        if (rawVal != null) {
                            String strVal = String.valueOf(rawVal).trim();
                            if (!strVal.isEmpty() && !"null".equalsIgnoreCase(strVal)) {
                                factsMap.put(entry.getKey(), strVal);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse gathered facts for brief generation",
                    e
            );
        }

        // 5. Trigger the RAG pipeline and LLM generation
        GenerateBriefResponse draftResponse = briefGenerationService.generateDraftFromFacts(
                factsMap,
                legalDomain,
                categoryLabel
        );

        return ResponseEntity.ok(draftResponse);
    }
}