        package com.app.refirm.workspace.service;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.domain.CaseFactLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet; 
import java.util.Set;

/**
 * Deterministic service reconciling AI-extracted dispute facts against the canonical CaseFactLedger.
 * Guarantees that advocate-locked fields are strictly protected against automated re-extraction regressions.
 */
@Service
public class FactReconcilerService {

    private static final Logger log = LoggerFactory.getLogger(FactReconcilerService.class);

    /**
     * Reconciles freshly extracted facts into the existing ledger.
     * Increments ledger version and protects all locked fields.
     *
     * @param currentLedger Current state of the workspace fact ledger (may be null)
     * @param newFacts      New structured facts extracted by Pass 1 (gpt-6-luna)
     * @param newRuleFacts  New rule evaluation facts extracted for the specification engine
     * @return Updated, version-incremented CaseFactLedger
     */
    public CaseFactLedger reconcile(CaseFactLedger currentLedger, ExtractedFacts newFacts, ExtractedFactsDTO newRuleFacts) {
        if (currentLedger == null || (currentLedger.extractedFacts() == null && currentLedger.ruleEvaluationFacts() == null)) {
            log.info("Initializing CaseFactLedger v1 with fresh extraction");
            return new CaseFactLedger(
                    1,
                    newFacts,
                    newRuleFacts,
                    currentLedger != null && currentLedger.lawyerLockedFields() != null ? currentLedger.lawyerLockedFields() : Set.of(),
                    Instant.now()
            );
        }

        Set<String> locked = currentLedger.lawyerLockedFields() != null ? currentLedger.lawyerLockedFields() : Set.of();
        int nextVersion = currentLedger.version() + 1;

        ExtractedFacts mergedFacts = mergeExtractedFacts(currentLedger.extractedFacts(), newFacts, locked);
        ExtractedFactsDTO mergedRuleFacts = mergeRuleFacts(currentLedger.ruleEvaluationFacts(), newRuleFacts, locked);

        log.info("Reconciled CaseFactLedger from v{} -> v{} ({} locked fields preserved)",
                currentLedger.version(), nextVersion, locked.size());

        return new CaseFactLedger(nextVersion, mergedFacts, mergedRuleFacts, locked, Instant.now());
    }

    /**
     * Applies manual lawyer fact corrections and locks the specified fields against future AI overwrite.
     *
     * @param currentLedger    Existing ledger state
     * @param updatedFacts     Manually modified structured facts
     * @param updatedRuleFacts Manually modified rule facts
     * @param fieldsToLock     Set of field names to permanently lock
     * @return Version-incremented ledger with updated facts and locked fields
     */
    public CaseFactLedger applyLawyerOverride(
            CaseFactLedger currentLedger,
            ExtractedFacts updatedFacts,
            ExtractedFactsDTO updatedRuleFacts,
            Set<String> fieldsToLock
    ) {
        Set<String> mergedLocks = new HashSet<>();
        if (currentLedger != null && currentLedger.lawyerLockedFields() != null) {
            mergedLocks.addAll(currentLedger.lawyerLockedFields());
        }
        if (fieldsToLock != null) {
            mergedLocks.addAll(fieldsToLock);
        }

        int nextVersion = (currentLedger != null ? currentLedger.version() : 0) + 1;
        log.info("Lawyer override applied. Ledger incremented to v{} with {} locked fields",
                nextVersion, mergedLocks.size());

        return new CaseFactLedger(nextVersion, updatedFacts, updatedRuleFacts, Set.copyOf(mergedLocks), Instant.now());
    }

    private ExtractedFacts mergeExtractedFacts(ExtractedFacts current, ExtractedFacts incoming, Set<String> locked) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        LegalDomain domain = isFieldLocked(locked, "domain")
                ? current.domain()
                : (incoming.domain() != null && incoming.domain() != LegalDomain.UNKNOWN ? incoming.domain() : current.domain());

        var claimant = isFieldLocked(locked, "claimant")
                ? current.claimant()
                : (incoming.claimant() != null ? incoming.claimant() : current.claimant());

        var payer = isFieldLocked(locked, "paymentInstrumentHolder") || isFieldLocked(locked, "payment_instrument_holder")
                ? current.paymentInstrumentHolder()
                : (incoming.paymentInstrumentHolder() != null ? incoming.paymentInstrumentHolder() : current.paymentInstrumentHolder());

        var oppositeParties = isFieldLocked(locked, "oppositeParties") || isFieldLocked(locked, "opposite_parties")
                ? current.oppositeParties()
                : (incoming.oppositeParties() != null && !incoming.oppositeParties().isEmpty() ? incoming.oppositeParties() : current.oppositeParties());

        var anomalies = isFieldLocked(locked, "factualAnomalies")
                ? current.factualAnomalies()
                : (incoming.factualAnomalies() != null && !incoming.factualAnomalies().isEmpty() ? incoming.factualAnomalies() : current.factualAnomalies());

        Double loss = isFieldLocked(locked, "disputeAmount") || isFieldLocked(locked, "estimatedFinancialLoss")
                ? current.estimatedFinancialLoss()
                : (incoming.estimatedFinancialLoss() != null && incoming.estimatedFinancialLoss() > 0.0 ? incoming.estimatedFinancialLoss() : current.estimatedFinancialLoss());

        return new ExtractedFacts(domain, claimant, payer, oppositeParties, anomalies, loss);
    }

    private ExtractedFactsDTO mergeRuleFacts(ExtractedFactsDTO current, ExtractedFactsDTO incoming, Set<String> locked) {
        if (incoming == null) return current;
        if (current == null) return incoming;

        com.app.refirm.rules.model.LegalDomain domain = isFieldLocked(locked, "domain")
                ? current.domain()
                : (incoming.domain() != null && incoming.domain() != com.app.refirm.rules.model.LegalDomain.UNKNOWN ? incoming.domain() : current.domain());

        Long amount = isFieldLocked(locked, "disputeAmount") || isFieldLocked(locked, "estimatedFinancialLoss")
                ? current.disputeAmount()
                : (incoming.disputeAmount() != null ? incoming.disputeAmount() : current.disputeAmount());

        String location = isFieldLocked(locked, "location")
                ? current.location()
                : (incoming.location() != null && !incoming.location().isBlank() ? incoming.location() : current.location());

        String counterparty = isFieldLocked(locked, "counterpartyType") || isFieldLocked(locked, "oppositeParties")
                ? current.counterpartyType()
                : (incoming.counterpartyType() != null && !incoming.counterpartyType().isBlank() ? incoming.counterpartyType() : current.counterpartyType());

        Integer propSize = isFieldLocked(locked, "propertySizeSqMeters")
                ? current.propertySizeSqMeters()
                : (incoming.propertySizeSqMeters() != null ? incoming.propertySizeSqMeters() : current.propertySizeSqMeters());

        return new ExtractedFactsDTO(domain, amount, location, counterparty, propSize);
    }

    private boolean isFieldLocked(Set<String> locked, String fieldName) {
        if (locked == null || locked.isEmpty() || fieldName == null) return false;
        return locked.stream().anyMatch(l -> l.equalsIgnoreCase(fieldName) || l.equalsIgnoreCase(fieldName.replace("_", "")));
    }
}
