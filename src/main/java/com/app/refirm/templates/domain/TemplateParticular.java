package com.app.refirm.templates.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Specification for a scalar factual particular within a template skeleton.
 *
 * @param key              Unique identifier used in {{FACT:key}} tokens
 * @param label            Human-readable label displayed in UI particular editors
 * @param required         Whether this particular is mandatory for filing
 * @param ledgerSourceKey  Deterministic mapping hint from workspace state
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateParticular(
        String key,
        String label,
        boolean required,
        String ledgerSourceKey
) {
    /**
     * Backward-compatible 5-parameter constructor accepting description.
     */
    public TemplateParticular(String key, String label, String description, boolean required, String ledgerSourceKey) {
        this(key, label, required, ledgerSourceKey);
    }
}
