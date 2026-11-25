package com.app.refirm.inspector.search;

import java.util.Collections;
import java.util.Map;

/**
 * Transparent scoring breakdown for Milestone 2B legal reranking (returned when debug=true).
 */
public record RankingScoreBreakdown(
        QueryIntent intent,
        double rrfScore,
        double relevancePts,
        double statutePts,
        double authorityPts,
        double recencyPts,
        double domainPosturePts,
        double goodLawPenalty,
        double finalScore,
        Map<String, Integer> channelRanks
) {
    public RankingScoreBreakdown {
        channelRanks = channelRanks != null ? Collections.unmodifiableMap(channelRanks) : Collections.emptyMap();
    }
}
