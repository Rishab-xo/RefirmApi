package com.app.refirm.templates.domain;

/**
 * Ranked recommendation output from the Smart Procedural Recommender.
 *
 * @param template              The candidate master template
 * @param matchScore            Deterministic suitability score (0-100+)
 * @param recommendationReason  Detailed rationale explaining why this template fits the case
 * @param variantComparisonNote Disambiguation guidance distinguishing this template from close variants
 */
public record TemplateRecommendation(
        TemplateManifest template,
        int matchScore,
        String recommendationReason,
        String variantComparisonNote
) {
    /**
     * Backward-compatible accessor for the recommended template manifest.
     */
    public TemplateManifest manifest() {
        return template;
    }
}
