package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.pipeline.FactExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiFactExtractor implements FactExtractor {

    private final ChatClient chatClient;

    @Autowired
    public OpenAiFactExtractor(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-6-luna")
                        .temperature(1.0) // Overrides Spring AI's 0.7 default
                        .build())
                .build();
    }

    public OpenAiFactExtractor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public ExtractedFacts extract(String userNarrative) {
        BeanOutputConverter<ExtractedFacts> converter = new BeanOutputConverter<>(ExtractedFacts.class);

        String promptText = """
            You are an elite Legal Ontology Extraction Engine. Analyze the user's dispute narrative and extract the facts strictly into the provided JSON schema.
            
            CRITICAL EXTRACTION RULES:
            0. 'domain': Classify the dispute into one of these EXACT strings: BANKING, CIVIL_PROPERTY, CONSTITUTIONAL, CONSUMER, CORPORATE, CRIMINAL, CYBER, EMPLOYMENT, ENVIRONMENTAL, FAMILY, IP, REAL_ESTATE, TAXATION, TENANCY. For real estate developer delays, builder-buyer agreements, or flat/apartment possession issues, ALWAYS classify as REAL_ESTATE.
            1. 'claimant': The direct aggrieved consumer/victim (relationship: PRIMARY_CLAIMANT).
            2. 'paymentInstrumentHolder': The person who paid if different from the claimant (e.g., friend/parent). Output null if the claimant paid directly.
            3. 'oppositeParties': The entities responsible for the breach (e.g., Platform Aggregator, Franchisee, Merchant).
            4. Strict Enums: Map 'role', 'relationship', and 'financialRole' strictly using the exact Enum values provided in the schema.
            5. 'estimatedFinancialLoss': MUST be a pure Double (e.g., 36776.0). DO NOT use strings or commas. Always convert monetary amounts including booking amounts, paid consideration, or transaction values into numeric values (e.g., 10 Lakh = 1000000.0, 1 Crore = 10000000.0).
            
            LEGAL TAXONOMY TRANSLATION ('factualAnomalies'):
            This array is fed directly into a strict statutory Bare Act search engine. 
            - DO NOT extract raw story keywords (e.g., "broken clutch", "instagram deleted", "mechanic lied").
            - RULE A (Governing Act): The first element (index 0) MUST be the formal title of the primary governing Indian Act based on the narrative (e.g., "The Consumer Protection Act, 2019", "The Negotiable Instruments Act, 1881", "The Information Technology Act, 2000", "The Transfer of Property Act, 1882", "The Real Estate (Regulation and Development) Act, 2016").
            - RULE B (Canonical Causes of Action): The remaining elements (index 1..N) MUST be selected ONLY from the applicable canonical keys for the classified domain from this Master Domain Taxonomy:
              CONSUMER: DEFICIENCY_IN_SERVICE, UNFAIR_TRADE_PRACTICE, DEFECTIVE_GOODS, UNFAIR_CONTRACT, MISLEADING_ADVERTISEMENT, OVERCHARGING_EXCESS_PRICE, HAZARDOUS_GOODS_OR_SERVICES, PRODUCT_LIABILITY_ACTION
              BANKING: UNAUTHORIZED_ELECTRONIC_TRANSACTION, WRONGFUL_DEBIT_OR_CHARGEBACK, UNFAIR_RECOVERY_HARASSMENT, CHEQUE_DISHONOUR_SECTION_138, ILLEGAL_ACCOUNT_FREEZE_OR_LIEN, SARFAESI_ENFORCEMENT_DISPUTE
              TENANCY: ILLEGAL_EVICTION_OR_LOCKOUT, SECURITY_DEPOSIT_WITHHOLDING, UNLAWFUL_RENT_ENHANCEMENT, ESSENTIAL_SUPPLY_DISCONNECTION, BREACH_OF_TENANCY_AGREEMENT
              EMPLOYMENT: WRONGFUL_TERMINATION_OR_RETRENCHMENT, UNPAID_WAGES_OR_SALARY_WITHHOLDING, GRATUITY_OR_STATUTORY_BENEFIT_DENIAL, UNFAIR_LABOUR_PRACTICE
              CIVIL_PROPERTY: BREACH_OF_CONTRACT_DAMAGES, SPECIFIC_PERFORMANCE_OF_CONTRACT, TITLE_OR_POSSESSION_DISPUTE, FRAUDULENT_TRANSFER_OF_PROPERTY
              CYBER: UNAUTHORIZED_ACCESS_OR_DATA_THEFT, IDENTITY_THEFT_AND_IMPERSONATION, CYBER_FRAUD_AND_CHEATING, PRIVACY_OR_DATA_PROTECTION_BREACH
              CRIMINAL: CHEATING_AND_FRAUD, CRIMINAL_BREACH_OF_TRUST, EXTORTION_AND_CRIMINAL_INTIMIDATION, FORGERY_OF_DOCUMENTS
              CORPORATE: OPPRESSION_AND_MISMANAGEMENT, INSOLVENCY_DEFAULT_CIRP, BREACH_OF_FIDUCIARY_DIRECTOR_DUTY
              FAMILY: DOMESTIC_VIOLENCE_PROTECTION, MAINTENANCE_AND_ALIMONY, DISSOLUTION_OF_MARRIAGE_DIVORCE, CHILD_CUSTODY_AND_GUARDIANSHIP
              IP: TRADEMARK_INFRINGEMENT_AND_PASSING_OFF, COPYRIGHT_INFRINGEMENT, PATENT_INFRINGEMENT
              TAXATION: ILLEGAL_TAX_DEMAND_OR_ASSESSMENT, INPUT_TAX_CREDIT_DENIAL, WRONGFUL_PENALTY_OR_SEIZURE
              ENVIRONMENTAL: ENVIRONMENTAL_POLLUTION_DAMAGES, HAZARDOUS_SUBSTANCE_HANDLING_BREACH
              CONSTITUTIONAL: VIOLATION_OF_FUNDAMENTAL_RIGHTS, ARBITRARY_STATE_OR_ADMINISTRATIVE_ACTION
            - STRICT PRODUCT LIABILITY BOUNDARY: NEVER output PRODUCT_LIABILITY_ACTION for negligent repairs, service deficiencies, or damage/defect confined to the serviced product/vehicle itself (as Section 2(22) excludes damage to the product itself). Output PRODUCT_LIABILITY_ACTION ONLY if a defective product causes physical personal injury/death or physical damage to other distinct property.
            
            NO MARKDOWN: Output raw, valid JSON only. Do not wrap the output in ```json blocks.
            
            Narrative: {narrative}
            
            {format}
            """;

        PromptTemplate template = new PromptTemplate(promptText);
        template.add("narrative", userNarrative != null ? userNarrative : "");
        template.add("format", converter.getFormat());

        String rawResponse = chatClient.prompt(template.create()).call().content();
        if (rawResponse == null || rawResponse.isBlank()) {
            return new ExtractedFacts(null, null, null, List.of(), List.of(), 0.0);
        }
        
        // Defensively strip markdown fences to prevent Jackson JsonParseExceptions
        String cleanJson = rawResponse.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "").trim();

        ExtractedFacts facts = converter.convert(cleanJson);
        if (facts != null && (facts.estimatedFinancialLoss() == null || facts.estimatedFinancialLoss() == 0.0)) {
            Double parsedLoss = parseAmountFromNarrative(userNarrative);
            if (parsedLoss != null && parsedLoss > 0.0) {
                return new ExtractedFacts(
                        facts.domain(),
                        facts.claimant(),
                        facts.paymentInstrumentHolder(),
                        facts.oppositeParties(),
                        facts.factualAnomalies(),
                        parsedLoss
                );
            }
        }
        return facts;
    }

    private Double parseAmountFromNarrative(String narrative) {
        if (narrative == null || narrative.isBlank()) return null;
        Matcher lakhMatcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:lakh|lac|lacs|lakhs)\\b").matcher(narrative);
        if (lakhMatcher.find()) {
            try {
                return Double.parseDouble(lakhMatcher.group(1)) * 100000.0;
            } catch (Exception ignored) {}
        }
        Matcher croreMatcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:crore|crores|cr)\\b").matcher(narrative);
        if (croreMatcher.find()) {
            try {
                return Double.parseDouble(croreMatcher.group(1)) * 10000000.0;
            } catch (Exception ignored) {}
        }
        Matcher rsMatcher = Pattern.compile("(?i)(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.\\d+)?)").matcher(narrative);
        if (rsMatcher.find()) {
            try {
                return Double.parseDouble(rsMatcher.group(1).replace(",", ""));
            } catch (Exception ignored) {}
        }
        return null;
    }
}
