package com.app.refirm.rules.evaluation;

import com.app.refirm.rules.model.StatuteRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The Strategy Engine Orchestrator — runs all registered {@link RuleSpecification}
 * implementations against a single statute and collapses their individual decisions
 * into one authoritative, tri-state {@link EvaluationDecision}.
 *
 * <h3>Orchestration Algorithm</h3>
 * <ol>
 *   <li><strong>Short-circuit on EXCLUDED:</strong> the moment any specification
 *       returns {@code EXCLUDED}, evaluation halts immediately and the exclusion
 *       decision is returned. Remaining specs are not evaluated (no false hope).</li>
 *   <li><strong>Accumulate UNVERIFIED caveats:</strong> specs returning
 *       {@code UNVERIFIED_APPLICABLE} do not short-circuit; their caveat strings
 *       are collected for inclusion in the final decision.</li>
 *   <li><strong>Final verdict:</strong>
 *       <ul>
 *         <li>If accumulated caveats are non-empty → {@code UNVERIFIED_APPLICABLE}
 *             carrying all caveats, so the drafting LLM knows exactly which assumptions
 *             were made.</li>
 *         <li>If no spec excluded and no caveats → {@code APPLICABLE} — the statute
 *             is fully and deterministically confirmed.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Extensibility</h3>
 * New specifications are discovered automatically by Spring's
 * {@code List<RuleSpecification>} injection — no changes to this class are needed
 * when adding geographic, temporal, or entity-type specifications.
 */
@Service
public class StatuteRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StatuteRuleEvaluator.class);

    private final List<RuleSpecification> specifications;

    /**
     * Constructor injection of all registered {@link RuleSpecification} beans.
     * Spring collects every {@code @Component} implementing {@link RuleSpecification}
     * into an ordered list; the evaluation order follows Spring's default bean ordering
     * (alphabetical by class name unless {@code @Order} / {@code @Primary} is used).
     *
     * @param specifications all registered specification strategy implementations
     */
    public StatuteRuleEvaluator(List<RuleSpecification> specifications) {
        this.specifications = List.copyOf(specifications); // immutable snapshot at startup
    }

    /**
     * Evaluates a single statute against the provided extracted facts using all
     * registered specifications.
     *
     * <h3>Null Guards</h3>
     * <ul>
     *   <li>A null {@code rule} → immediate {@code EXCLUDED} (cannot evaluate an unknown statute).</li>
     *   <li>A null {@code facts} → immediate {@code UNVERIFIED_APPLICABLE} (no facts = all checks
     *       unverifiable, fail-open with a global caveat).</li>
     * </ul>
     *
     * @param rule  the statute rule definition from the registry; should not be null in production
     * @param facts the extracted client facts; may contain null fields
     * @return a single, non-null {@link EvaluationDecision} representing the final verdict
     */
    public EvaluationDecision evaluateStatute(StatuteRule rule, ExtractedFactsDTO facts) {

        // ── Hard guard: no rule to evaluate ───────────────────────────────────
        if (rule == null) {
            log.warn("[Evaluator] evaluateStatute called with null rule. Returning EXCLUDED.");
            return EvaluationDecision.excluded(
                    "UNKNOWN",
                    "Cannot evaluate a null statute rule. This is a programming error."
            );
        }

        String name = (rule.statuteName() != null && !rule.statuteName().isBlank())
                ? rule.statuteName()
                : "Unknown Statute";

        // ── Soft guard: no facts provided — global fail-open ──────────────────
        if (facts == null) {
            log.warn("[Evaluator] '{}': facts DTO is null. Failing open with global caveat.", name);
            return EvaluationDecision.unverified(
                    name,
                    "No facts provided for this evaluation. All specification checks are unverifiable. "
                    + "The statute is assumed applicable pending fact collection.",
                    "Facts DTO was null; all checks skipped and treated as UNVERIFIED_APPLICABLE."
            );
        }

        log.debug("[Evaluator] Starting evaluation of '{}' with {} specification(s).", name, specifications.size());

        // ── Core evaluation loop ───────────────────────────────────────────────
        List<String> accumulatedCaveats = new ArrayList<>();

        for (RuleSpecification spec : specifications) {
            EvaluationDecision decision;

            try {
                decision = spec.evaluate(rule, facts);
            } catch (Exception e) {
                // A broken specification must not corrupt the entire evaluation pipeline.
                // Log, treat as UNVERIFIED (fail-open), and continue.
                String caveat = "Specification [" + spec.getClass().getSimpleName()
                        + "] threw an unexpected exception and could not be evaluated: " + e.getMessage();
                log.error("[Evaluator] '{}': {} — treating as UNVERIFIED_APPLICABLE.", name, caveat, e);
                accumulatedCaveats.add(caveat);
                continue;
            }

            if (decision == null) {
                log.warn("[Evaluator] '{}': specification [{}] returned null. Treating as UNVERIFIED_APPLICABLE.",
                        name, spec.getClass().getSimpleName());
                accumulatedCaveats.add("Specification [" + spec.getClass().getSimpleName()
                        + "] returned a null decision and could not be evaluated.");
                continue;
            }

            switch (decision.status()) {

                case EXCLUDED -> {
                    // Short-circuit: one exclusion is enough — no further specs are run
                    log.debug("[Evaluator] '{}': EXCLUDED by [{}]. Rationale: {}",
                            name, spec.getClass().getSimpleName(), decision.rationale());
                    return decision;
                }

                case UNVERIFIED_APPLICABLE -> {
                    // Accumulate all caveats but keep evaluating remaining specs
                    log.debug("[Evaluator] '{}': UNVERIFIED by [{}]. Caveat(s): {}",
                            name, spec.getClass().getSimpleName(), decision.caveats());
                    accumulatedCaveats.addAll(decision.caveats());
                }

                case APPLICABLE -> {
                    // Spec passed — continue to next specification
                    log.debug("[Evaluator] '{}': APPLICABLE by [{}].",
                            name, spec.getClass().getSimpleName());
                }
            }
        }

        // ── Final verdict ─────────────────────────────────────────────────────
        if (!accumulatedCaveats.isEmpty()) {
            log.debug("[Evaluator] '{}': final verdict UNVERIFIED_APPLICABLE with {} caveat(s).",
                    name, accumulatedCaveats.size());
            return new EvaluationDecision(
                    name,
                    RuleEvaluationStatus.UNVERIFIED_APPLICABLE,
                    List.copyOf(accumulatedCaveats),
                    "Statute is conditionally applicable. One or more checks could not be completed "
                    + "due to missing facts. See caveats for details."
            );
        }

        log.debug("[Evaluator] '{}': final verdict APPLICABLE — all specifications passed.", name);
        return EvaluationDecision.applicable(
                name,
                "All specification checks passed deterministically. Statute is fully confirmed as applicable."
        );
    }

    /**
     * Returns the number of registered specification strategies.
     * Useful for startup diagnostics and health checks.
     *
     * @return count of loaded {@link RuleSpecification} implementations
     */
    public int getSpecificationCount() {
        return specifications.size();
    }
}
