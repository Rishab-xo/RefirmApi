package com.app.refirm.inspector.domain;

import java.util.Collections;
import java.util.List;

/**
 * Criteria record for additive SQL scoring search over Table 1 (legal_precedent_index).
 */
public record PrecedentSearchCriteria(
        List<String> expandedActSectionTuples,
        String propositionQueryText,
        String preferredDomain,
        String topicSlug,
        String issueTag,
        String courtCode,
        int limit
) {
    public PrecedentSearchCriteria {
        expandedActSectionTuples = expandedActSectionTuples != null ? List.copyOf(expandedActSectionTuples) : Collections.emptyList();
        limit = limit > 0 ? limit : 20;
    }

    public static PrecedentSearchCriteria ofTuples(List<String> tuples, int limit) {
        return new PrecedentSearchCriteria(tuples, null, null, null, null, null, limit);
    }

    public static PrecedentSearchCriteria ofTopic(String topicSlug, String issueTag, int limit) {
        return new PrecedentSearchCriteria(List.of(), null, null, topicSlug, issueTag, null, limit);
    }
}

