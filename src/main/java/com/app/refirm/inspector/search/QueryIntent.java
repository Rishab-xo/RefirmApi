package com.app.refirm.inspector.search;

/**
 * Classification of user query intent for optimal multi-channel search weighting.
 */
public enum QueryIntent {
    /**
     * Exact case name, party match, or formal reporter citation.
     * Weights trigram / known-item heavily; disables semantic embedding.
     */
    KNOWN_ITEM,

    /**
     * Query consists solely of statutory references (e.g. "Section 438 CrPC").
     * Weights tuple GIN containment heavily; skips BM25 text match.
     */
    STATUTE_ONLY,

    /**
     * Fact-pattern, legal issue, or mixed statute + legal proposition query.
     * Balanced fusion of BM25, Dense Vector (halfvec), Tuple GIN, and Trigram.
     */
    HYBRID_LEGAL
}
