package com.app.refirm.rules.evaluation;

import com.app.refirm.rules.model.PecuniaryJurisdiction;
import com.app.refirm.rules.model.PhysicalSizeExemption;
import com.app.refirm.rules.model.StatuteRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Statute Rule Evaluation Engine Tests")
class StatuteRuleEvaluatorTest {

    private PecuniarySpecification pecuniarySpecification;
    private PhysicalSizeSpecification physicalSizeSpecification;
    private StatuteRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        pecuniarySpecification = new PecuniarySpecification();
        physicalSizeSpecification = new PhysicalSizeSpecification();
        evaluator = new StatuteRuleEvaluator(List.of(pecuniarySpecification, physicalSizeSpecification));
    }

    private StatuteRule createMockRule(String name, PecuniaryJurisdiction pecuniary) {
        return new StatuteRule(
                name,
                "All India",
                null,
                pecuniary,
                null,
                null,
                List.of()
        );
    }

    @Nested
    @DisplayName("PecuniarySpecification Tests")
    class PecuniarySpecificationTests {

        @Test
        @DisplayName("Returns APPLICABLE when statute has no pecuniary limits")
        void shouldReturnApplicableWhenNoLimits() {
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(false, null, null, null, null, null);
            StatuteRule rule = createMockRule("Test Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(null, "Delhi", "Individual", null);

            EvaluationDecision decision = pecuniarySpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.APPLICABLE, decision.status());
            assertTrue(decision.caveats().isEmpty());
        }

        @Test
        @DisplayName("Returns UNVERIFIED_APPLICABLE with caveat when limit exists but disputeAmount is null")
        void shouldReturnUnverifiedWhenAmountMissing() {
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 100000L, 2000000L, null, null, null);
            StatuteRule rule = createMockRule("Consumer Protection Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(null, "Delhi", "Business", null);

            EvaluationDecision decision = pecuniarySpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.UNVERIFIED_APPLICABLE, decision.status());
            assertEquals(1, decision.caveats().size());
            assertTrue(decision.caveats().getFirst().contains("missing exact financial dispute amount"));
        }

        @Test
        @DisplayName("Returns EXCLUDED when dispute amount is below minimum threshold")
        void shouldReturnExcludedWhenBelowMinimum() {
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 500000L, 5000000L, null, null, null);
            StatuteRule rule = createMockRule("Commercial Courts Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(200000L, "Mumbai", "Company", null);

            EvaluationDecision decision = pecuniarySpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.EXCLUDED, decision.status());
            assertTrue(decision.rationale().contains("below the statute's minimum"));
        }

        @Test
        @DisplayName("Returns EXCLUDED when dispute amount exceeds maximum threshold")
        void shouldReturnExcludedWhenExceedsMaximum() {
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 0L, 2000000L, null, null, null);
            StatuteRule rule = createMockRule("District Consumer Forum", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(2500000L, "Bengaluru", "Individual", null);

            EvaluationDecision decision = pecuniarySpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.EXCLUDED, decision.status());
            assertTrue(decision.rationale().contains("exceeds the statute's pecuniary ceiling"));
        }

        @Test
        @DisplayName("Returns APPLICABLE when dispute amount is within thresholds")
        void shouldReturnApplicableWhenWithinThresholds() {
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 100000L, 2000000L, null, null, null);
            StatuteRule rule = createMockRule("District Consumer Forum", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(500000L, "Bengaluru", "Individual", null);

            EvaluationDecision decision = pecuniarySpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.APPLICABLE, decision.status());
            assertTrue(decision.caveats().isEmpty());
        }
    }

    @Nested
    @DisplayName("PhysicalSizeSpecification Tests")
    class PhysicalSizeSpecificationTests {

        @Test
        @DisplayName("Returns APPLICABLE when no physical size exemption exists")
        void shouldReturnApplicableWhenNoSizeExemption() {
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(null, null, null, null, null, null);
            StatuteRule rule = createMockRule("General Rent Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(null, null, null, 100);

            EvaluationDecision decision = physicalSizeSpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.APPLICABLE, decision.status());
        }

        @Test
        @DisplayName("Returns UNVERIFIED_APPLICABLE when size limit exists but property size is null")
        void shouldReturnUnverifiedWhenPropertySizeMissing() {
            PhysicalSizeExemption pse = new PhysicalSizeExemption(500, 2);
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(false, null, null, null, null, pse);
            StatuteRule rule = createMockRule("Tenancy Protection Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(50000L, "Chennai", "Tenant", null);

            EvaluationDecision decision = physicalSizeSpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.UNVERIFIED_APPLICABLE, decision.status());
            assertEquals(1, decision.caveats().size());
            assertTrue(decision.caveats().getFirst().contains("missing property size"));
        }

        @Test
        @DisplayName("Returns EXCLUDED when property area is within exemption boundary (<= ceiling)")
        void shouldReturnExcludedWhenPropertyWithinExemption() {
            PhysicalSizeExemption pse = new PhysicalSizeExemption(500, 2);
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(false, null, null, null, null, pse);
            StatuteRule rule = createMockRule("Tenancy Protection Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(50000L, "Chennai", "Tenant", 450);

            EvaluationDecision decision = physicalSizeSpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.EXCLUDED, decision.status());
            assertTrue(decision.rationale().contains("within the statute's physical size exemption boundary"));
        }

        @Test
        @DisplayName("Returns APPLICABLE when property area exceeds exemption ceiling")
        void shouldReturnApplicableWhenPropertyExceedsExemption() {
            PhysicalSizeExemption pse = new PhysicalSizeExemption(500, 2);
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(false, null, null, null, null, pse);
            StatuteRule rule = createMockRule("Tenancy Protection Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(50000L, "Chennai", "Tenant", 800);

            EvaluationDecision decision = physicalSizeSpecification.evaluate(rule, facts);

            assertEquals(RuleEvaluationStatus.APPLICABLE, decision.status());
        }
    }

    @Nested
    @DisplayName("StatuteRuleEvaluator Orchestrator Tests")
    class OrchestratorTests {

        @Test
        @DisplayName("Short-circuits immediately to EXCLUDED if any specification fails")
        void shouldShortCircuitOnExcluded() {
            // Pecuniary will exclude (amount 3M > 2M max), Physical size would have unverified
            PhysicalSizeExemption pse = new PhysicalSizeExemption(500, null);
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 0L, 2000000L, null, null, pse);
            StatuteRule rule = createMockRule("Strict Tribunal Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(3000000L, "Delhi", null, null);

            EvaluationDecision decision = evaluator.evaluateStatute(rule, facts);

            assertEquals(RuleEvaluationStatus.EXCLUDED, decision.status());
        }

        @Test
        @DisplayName("Combines caveats when multiple specifications are UNVERIFIED")
        void shouldCombineCaveatsWhenMultipleUnverified() {
            PhysicalSizeExemption pse = new PhysicalSizeExemption(500, null);
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 0L, 2000000L, null, null, pse);
            StatuteRule rule = createMockRule("Multi-Check Act", pj);
            // Neither disputeAmount nor propertySize provided
            ExtractedFactsDTO facts = new ExtractedFactsDTO(null, "Delhi", null, null);

            EvaluationDecision decision = evaluator.evaluateStatute(rule, facts);

            assertEquals(RuleEvaluationStatus.UNVERIFIED_APPLICABLE, decision.status());
            assertEquals(2, decision.caveats().size());
        }

        @Test
        @DisplayName("Returns APPLICABLE when all specifications succeed without caveats")
        void shouldReturnApplicableWhenAllPass() {
            PhysicalSizeExemption pse = new PhysicalSizeExemption(500, null);
            PecuniaryJurisdiction pj = new PecuniaryJurisdiction(true, 0L, 2000000L, null, null, pse);
            StatuteRule rule = createMockRule("Passing Act", pj);
            ExtractedFactsDTO facts = new ExtractedFactsDTO(1000000L, "Delhi", null, 750);

            EvaluationDecision decision = evaluator.evaluateStatute(rule, facts);

            assertEquals(RuleEvaluationStatus.APPLICABLE, decision.status());
            assertTrue(decision.caveats().isEmpty());
        }

        @Test
        @DisplayName("Handles null facts safely by returning UNVERIFIED_APPLICABLE with global caveat")
        void shouldHandleNullFactsSafely() {
            StatuteRule rule = createMockRule("Passing Act", null);
            EvaluationDecision decision = evaluator.evaluateStatute(rule, null);

            assertEquals(RuleEvaluationStatus.UNVERIFIED_APPLICABLE, decision.status());
            assertFalse(decision.caveats().isEmpty());
        }

        @Test
        @DisplayName("Handles null rule safely by returning EXCLUDED")
        void shouldHandleNullRuleSafely() {
            ExtractedFactsDTO facts = new ExtractedFactsDTO(100L, "Delhi", null, 100);
            EvaluationDecision decision = evaluator.evaluateStatute(null, facts);

            assertEquals(RuleEvaluationStatus.EXCLUDED, decision.status());
        }
    }
}
