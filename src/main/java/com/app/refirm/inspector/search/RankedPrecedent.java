package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;

/**
 * Precedent candidate with final 0-100 score and transparent component breakdown.
 */
public record RankedPrecedent(
        PrecedentIndexRecord record,
        double finalScore,
        RankingScoreBreakdown breakdown
) {}
