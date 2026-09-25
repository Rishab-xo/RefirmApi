package com.app.refirm.triage.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Structured fact-coverage tracker returned by the AI on every intake turn.
 * <p>
 * The backend uses {@code coveragePercent} as a validation gate —
 * the AI cannot mark {@code isComplete = true} until coverage >= 80%.
 * The frontend renders this as a live progress bar in the sidebar.
 */
public record FactChecklist(

        /** Key-value map of facts gathered so far, e.g. {"nature_of_offence": "Theft", "fir_filed": "Yes"} */
        Map<String, String> gatheredFacts,

        /** List of required fact keys still missing, e.g. ["date_of_incident", "accused_details"] */
        List<String> missingRequiredFacts,

        /** Percentage of required facts gathered (0-100). Backend enforces >= 80 for completion. */
        int coveragePercent

) {

    @JsonCreator
    public static FactChecklist create(
            @JsonProperty("gatheredFacts") Map<String, String> gatheredFacts,
            @JsonProperty("missingRequiredFacts") List<String> missingRequiredFacts,
            @JsonProperty("coveragePercent") Object coveragePercent
    ) {
        int parsedCoverage = 0;
        if (coveragePercent instanceof Number n) {
            parsedCoverage = n.intValue();
        } else if (coveragePercent instanceof String s) {
            String clean = s.replaceAll("[^0-9]", "").trim();
            if (!clean.isEmpty()) {
                try {
                    parsedCoverage = Integer.parseInt(clean);
                } catch (NumberFormatException ignored) {}
            }
        }

        return new FactChecklist(
                gatheredFacts != null ? gatheredFacts : Map.of(),
                missingRequiredFacts != null ? missingRequiredFacts : List.of(),
                Math.max(0, Math.min(100, parsedCoverage))
        );
    }

    /**
     * Creates an empty checklist (used as a safe fallback when the AI fails).
     */
    public static FactChecklist empty() {
        return new FactChecklist(Map.of(), List.of(), 0);
    }
}
