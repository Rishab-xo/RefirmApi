package com.app.refirm.triage.pipeline;

import com.app.refirm.triage.domain.ExtractedFacts;

/**
 * Step 1 Pipeline Interface: Fact Extraction.
 */
public interface FactExtractor {
    /**
     * Pass 1: Extracts structured facts and entity roles from the raw user narrative using gpt-6-luna.
     *
     * @param userNarrative Unstructured client input
     * @return Immutable extracted legal facts and roles
     */
    ExtractedFacts extract(String userNarrative);
}
