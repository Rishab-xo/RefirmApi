package com.app.refirm.core.controller;

import com.app.refirm.core.dto.GenerateBriefResponse;
import com.app.refirm.core.service.BriefGenerationService;
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

    @PostMapping("/generate/{sessionId}")
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

        // 3. Extract metadata labels
        String legalDomain = session.getIssueCategory().name();
        String categoryLabel = session.getIssueCategory().getDisplayLabel();

        // 4. Deserialize JSON facts string into Map<String, String>
        Map<String, String> factsMap;
        try {
            String rawFacts = session.getGatheredFacts();
            factsMap = (rawFacts != null && !rawFacts.isBlank())
                    ? objectMapper.readValue(rawFacts, new TypeReference<Map<String, String>>() {})
                    : Map.of();
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