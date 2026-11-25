package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentSearchCriteria;

/**
 * Port interface for multi-channel candidate retrieval before legal reranking.
 */
public interface PrecedentRecallPort {

    /**
     * Recalls candidate precedents across multiple search channels and performs Reciprocal Rank Fusion (RRF).
     *
     * @param query sanitized query and intent
     * @param criteria search criteria (domain, topic, court filters, tuples)
     * @return RecallPool containing Top-100 candidates with individual channel ranks
     */
    RecallPool recallCandidates(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria);
}
