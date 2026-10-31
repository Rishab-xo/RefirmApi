package com.app.refirm.rules.evaluation;

import com.app.refirm.rules.model.LegalDomain;

/**
 * Input data bag for the Specification Engine and Luna Fact Extraction.
 * <p>
 * Carries the client-provided facts extracted from the triage intake pipeline
 * that are relevant to statute applicability evaluation.
 * All fields are intentionally nullable — the Specification Engine treats a
 * null field as "fact not yet known" and triggers the {@code UNVERIFIED_APPLICABLE}
 * fail-open path rather than crashing or making a silent wrong assumption.
 *
 * <p><strong>Thread safety:</strong> Java Records are structurally immutable.
 * This DTO can be freely shared across specification evaluations without defensive copies.
 *
 * @param domain                Classified legal domain taxonomy. Nullable.
 * @param disputeAmount         Monetary value of the dispute in INR (e.g., 500000L). Nullable.
 * @param location              City or state where the dispute arose (e.g., "Mumbai"). Nullable.
 * @param counterpartyType      Describing the opposing party (e.g., "Landlord", "Bank"). Nullable.
 * @param propertySizeSqMeters  Area of the subject property in square meters. Nullable.
 */
public record ExtractedFactsDTO(
        LegalDomain domain,
        Long disputeAmount,
        String location,
        String counterpartyType,
        Integer propertySizeSqMeters
) {

    /**
     * Backward-compatible 4-argument constructor for calls omitting explicit domain.
     */
    public ExtractedFactsDTO(Long disputeAmount, String location, String counterpartyType, Integer propertySizeSqMeters) {
        this(null, disputeAmount, location, counterpartyType, propertySizeSqMeters);
    }

    /**
     * Factory method creating an empty DTO where all facts are unknown (null).
     */
    public static ExtractedFactsDTO empty() {
        return new ExtractedFactsDTO(null, null, null, null, null);
    }
}
