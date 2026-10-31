package com.app.refirm.rules.evaluation;

import com.app.refirm.rules.model.StatuteRule;

/**
 * Strategy interface for a single, atomic statute evaluation rule.
 * <p>
 * Each implementation encapsulates exactly one evaluation concern
 * (pecuniary limits, geographic scope, physical size exemptions, etc.)
 * and returns a tri-state {@link EvaluationDecision}.
 *
 * <h3>Design Contract</h3>
 * <ul>
 *   <li>Implementations MUST be stateless. All input comes through the method parameters.</li>
 *   <li>Implementations MUST be null-safe: a null or absent field in {@code rule} or
 *       {@code facts} must never produce an unchecked {@code NullPointerException}.</li>
 *   <li>Implementations MUST return {@code UNVERIFIED_APPLICABLE} — never {@code EXCLUDED} —
 *       when a required fact is absent. Absence of evidence is not evidence of exclusion.</li>
 *   <li>Implementations annotated with {@code @Component} will be auto-discovered and
 *       injected as {@code List<RuleSpecification>} into {@link StatuteRuleEvaluator}.</li>
 * </ul>
 *
 * <h3>Adding a New Specification</h3>
 * Create a Spring {@code @Component} implementing this interface. Spring will automatically
 * include it in the evaluator's specification list — no wiring changes required.
 */
public interface RuleSpecification {

    /**
     * Evaluates one aspect of a statute's applicability against the provided facts.
     *
     * @param rule  the statute rule definition loaded from the rules engine JSON; never null
     * @param facts the extracted client facts from the triage intake; may contain null fields
     * @return a non-null {@link EvaluationDecision} representing the tri-state verdict
     */
    EvaluationDecision evaluate(StatuteRule rule, ExtractedFactsDTO facts);
}
