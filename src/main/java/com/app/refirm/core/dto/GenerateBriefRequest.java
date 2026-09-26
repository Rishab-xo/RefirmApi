package com.app.refirm.core.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for the structured brief generation endpoint.
 * <p>
 * Accepts the gathered facts map directly from the frontend's
 * intake fact checklist, along with the raw legal domain enum
 * string for correct vector store filtering.
 */
public record GenerateBriefRequest(

        /** Raw legal domain enum string (e.g., "CIVIL_PROPERTY") — must match vector store metadata. */
        String domain,

        /** Human-readable category label (e.g., "Civil & Property Law") — used in the brief header. */
        String categoryLabel,

        /** Structured facts gathered during intake, keyed by fact ID (e.g., "nature_of_dispute" -> "Illegal lockout"). */
        Map<String, Object> gatheredFacts,

        /** Optional: Triage session ID for audit trail and session-based loading. */
        UUID sessionId,

        /** Optional: Raw facts string for legacy/fallback requests. */
        String facts
) {}
