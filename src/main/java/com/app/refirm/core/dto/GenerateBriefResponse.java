package com.app.refirm.core.dto;

/**
 * Response DTO for the structured brief generation endpoint.
 * <p>
 * Returns both the generated petition markdown and metadata about
 * which statutes were retrieved, so the frontend can display
 * provenance/trust indicators.
 */
public record GenerateBriefResponse(

        /** The generated legal petition in Markdown format. */
        String draft,

        /** The raw statutes text that was retrieved from the vector store and fed to the LLM. */
        String retrievedStatutes,

        /** Whether the draft was generated with actual statutes (true) or with a "no statutes" fallback (false). */
        boolean hasStatutes
) {}
