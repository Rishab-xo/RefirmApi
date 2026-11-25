package com.app.refirm.inspector.search;

/**
 * Target consumer profile for legal reranking score weighting.
 */
public enum RankingProfile {
    /**
     * Standalone Judgments Library browsing: balanced relevance, high authority, recency.
     */
    SEARCH_LIBRARY,

    /**
     * Argument Preparation: high statutory specificity and matching litigation posture.
     */
    ARGUMENT_PREP,

    /**
     * In-line legal drafting: authoritative landmark precedents directly on-point.
     */
    DRAFTING
}
