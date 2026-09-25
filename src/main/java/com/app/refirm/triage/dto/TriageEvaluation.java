package com.app.refirm.triage.dto;

import com.app.refirm.triage.entities.LegalCategory;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured output contract for the AI intake evaluation.
 * <p>
 * Supports robust Jackson deserialization with explicit @JsonCreator,
 * null-safety defaults, and resilient type mapping.
 */
public record TriageEvaluation(

        /** True when all material facts (Who, What, When, Where) have been gathered. */
        boolean isComplete,

        /** The next plain-language question to ask — null when isComplete is true. */
        String nextQuestion,

        /** Classified legal issue category constrained to the {@link LegalCategory} enum. */
        LegalCategory issueCategory,

        /** True if the situation involves immediate safety or irreversible harm. */
        boolean isEmergency,

        /** Running list of distilled facts extracted so far. */
        List<String> extractedFacts,

        /** Structured fact-coverage tracker for the current legal category's required fields. */
        FactChecklist factChecklist

) {

    @JsonCreator
    public static TriageEvaluation create(
            @JsonProperty("isComplete") Boolean isComplete,
            @JsonProperty("nextQuestion") String nextQuestion,
            @JsonProperty("issueCategory") LegalCategory issueCategory,
            @JsonProperty("isEmergency") Boolean isEmergency,
            @JsonProperty("extractedFacts") List<String> extractedFacts,
            @JsonProperty("factChecklist") FactChecklist factChecklist
    ) {
        return new TriageEvaluation(
                Boolean.TRUE.equals(isComplete),
                nextQuestion != null ? nextQuestion : "",
                issueCategory != null ? issueCategory : LegalCategory.NEEDS_HUMAN_TRIAGE,
                Boolean.TRUE.equals(isEmergency),
                extractedFacts != null ? extractedFacts : List.of(),
                factChecklist != null ? factChecklist : FactChecklist.empty()
        );
    }

    /**
     * Backwards-compatible constructor for code that doesn't supply a checklist.
     */
    public TriageEvaluation(
            boolean isComplete,
            String nextQuestion,
            LegalCategory issueCategory,
            boolean isEmergency,
            List<String> extractedFacts
    ) {
        this(isComplete, nextQuestion, issueCategory, isEmergency, extractedFacts, FactChecklist.empty());
    }
}
