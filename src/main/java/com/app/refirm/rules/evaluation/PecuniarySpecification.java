package com.app.refirm.rules.evaluation;

import com.app.refirm.rules.model.PecuniaryJurisdiction;
import com.app.refirm.rules.model.StatuteRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Specification: Evaluates whether the client's dispute amount falls within
 * the statute's pecuniary jurisdiction limits.
 *
 * <h3>Evaluation Logic</h3>
 * <ol>
 *   <li>If the statute defines no pecuniary jurisdiction, or {@code hasLimit} is null/false
 *       → {@code APPLICABLE}: no monetary restriction exists.</li>
 *   <li>If {@code hasLimit} is {@code true} but {@code facts.disputeAmount()} is {@code null}
 *       → {@code UNVERIFIED_APPLICABLE}: the user did not provide the amount; we fail-open
 *       with a caveat rather than silently excluding a potentially valid statute.</li>
 *   <li>If {@code disputeAmount < minimumThreshold} (and a minimum is defined)
 *       → {@code EXCLUDED}: dispute is below the tribunal's minimum cognizance floor.</li>
 *   <li>If {@code disputeAmount > maximumThreshold} (and a maximum is defined)
 *       → {@code EXCLUDED}: dispute exceeds the tribunal's pecuniary ceiling.</li>
 *   <li>Otherwise → {@code APPLICABLE}: amount is within range.</li>
 * </ol>
 */
@Component
public class PecuniarySpecification implements RuleSpecification {

    private static final Logger log = LoggerFactory.getLogger(PecuniarySpecification.class);

    private static final String CAVEAT_MISSING_AMOUNT =
            "Assumed applicable; missing exact financial dispute amount to verify pecuniary limits.";

    @Override
    public EvaluationDecision evaluate(StatuteRule rule, ExtractedFactsDTO facts) {

        String name = resolveStatuteName(rule);

        // ── Guard: no pecuniary jurisdiction defined at all ──────────────────
        PecuniaryJurisdiction jurisdiction = rule.pecuniaryJurisdiction();
        if (jurisdiction == null) {
            log.debug("[PecuniarySpec] '{}': no pecuniaryJurisdiction defined → APPLICABLE.", name);
            return EvaluationDecision.applicable(name,
                    "No pecuniary jurisdiction block defined in the statute rule.");
        }

        // ── Guard: statute has no pecuniary limit ────────────────────────────
        if (!Boolean.TRUE.equals(jurisdiction.hasLimit())) {
            log.debug("[PecuniarySpec] '{}': hasLimit is false/null → APPLICABLE.", name);
            return EvaluationDecision.applicable(name,
                    "Statute has no pecuniary cap (hasLimit is false or absent).");
        }

        // ── Tri-State: hasLimit is true but amount not provided ───────────────
        Long disputeAmount = facts.disputeAmount();
        if (disputeAmount == null) {
            log.debug("[PecuniarySpec] '{}': hasLimit=true but disputeAmount is null → UNVERIFIED_APPLICABLE.", name);
            return EvaluationDecision.unverified(name,
                    CAVEAT_MISSING_AMOUNT,
                    "Statute defines a pecuniary limit but no dispute amount was provided in the facts.");
        }

        // ── Hard exclusion: amount below minimum threshold ────────────────────
        Double minThreshold = jurisdiction.minimumThreshold();
        if (minThreshold != null && disputeAmount < minThreshold) {
            log.debug("[PecuniarySpec] '{}': disputeAmount {} < minThreshold {} → EXCLUDED.",
                    name, disputeAmount, minThreshold);
            String minStr = (minThreshold % 1 == 0) ? String.valueOf(minThreshold.longValue()) : String.valueOf(minThreshold);
            return EvaluationDecision.excluded(name,
                    "Dispute amount (" + disputeAmount + " INR) is below the statute's minimum "
                    + "cognizance threshold (" + minStr + " INR).");
        }

        // ── Hard exclusion: amount above maximum threshold ────────────────────
        Double maxThreshold = jurisdiction.maximumThreshold();
        if (maxThreshold != null && disputeAmount > maxThreshold) {
            log.debug("[PecuniarySpec] '{}': disputeAmount {} > maxThreshold {} → EXCLUDED.",
                    name, disputeAmount, maxThreshold);
            String maxStr = (maxThreshold % 1 == 0) ? String.valueOf(maxThreshold.longValue()) : String.valueOf(maxThreshold);
            return EvaluationDecision.excluded(name,
                    "Dispute amount (" + disputeAmount + " INR) exceeds the statute's pecuniary "
                    + "ceiling (" + maxStr + " INR).");
        }

        // ── Pass: amount is within bounds ─────────────────────────────────────
        log.debug("[PecuniarySpec] '{}': disputeAmount {} is within pecuniary bounds → APPLICABLE.", name, disputeAmount);
        return EvaluationDecision.applicable(name,
                "Dispute amount (" + disputeAmount + " INR) is within the statute's pecuniary jurisdiction.");
    }

    /**
     * Null-safe helper to resolve a displayable statute name for log messages and decisions.
     */
    private static String resolveStatuteName(StatuteRule rule) {
        return (rule != null && rule.statuteName() != null && !rule.statuteName().isBlank())
                ? rule.statuteName()
                : "Unknown Statute";
    }
}
