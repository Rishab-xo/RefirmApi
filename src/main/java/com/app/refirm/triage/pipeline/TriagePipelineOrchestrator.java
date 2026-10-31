package com.app.refirm.triage.pipeline;

import com.app.refirm.triage.domain.TriagePipelineResult;

/**
 * Master Pipeline Interface: Orchestrates the multi-stage triage and drafting pipeline.
 */
public interface TriagePipelineOrchestrator {

    /**
     * Executes the complete legal triage and brief generation pipeline:
     * <ol>
     *   <li>Extracts structured facts from the raw narrative (Pass 1).</li>
     *   <li>Deterministically resolves the appropriate forum via strategy pattern.</li>
     *   <li>Retrieves applicable statutory provisions.</li>
     *   <li>Drafts the authoritative legal brief (Pass 2).</li>
     * </ol>
     *
     * @param userNarrative The unstructured factual background provided by the user
     * @param userLocation  The geographical jurisdiction or location of the user
     * @return Fully formatted strategic legal brief in Markdown
     */
    String processCase(String userNarrative, String userLocation);

    /**
     * Executes the pipeline and returns both the assembled DraftingContext and generated brief.
     *
     * @param userNarrative The unstructured factual background provided by the user
     * @param userLocation  The geographical jurisdiction or location of the user
     * @return TriagePipelineResult containing DraftingContext and briefMarkdown
     */
    default TriagePipelineResult executeWithContext(String userNarrative, String userLocation) {
        String brief = processCase(userNarrative, userLocation);
        return new TriagePipelineResult(null, brief);
    }
}

