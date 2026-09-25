package com.app.refirm.triage.dto;

import com.app.refirm.triage.entities.LegalCategory;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * API response for the intake chat endpoint.
 * Provides multi-turn status ("IN_PROGRESS" / "READY_FOR_DRAFTING"), AI reply,
 * extracted legal facts, and structured fact coverage checklist.
 */
public record IntakeMessageResponse(
        UUID sessionId,
        String reply,
        String status,
        @JsonProperty("isComplete") boolean isComplete,
        LegalCategory issueCategory,
        @JsonProperty("isEmergency") boolean isEmergency,
        List<String> extractedFacts,
        FactChecklist factChecklist
) {
    public IntakeMessageResponse(
            UUID sessionId,
            boolean isComplete,
            String nextQuestion,
            LegalCategory issueCategory,
            boolean isEmergency,
            List<String> extractedFacts
    ) {
        this(
                sessionId,
                nextQuestion,
                isComplete ? "READY_FOR_DRAFTING" : "IN_PROGRESS",
                isComplete,
                issueCategory,
                isEmergency,
                extractedFacts,
                FactChecklist.empty()
        );
    }

    public String nextQuestion() {
        return reply;
    }

    public boolean complete() {
        return isComplete;
    }

    public boolean emergency() {
        return isEmergency;
    }
}
