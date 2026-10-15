package com.app.refirm.triage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Encapsulates the complete end-to-end intake result, including legal domain classification,
 * statutory retrieval triggers, and generated formal legal petition brief.
 */
public record FullIntakeResponse(
        @JsonPropertyDescription("Structured categorization and identified triggers from intake narrative")
        CategorizationResult triageResult,

        @JsonPropertyDescription("Generated formal legal petition and brief draft")
        String brief,

        @JsonPropertyDescription("Total count of key triggers and legal grounds extracted")
        int keyTriggersCount
) {}
