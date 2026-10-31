package com.app.refirm.triage;

import com.app.refirm.triage.domain.EntityRole;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.FinancialRole;
import com.app.refirm.triage.domain.PartyEntity;
import com.app.refirm.triage.domain.RelationshipType;
import com.app.refirm.triage.pipeline.impl.OpenAiFactExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OpenAiFactExtractorIntegrationTest {

    @Autowired
    private OpenAiFactExtractor factExtractor;

    @Test
    @DisplayName("Pass 1: Entity-capacity mapping extracts split entities and parses pecuniary values")
    void shouldExtractSplitEntitiesAndFinancialRoles() {
        String narrative = "I bought a flat from Apex Builders, but my father paid the 10 Lakh booking amount from his SBI account.";

        ExtractedFacts facts = factExtractor.extract(narrative);

        assertThat(facts).isNotNull();

        // 1. Assert that parties contains two distinct entities (or more, including the builder)
        assertThat(facts.parties()).hasSizeGreaterThanOrEqualTo(2);

        // 2. Assert that the father is classified correctly as PAYMENT_INSTRUMENT_HOLDER and PAID_CONSIDERATION
        PartyEntity father = facts.parties().stream()
                .filter(p -> p.relationship() == RelationshipType.PAYMENT_INSTRUMENT_HOLDER
                        || p.financialRole() == FinancialRole.PAID_CONSIDERATION
                        || (p.name() != null && p.name().toLowerCase().contains("father")))
                .findFirst()
                .orElse(null);

        assertThat(father).isNotNull();
        assertThat(father.relationship()).isEqualTo(RelationshipType.PAYMENT_INSTRUMENT_HOLDER);
        assertThat(father.financialRole()).isEqualTo(FinancialRole.PAID_CONSIDERATION);

        // 3. Assert that estimatedFinancialLoss is parsed strictly as 1000000.0
        assertThat(facts.estimatedFinancialLoss()).isEqualTo(1000000.0);
    }
}
