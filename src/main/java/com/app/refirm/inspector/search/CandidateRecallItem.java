package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;

import java.util.Collections;
import java.util.Map;

/**
 * Precedent candidate recalled from one or more channels, scored via Reciprocal Rank Fusion (RRF).
 */
public record CandidateRecallItem(
        PrecedentIndexRecord record,
        double rrfScore,
        Map<String, Integer> channelRanks
) {
    public CandidateRecallItem {
        channelRanks = channelRanks != null ? Collections.unmodifiableMap(channelRanks) : Collections.emptyMap();
    }
}
