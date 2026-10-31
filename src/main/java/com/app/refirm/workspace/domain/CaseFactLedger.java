package com.app.refirm.workspace.domain;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Canonical Case Fact Ledger representing the single source of truth for all dispute facts.
 * Tracks versioning, AI-extracted facts, rule-evaluation facts, and lawyer-locked field safeguards.
 *
 * @param version             Incremental ledger revision (starts at 1)
 * @param extractedFacts      Pass 1 structured entity & party graph from triage
 * @param ruleEvaluationFacts Lightweight scalar facts used by the Specification Engine
 * @param lawyerLockedFields  Field names locked by the advocate to prevent AI regression
 * @param lastUpdatedAt       Timestamp of latest ledger modification
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseFactLedger(
        int version,
        ExtractedFacts extractedFacts,
        ExtractedFactsDTO ruleEvaluationFacts,
        Set<String> lawyerLockedFields,
        Instant lastUpdatedAt
) {
    public CaseFactLedger {
        version = version > 0 ? version : 1;
        lawyerLockedFields = lawyerLockedFields != null ? Collections.unmodifiableSet(lawyerLockedFields) : Collections.emptySet();
        lastUpdatedAt = lastUpdatedAt != null ? lastUpdatedAt : Instant.now();
    }

    public static CaseFactLedger empty() {
        return new CaseFactLedger(1, null, null, Collections.emptySet(), Instant.now());
    }

    public static CaseFactLedger initial(ExtractedFacts facts, ExtractedFactsDTO ruleFacts) {
        return new CaseFactLedger(1, facts, ruleFacts, Collections.emptySet(), Instant.now());
    }

    /**
     * Checks if a specific fact field has been locked against AI overwrite by the advocate.
     *
     * @param fieldName The canonical field identifier (e.g. "claimant", "disputeAmount", "oppositeParties")
     * @return true if explicitly locked
     */
    public boolean isLocked(String fieldName) {
        return lawyerLockedFields != null && fieldName != null && lawyerLockedFields.contains(fieldName);
    }
}
