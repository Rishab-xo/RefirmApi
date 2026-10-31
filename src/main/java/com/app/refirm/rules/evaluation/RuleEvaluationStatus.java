package com.app.refirm.rules.evaluation;

/**
 * Tri-State evaluation outcome for a single statute against a given set of facts.
 * <p>
 * This engine deliberately avoids binary {@code true/false} outcomes to prevent
 * the "silent false negative" failure mode, where a statute is wrongly excluded
 * simply because the user did not supply a specific fact.
 *
 * <h3>State Semantics</h3>
 * <ul>
 *   <li>{@link #APPLICABLE} — All verifiable conditions confirm the statute applies.
 *       The LLM can cite this statute with full confidence.</li>
 *   <li>{@link #EXCLUDED} — At least one condition conclusively disqualifies the statute
 *       (e.g., dispute amount mathematically exceeds the tribunal's pecuniary ceiling).
 *       The LLM must not cite this statute.</li>
 *   <li>{@link #UNVERIFIED_APPLICABLE} — No condition has positively excluded the statute,
 *       but one or more facts required for a deterministic verdict are missing.
 *       The statute is included in the output under a caveat flag so the drafting
 *       LLM can acknowledge the assumption rather than silently assert it.</li>
 * </ul>
 */
public enum RuleEvaluationStatus {

    /**
     * All specification checks passed deterministically.
     * The statute is confirmed applicable to the provided facts.
     */
    APPLICABLE,

    /**
     * At least one specification check has positively excluded the statute.
     * The statute must not be cited in any legal output.
     */
    EXCLUDED,

    /**
     * No specification has excluded the statute, but at least one check could not
     * be completed due to a missing fact. The statute is included with an
     * accompanying caveat list for LLM consumption.
     */
    UNVERIFIED_APPLICABLE
}
