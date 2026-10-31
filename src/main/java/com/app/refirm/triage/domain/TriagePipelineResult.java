package com.app.refirm.triage.domain;

/**
 * Immutable carrier holding both the assembled DraftingContext and the final generated brief Markdown.
 *
 * @param draftingContext The immutable context synthesized from Pass 1, forum resolution, and statute retrieval
 * @param briefMarkdown   The final strategic legal brief generated in Pass 2
 */
public record TriagePipelineResult(
        DraftingContext draftingContext,
        String briefMarkdown
) {
    public TriagePipelineResult {
        briefMarkdown = briefMarkdown != null ? briefMarkdown : "";
    }
}
