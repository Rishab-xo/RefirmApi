package com.app.refirm.inspector.search;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pool of candidate precedents retrieved across retrieval channels and ranked by RRF.
 */
public record RecallPool(
        List<CandidateRecallItem> items,
        Map<String, Map<String, Integer>> channelRanks
) {
    public RecallPool {
        items = items != null ? List.copyOf(items) : Collections.emptyList();
        channelRanks = channelRanks != null ? Collections.unmodifiableMap(channelRanks) : Collections.emptyMap();
    }

    public static RecallPool empty() {
        return new RecallPool(Collections.emptyList(), Collections.emptyMap());
    }
}
