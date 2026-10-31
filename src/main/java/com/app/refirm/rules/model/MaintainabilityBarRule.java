package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Universal Maintainability & Statutory Bar Rule loaded from master_rules_engine.json.
 * Applicable dynamically across all 13 domains and cross-cutting statutes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MaintainabilityBarRule(
        String ruleId,
        @JsonAlias("act_slug") String actName,
        List<String> sectionIds,
        List<String> triggerKeywords,
        DateWindowTrigger dateWindowTrigger,
        String barWarningDirective
) {
    public MaintainabilityBarRule {
        if (sectionIds == null) sectionIds = List.of();
        if (triggerKeywords == null) triggerKeywords = List.of();
    }

    public String actSlug() {
        return actName;
    }

    /**
     * Resolves the effective bar warning directive, preferring the date-window
     * directive if present, otherwise falling back to the rule-level directive.
     */
    public String resolveEffectiveDirective() {
        if (dateWindowTrigger != null && dateWindowTrigger.barWarningDirective() != null && !dateWindowTrigger.barWarningDirective().isBlank()) {
            return dateWindowTrigger.barWarningDirective();
        }
        return barWarningDirective != null ? barWarningDirective : "";
    }
}
