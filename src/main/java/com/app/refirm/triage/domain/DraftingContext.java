package com.app.refirm.triage.domain;

import java.util.List;

/**
 * Immutable master context payload supplied to the final drafting stage.
 *
 * @param userNarrative     Client dispute narrative and factual background
 * @param facts             Structured facts from Pass 1
 * @param statutePack       Pinned statutory provisions from retrieval
 * @param targetForum       Deterministically resolved jurisdiction and filing forum
 * @param assumptionCaveats Caveats and unverified applicability assumptions from rules engine
 * @param limitationMonths  Statutory limitation period in months (if identified)
 * @param civilCourtsBarred Whether civil court jurisdiction is barred under governing statute
 * @param evidenceRule      Statutory evidence rule (e.g. Section 63 BSA 2023 certificate)
 */
public record DraftingContext(
        String userNarrative,
        ExtractedFacts facts,
        List<StatutePin> statutePack,
        ResolvedForum targetForum,
        List<String> assumptionCaveats,
        Integer limitationMonths,
        Boolean civilCourtsBarred,
        String evidenceRule
) {
    public DraftingContext {
        statutePack = statutePack != null ? List.copyOf(statutePack) : List.of();
        assumptionCaveats = assumptionCaveats != null ? List.copyOf(assumptionCaveats) : List.of();
        if (limitationMonths == null && facts != null && facts.domain() != null &&
                (facts.domain() == LegalDomain.CONSUMER_RIGHTS || facts.domain() == LegalDomain.CONSUMER)) {
            limitationMonths = 24; // 2 years under CPA 2019
        }
        if (evidenceRule == null) {
            evidenceRule = "Section 63 certificate under Bharatiya Sakshya Adhiniyam, 2023 (BSA)";
        }
    }

    public DraftingContext(String userNarrative, ExtractedFacts facts, List<StatutePin> statutePack, ResolvedForum targetForum) {
        this(userNarrative, facts, statutePack, targetForum, List.of(), null, null, null);
    }

    public DraftingContext(String userNarrative, ExtractedFacts facts, List<StatutePin> statutePack, ResolvedForum targetForum, List<String> assumptionCaveats) {
        this(userNarrative, facts, statutePack, targetForum, assumptionCaveats, null, null, null);
    }

    public DraftingContext(String userNarrative, ExtractedFacts facts, List<StatutePin> statutePack, ResolvedForum targetForum, List<String> assumptionCaveats, Integer limitationMonths, Boolean civilCourtsBarred) {
        this(userNarrative, facts, statutePack, targetForum, assumptionCaveats, limitationMonths, civilCourtsBarred, null);
    }

    public String forumAnalysis() {
        if (targetForum == null) {
            return null;
        }
        if (targetForum.jurisdictionReason() != null && !targetForum.jurisdictionReason().isBlank()) {
            return targetForum.forumName() + " (" + targetForum.jurisdictionReason() + ")";
        }
        return targetForum.forumName();
    }
}

