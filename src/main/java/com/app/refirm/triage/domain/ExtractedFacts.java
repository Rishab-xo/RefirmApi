package com.app.refirm.triage.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedFacts(
        LegalDomain domain,
        PartyEntity claimant,
        PartyEntity paymentInstrumentHolder,
        List<PartyEntity> oppositeParties,
        List<String> factualAnomalies,
        Double estimatedFinancialLoss
) {
    // Canonical constructor with defensive null-checks for Jackson deserialization
    public ExtractedFacts {
        oppositeParties = oppositeParties != null ? List.copyOf(oppositeParties) : Collections.emptyList();
        factualAnomalies = factualAnomalies != null ? List.copyOf(factualAnomalies) : Collections.emptyList();
        estimatedFinancialLoss = estimatedFinancialLoss != null ? estimatedFinancialLoss : 0.0;
    }

    // BACKWARD-COMPATIBLE CONSTRUCTOR: Prevents existing unit tests from breaking
    public ExtractedFacts(LegalDomain domain, List<PartyEntity> parties, List<String> factualAnomalies, Double estimatedFinancialLoss) {
        this(
                domain,
                extractClaimant(parties),
                extractPaymentHolder(parties),
                extractOppositeParties(parties),
                factualAnomalies,
                estimatedFinancialLoss
        );
    }

    @JsonCreator
    public static ExtractedFacts create(
            @JsonProperty("domain") LegalDomain domain,
            @JsonProperty("claimant") PartyEntity claimant,
            @JsonProperty("paymentInstrumentHolder") PartyEntity paymentInstrumentHolder,
            @JsonProperty("oppositeParties") List<PartyEntity> oppositeParties,
            @JsonProperty("parties") List<PartyEntity> parties,
            @JsonProperty("factualAnomalies") List<String> factualAnomalies,
            @JsonProperty("estimatedFinancialLoss") Double estimatedFinancialLoss
    ) {
        if (parties != null && !parties.isEmpty()) {
            if (claimant == null) claimant = extractClaimant(parties);
            if (paymentInstrumentHolder == null) paymentInstrumentHolder = extractPaymentHolder(parties);
            if (oppositeParties == null || oppositeParties.isEmpty()) oppositeParties = extractOppositeParties(parties);
        }
        return new ExtractedFacts(domain, claimant, paymentInstrumentHolder, oppositeParties, factualAnomalies, estimatedFinancialLoss);
    }

    // BACKWARD-COMPATIBLE METHOD: Keeps legacy pipeline iterations functional
    public List<PartyEntity> parties() {
        List<PartyEntity> all = new ArrayList<>();
        if (claimant != null) all.add(claimant);
        if (paymentInstrumentHolder != null) all.add(paymentInstrumentHolder);
        all.addAll(oppositeParties);
        return Collections.unmodifiableList(all);
    }

    private static PartyEntity extractClaimant(List<PartyEntity> parties) {
        if (parties == null) return null;
        return parties.stream()
                .filter(p -> p.relationship() == RelationshipType.PRIMARY_CLAIMANT)
                .findFirst()
                .orElse(parties.isEmpty() ? null : parties.get(0));
    }

    private static PartyEntity extractPaymentHolder(List<PartyEntity> parties) {
        if (parties == null) return null;
        return parties.stream()
                .filter(p -> p.relationship() == RelationshipType.PAYMENT_INSTRUMENT_HOLDER)
                .findFirst()
                .orElse(null);
    }

    private static List<PartyEntity> extractOppositeParties(List<PartyEntity> parties) {
        if (parties == null) return Collections.emptyList();
        return parties.stream()
                .filter(p -> p.relationship() == RelationshipType.OPPOSING_ENTITY || p.role() == EntityRole.OPPOSITE_PARTY || p.role() == EntityRole.PLATFORM_AGGREGATOR)
                .toList();
    }
}
