package com.app.refirm.triage;

import com.app.refirm.triage.domain.EntityRole;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.FinancialRole;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.PartyEntity;
import com.app.refirm.triage.domain.RelationshipType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedFactsAndBriefDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("ExtractedFacts preserves backward compatibility with legacy constructor and parties list")
    void testExtractedFactsBackwardCompatibility() {
        PartyEntity claimant = new PartyEntity("Priya", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.INVOICE_RECIPIENT);
        PartyEntity payer = new PartyEntity("Amit", EntityRole.CONSUMER, RelationshipType.PAYMENT_INSTRUMENT_HOLDER, FinancialRole.PAID_CONSIDERATION);
        PartyEntity op = new PartyEntity("Flipkart", EntityRole.PLATFORM_AGGREGATOR, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                List.of(claimant, payer, op),
                List.of("Defective mobile phone"),
                45000.0
        );

        assertThat(facts.claimant()).isEqualTo(claimant);
        assertThat(facts.paymentInstrumentHolder()).isEqualTo(payer);
        assertThat(facts.oppositeParties()).containsExactly(op);
        assertThat(facts.parties()).hasSize(3);
    }

    @Test
    @DisplayName("ExtractedFacts deserializes legacy JSON containing parties array")
    void testExtractedFactsLegacyJsonDeserialization() throws Exception {
        String legacyJson = """
                {
                  "domain": "CONSUMER_RIGHTS",
                  "parties": [
                    {
                      "name": "Priya",
                      "role": "CONSUMER",
                      "relationship": "PRIMARY_CLAIMANT",
                      "financialRole": "NONE"
                    },
                    {
                      "name": "Flipkart",
                      "role": "PLATFORM_AGGREGATOR",
                      "relationship": "OPPOSING_ENTITY",
                      "financialRole": "PROMOTER_OR_MERCHANT"
                    }
                  ],
                  "factualAnomalies": ["Wrong item delivered"],
                  "estimatedFinancialLoss": 35000.0
                }
                """;

        ExtractedFacts facts = objectMapper.readValue(legacyJson, ExtractedFacts.class);
        assertThat(facts.claimant()).isNotNull();
        assertThat(facts.claimant().name()).isEqualTo("Priya");
        assertThat(facts.oppositeParties()).hasSize(1);
        assertThat(facts.oppositeParties().get(0).name()).isEqualTo("Flipkart");
        assertThat(facts.parties()).hasSize(2);
    }
}
