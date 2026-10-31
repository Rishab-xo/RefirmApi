package com.app.refirm.workspace;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.*;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.service.FactReconcilerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FactReconcilerServiceTest {

    private FactReconcilerService reconcilerService;

    @BeforeEach
    void setUp() {
        reconcilerService = new FactReconcilerService();
    }

    @Test
    @DisplayName("Initial reconciliation creates v1 ledger with unlocked fields")
    void shouldInitializeV1Ledger() {
        PartyEntity claimant = new PartyEntity("Rahul Sharma", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        PartyEntity merchant = new PartyEntity("AutoFix Workshop", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);
        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                claimant,
                null,
                List.of(merchant),
                List.of("Defective brake overhaul"),
                85000.0
        );

        ExtractedFactsDTO ruleFacts = new ExtractedFactsDTO(
                com.app.refirm.rules.model.LegalDomain.CONSUMER,
                85000L,
                "Bengaluru",
                "AutoFix Workshop",
                null
        );

        CaseFactLedger ledger = reconcilerService.reconcile(null, facts, ruleFacts);

        assertThat(ledger).isNotNull();
        assertThat(ledger.version()).isEqualTo(1);
        assertThat(ledger.extractedFacts().claimant().name()).isEqualTo("Rahul Sharma");
        assertThat(ledger.extractedFacts().estimatedFinancialLoss()).isEqualTo(85000.0);
        assertThat(ledger.ruleEvaluationFacts().location()).isEqualTo("Bengaluru");
        assertThat(ledger.lawyerLockedFields()).isEmpty();
        assertThat(ledger.isLocked("claimant")).isFalse();
    }

    @Test
    @DisplayName("Reconciliation updates unlocked fields and increments version to v2")
    void shouldUpdateUnlockedFields() {
        PartyEntity claimant1 = new PartyEntity("Rahul Sharma", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        ExtractedFacts facts1 = new ExtractedFacts(LegalDomain.CONSUMER_RIGHTS, claimant1, null, List.of(), List.of(), 50000.0);
        ExtractedFactsDTO ruleFacts1 = new ExtractedFactsDTO(null, 50000L, "Bengaluru", null, null);

        CaseFactLedger v1 = reconcilerService.reconcile(null, facts1, ruleFacts1);

        // Turn 2 re-extraction with updated loss and opposite party
        PartyEntity merchant = new PartyEntity("AutoCare Platform", EntityRole.PLATFORM_AGGREGATOR, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);
        ExtractedFacts facts2 = new ExtractedFacts(LegalDomain.CONSUMER_RIGHTS, claimant1, null, List.of(merchant), List.of(), 85000.0);
        ExtractedFactsDTO ruleFacts2 = new ExtractedFactsDTO(null, 85000L, "Bengaluru", "AutoCare Platform", null);

        CaseFactLedger v2 = reconcilerService.reconcile(v1, facts2, ruleFacts2);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.extractedFacts().estimatedFinancialLoss()).isEqualTo(85000.0);
        assertThat(v2.extractedFacts().oppositeParties()).hasSize(1);
        assertThat(v2.extractedFacts().oppositeParties().get(0).name()).isEqualTo("AutoCare Platform");
        assertThat(v2.ruleEvaluationFacts().disputeAmount()).isEqualTo(85000L);
    }

    @Test
    @DisplayName("Reconciliation strictly preserves lawyer-locked fields against AI re-extraction overwrite")
    void shouldStrictlyPreserveLawyerLockedFields() {
        // Initial setup
        PartyEntity originalClaimant = new PartyEntity("Priya Verma", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        PartyEntity originalMerchant = new PartyEntity("Original Garage Ltd", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);
        ExtractedFacts facts1 = new ExtractedFacts(LegalDomain.CONSUMER_RIGHTS, originalClaimant, null, List.of(originalMerchant), List.of(), 95000.0);
        ExtractedFactsDTO ruleFacts1 = new ExtractedFactsDTO(null, 95000L, "Gurgaon", "Original Garage Ltd", null);

        CaseFactLedger v1 = reconcilerService.reconcile(null, facts1, ruleFacts1);

        // Advocate manually locks "claimant" and "disputeAmount"
        CaseFactLedger lockedLedger = reconcilerService.applyLawyerOverride(
                v1,
                v1.extractedFacts(),
                v1.ruleEvaluationFacts(),
                Set.of("claimant", "disputeAmount")
        );

        assertThat(lockedLedger.isLocked("claimant")).isTrue();
        assertThat(lockedLedger.isLocked("disputeAmount")).isTrue();
        assertThat(lockedLedger.isLocked("oppositeParties")).isFalse();

        // Automated AI re-extraction attempts to overwrite claimant and dispute amount with wrong data
        PartyEntity hallucinatedClaimant = new PartyEntity("Wrong Claimant Person", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        PartyEntity newOppositeParty = new PartyEntity("New Verified Platform", EntityRole.PLATFORM_AGGREGATOR, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);
        ExtractedFacts aiNewFacts = new ExtractedFacts(LegalDomain.CONSUMER_RIGHTS, hallucinatedClaimant, null, List.of(newOppositeParty), List.of(), 10000.0);
        ExtractedFactsDTO aiNewRuleFacts = new ExtractedFactsDTO(null, 10000L, "Noida", "New Verified Platform", null);

        CaseFactLedger v3 = reconcilerService.reconcile(lockedLedger, aiNewFacts, aiNewRuleFacts);

        // Claimant and Dispute Amount MUST be preserved from locked state
        assertThat(v3.extractedFacts().claimant().name()).isEqualTo("Priya Verma");
        assertThat(v3.extractedFacts().estimatedFinancialLoss()).isEqualTo(95000.0);
        assertThat(v3.ruleEvaluationFacts().disputeAmount()).isEqualTo(95000L);

        // Unlocked fields (oppositeParties, location) ARE updated
        assertThat(v3.extractedFacts().oppositeParties().get(0).name()).isEqualTo("New Verified Platform");
        assertThat(v3.ruleEvaluationFacts().location()).isEqualTo("Noida");
    }
}
