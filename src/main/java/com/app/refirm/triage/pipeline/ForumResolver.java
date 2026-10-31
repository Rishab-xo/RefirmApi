package com.app.refirm.triage.pipeline;

import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.ResolvedForum;

/**
 * Step 3 Pipeline Interface: Deterministic Forum Resolution (Strategy Pattern).
 */
public interface ForumResolver {
    /**
     * Deterministically calculates the correct legal forum based on domain and pecuniary limits.
     * Uses the Strategy Pattern (e.g., ConsumerForumResolver vs ReraForumResolver).
     *
     * @param facts        Structured facts from Pass 1
     * @param userLocation Geographic jurisdiction / location of dispute
     * @return Deterministically resolved forum and filing gateway
     */
    ResolvedForum resolve(ExtractedFacts facts, String userLocation);

    /**
     * Identifies if this specific strategy applies to the given domain.
     *
     * @param domain Domain to evaluate
     * @return true if this resolver supports the given domain
     */
    boolean supports(LegalDomain domain);
}
