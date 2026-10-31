package com.app.refirm.triage;

import com.app.refirm.triage.domain.EntityRole;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.FinancialRole;
import com.app.refirm.triage.domain.PartyEntity;
import com.app.refirm.triage.domain.RelationshipType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiFactExtractorTest {

    @Test
    @DisplayName("BeanOutputConverter deserializes split entities, relationships, and financial roles")
    void convertsExtractedFactsJsonWithSplitEntities() {
        BeanOutputConverter<ExtractedFacts> converter = new BeanOutputConverter<>(ExtractedFacts.class);

        String sampleJson = """
            {
              "domain": "REAL_ESTATE",
              "parties": [
                {
                  "name": "Buyer",
                  "role": "ALLOTTEE",
                  "relationship": "PRIMARY_CLAIMANT",
                  "financialRole": "INVOICE_RECIPIENT"
                },
                {
                  "name": "Father",
                  "role": "CONSUMER",
                  "relationship": "PAYMENT_INSTRUMENT_HOLDER",
                  "financialRole": "PAID_CONSIDERATION"
                },
                {
                  "name": "Apex Builders",
                  "role": "OPPOSITE_PARTY",
                  "relationship": "OPPOSING_ENTITY",
                  "financialRole": "PROMOTER_OR_MERCHANT"
                }
              ],
              "factualAnomalies": [
                "10 Lakh booking amount paid from father's SBI account"
              ],
              "estimatedFinancialLoss": 1000000.0
            }
            """;

        ExtractedFacts facts = converter.convert(sampleJson);

        assertThat(facts).isNotNull();
        assertThat(facts.parties()).hasSize(3);

        PartyEntity father = facts.parties().stream()
                .filter(p -> "Father".equalsIgnoreCase(p.name()))
                .findFirst()
                .orElse(null);

        assertThat(father).isNotNull();
        assertThat(father.relationship()).isEqualTo(RelationshipType.PAYMENT_INSTRUMENT_HOLDER);
        assertThat(father.financialRole()).isEqualTo(FinancialRole.PAID_CONSIDERATION);
        assertThat(facts.estimatedFinancialLoss()).isEqualTo(1000000.0);
    }

    @Test
    @DisplayName("FinancialRole handles PAYED_CONSIDERATION spelling alias gracefully")
    void handlesPayedSpellingAlias() {
        BeanOutputConverter<ExtractedFacts> converter = new BeanOutputConverter<>(ExtractedFacts.class);
        String json = """
            {
              "parties": [
                {
                  "name": "Father",
                  "relationship": "PAYMENT_INSTRUMENT_HOLDER",
                  "financialRole": "PAYED_CONSIDERATION"
                }
              ]
            }
            """;
        ExtractedFacts facts = converter.convert(json);
        assertThat(facts.parties().getFirst().financialRole()).isEqualTo(FinancialRole.PAID_CONSIDERATION);
    }
}
