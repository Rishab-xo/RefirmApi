package com.app.refirm.rules.evaluation;

import com.app.refirm.rules.model.PecuniaryJurisdiction;
import com.app.refirm.rules.model.PhysicalSizeExemption;
import com.app.refirm.rules.model.StatuteRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Specification: Evaluates whether the subject property falls within a physical-size
 * exemption defined in the statute's pecuniary jurisdiction block.
 *
 * <h3>Semantics of Physical Size Exemptions</h3>
 * Some statutes (notably tenancy and rent control acts) exempt small properties from
 * their jurisdiction. A property whose area is <em>at or below</em>
 * {@code maximumLandAreaSqMeters} falls within the exemption and is therefore
 * <strong>outside</strong> the statute's reach — producing an {@code EXCLUDED} decision.
 *
 * <h3>Evaluation Logic</h3>
 * <ol>
 *   <li>If no {@code physicalSizeExemptions} block exists → {@code APPLICABLE}:
 *       no size-based exemption defined.</li>
 *   <li>If {@code maximumLandAreaSqMeters} is defined but {@code facts.propertySizeSqMeters()}
 *       is {@code null} → {@code UNVERIFIED_APPLICABLE} (fail-open with caveat).</li>
 *   <li>If {@code propertySizeSqMeters <= maximumLandAreaSqMeters}
 *       → {@code EXCLUDED}: property is within the exemption boundary.</li>
 *   <li>Otherwise → {@code APPLICABLE}: property exceeds the exemption threshold
 *       and falls under the statute's jurisdiction.</li>
 * </ol>
 */
@Component
public class PhysicalSizeSpecification implements RuleSpecification {

    private static final Logger log = LoggerFactory.getLogger(PhysicalSizeSpecification.class);

    private static final String CAVEAT_MISSING_SIZE =
            "Assumed applicable; missing property size (sq. meters) to verify physical size exemption.";

    @Override
    public EvaluationDecision evaluate(StatuteRule rule, ExtractedFactsDTO facts) {

        String name = resolveStatuteName(rule);

        // ── Guard: no pecuniary jurisdiction block ─────────────────────────────
        PecuniaryJurisdiction jurisdiction = rule.pecuniaryJurisdiction();
        if (jurisdiction == null) {
            log.debug("[PhysicalSizeSpec] '{}': no pecuniaryJurisdiction block → APPLICABLE.", name);
            return EvaluationDecision.applicable(name,
                    "No pecuniary jurisdiction block defined; physical size exemption not applicable.");
        }

        // ── Guard: no physical size exemption defined ──────────────────────────
        PhysicalSizeExemption exemption = jurisdiction.physicalSizeExemptions();
        if (exemption == null) {
            log.debug("[PhysicalSizeSpec] '{}': no physicalSizeExemptions defined → APPLICABLE.", name);
            return EvaluationDecision.applicable(name,
                    "No physical size exemption defined in this statute.");
        }

        Integer maxExemptArea = exemption.maximumLandAreaSqMeters();

        // ── Guard: exemption block exists but specifies no area ceiling ────────
        if (maxExemptArea == null) {
            log.debug("[PhysicalSizeSpec] '{}': physicalSizeExemptions present but maximumLandAreaSqMeters is null → APPLICABLE.", name);
            return EvaluationDecision.applicable(name,
                    "Physical size exemption block is present but defines no land area ceiling.");
        }

        // ── Tri-State: area limit defined but property size not provided ───────
        Integer propertySizeSqMeters = facts.propertySizeSqMeters();
        if (propertySizeSqMeters == null) {
            log.debug("[PhysicalSizeSpec] '{}': area ceiling {}m² defined but propertySizeSqMeters is null → UNVERIFIED_APPLICABLE.", name, maxExemptArea);
            return EvaluationDecision.unverified(name,
                    CAVEAT_MISSING_SIZE,
                    "Statute defines a physical size exemption (threshold: " + maxExemptArea
                    + " m²) but property area was not provided in the facts.");
        }

        // ── Hard exclusion: property is within the exemption boundary ──────────
        if (propertySizeSqMeters <= maxExemptArea) {
            log.debug("[PhysicalSizeSpec] '{}': property {}m² ≤ exemption ceiling {}m² → EXCLUDED.",
                    name, propertySizeSqMeters, maxExemptArea);
            return EvaluationDecision.excluded(name,
                    "Property size (" + propertySizeSqMeters + " m²) is within the statute's physical "
                    + "size exemption boundary (" + maxExemptArea + " m²). The statute does not apply "
                    + "to properties of this size.");
        }

        // ── Pass: property exceeds exemption threshold — statute applies ───────
        log.debug("[PhysicalSizeSpec] '{}': property {}m² > exemption ceiling {}m² → APPLICABLE.",
                name, propertySizeSqMeters, maxExemptArea);
        return EvaluationDecision.applicable(name,
                "Property size (" + propertySizeSqMeters + " m²) exceeds the exemption ceiling ("
                + maxExemptArea + " m²). The statute's jurisdiction applies.");
    }

    /**
     * Null-safe helper to resolve a displayable statute name.
     */
    private static String resolveStatuteName(StatuteRule rule) {
        return (rule != null && rule.statuteName() != null && !rule.statuteName().isBlank())
                ? rule.statuteName()
                : "Unknown Statute";
    }
}
