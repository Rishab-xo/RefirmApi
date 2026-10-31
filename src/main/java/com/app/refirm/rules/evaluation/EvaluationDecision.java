package com.app.refirm.rules.evaluation;

import java.util.List;

/**
 * Immutable output of a single statute evaluation — the unit of truth returned
 * by every {@link RuleSpecification} and the {@link StatuteRuleEvaluator}.
 * <p>
 * Use the static factory methods to construct instances; avoid the canonical
 * constructor in application code to preserve intent clarity.
 *
 * @param statuteName the name of the statute being evaluated
 * @param status      the tri-state verdict (see {@link RuleEvaluationStatus})
 * @param caveats     ordered list of assumption notices for {@code UNVERIFIED_APPLICABLE}
 *                    outcomes; always empty for {@code APPLICABLE} and {@code EXCLUDED}
 * @param rationale   a human-readable explanation of why this decision was reached,
 *                    surfaced in logs and LLM drafting context
 */
public record EvaluationDecision(
        String statuteName,
        RuleEvaluationStatus status,
        List<String> caveats,
        String rationale
) {

    /**
     * Compact constructor — defensively copies the caveats list to guarantee immutability.
     * An {@code EvaluationDecision} instance can never have its caveats list mutated
     * after construction, regardless of what the caller does with the original list.
     */
    public EvaluationDecision {
        caveats = (caveats != null) ? List.copyOf(caveats) : List.of();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Static Factory Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a fully confirmed {@code APPLICABLE} decision with no caveats.
     * Use when all specification checks pass deterministically.
     *
     * @param statuteName the statute confirmed as applicable
     * @param rationale   explanation of why the statute applies
     * @return an immutable {@code APPLICABLE} decision
     */
    public static EvaluationDecision applicable(String statuteName, String rationale) {
        return new EvaluationDecision(
                statuteName,
                RuleEvaluationStatus.APPLICABLE,
                List.of(),
                rationale
        );
    }

    /**
     * Creates a conclusive {@code EXCLUDED} decision with no caveats.
     * Use when a fact conclusively disqualifies the statute (e.g., amount ceiling breach).
     *
     * @param statuteName the statute being excluded
     * @param rationale   the concrete reason for exclusion (e.g., "dispute amount 2,500,000 exceeds ceiling 2,000,000")
     * @return an immutable {@code EXCLUDED} decision
     */
    public static EvaluationDecision excluded(String statuteName, String rationale) {
        return new EvaluationDecision(
                statuteName,
                RuleEvaluationStatus.EXCLUDED,
                List.of(),
                rationale
        );
    }

    /**
     * Creates a fail-open {@code UNVERIFIED_APPLICABLE} decision with a single caveat.
     * Use when a required fact is missing and a deterministic verdict cannot be reached.
     * The statute is still forwarded to the drafting LLM but is tagged with the caveat
     * so the model can acknowledge the assumption rather than stating it as fact.
     *
     * @param statuteName the statute that is provisionally included
     * @param caveat      a specific, actionable notice about the missing fact
     *                    (e.g., "Assumed applicable; missing exact financial dispute amount to verify pecuniary limits.")
     * @param rationale   the broader reason for the unverified state
     * @return an immutable {@code UNVERIFIED_APPLICABLE} decision
     */
    public static EvaluationDecision unverified(String statuteName, String caveat, String rationale) {
        return new EvaluationDecision(
                statuteName,
                RuleEvaluationStatus.UNVERIFIED_APPLICABLE,
                List.of(caveat),
                rationale
        );
    }
}
