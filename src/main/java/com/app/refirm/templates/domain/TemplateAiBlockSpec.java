package com.app.refirm.templates.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Specification for an isolated AI-generated narrative block within a template skeleton.
 *
 * @param blockKey            Unique identifier used in {{AI_BLOCK:blockKey}} tokens
 * @param title               Human-readable title of the narrative section
 * @param draftingInstruction Targeted prompt directive instructing the model on drafting scope and tone
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateAiBlockSpec(
        String blockKey,
        String title,
        String draftingInstruction
) {}
