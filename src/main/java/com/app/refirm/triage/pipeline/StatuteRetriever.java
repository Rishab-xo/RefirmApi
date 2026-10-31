package com.app.refirm.triage.pipeline;

import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.StatutePin;

import java.util.List;

/**
 * Step 2 Pipeline Interface: Statute Retrieval.
 */
public interface StatuteRetriever {
    record StatuteRetrievalResult(
            List<StatutePin> pins, 
            List<String> assumptionCaveats,
            Integer limitationMonths,
            Boolean civilCourtsBarred
    ) {
        public StatuteRetrievalResult(List<StatutePin> pins, List<String> assumptionCaveats) {
            this(pins, assumptionCaveats, null, null);
        }
    }

    StatuteRetrievalResult retrieveStatutes(ExtractedFacts facts);
}
