package com.app.refirm.triage;

import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.EntityRole;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.domain.StatuteType;
import com.app.refirm.triage.pipeline.FactExtractor;
import com.app.refirm.triage.pipeline.ForumResolver;
import com.app.refirm.triage.pipeline.StatuteRetriever;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.pipeline.impl.OpenAiBriefGenerator;
import com.app.refirm.triage.pipeline.impl.TriagePipelineOrchestratorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriagePipelineOrchestratorIntegrationTest {

    @Mock
    private FactExtractor factExtractor;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private TriagePipelineOrchestratorImpl orchestrator;

    private static final String VEHICLE_REPAIR_NARRATIVE = """
            I booked a major brake repair and overhaul service for my sedan via an online aggregator app (AutoCare Express)
            paying Rs. 85,000 using my brother's credit card. The authorized on-ground service center installed substandard,
            counterfeit brake pads that completely failed on the highway at 90 km/h, resulting in an emergency collision
            and personal endangerment. AutoCare deleted my negative reviews and claimed they are merely a technology platform
            exempt from liability. The garage refuses to take accountability.
            """;

    private static final String MOCK_MODEL_OUTPUT = """
            ## CASE VALIDATION & DE-ESCALATION
            * Grounded empathy for the severe life-threatening brake failure and bad-faith response.
            * The claim is actionable and viable under the Consumer Protection Act, 2019.

            ## STRATEGIC MERITS, STATUTES & PRECEDENTS
            * **Accountable Parties & Joint Liability:** Both AutoCare Express (Digital Aggregator) and the Service Center are jointly liable. Section 2(42) strips aggregator immunity for active digital facilities.
            * **Procedural Standing (Locus Standi):** Complainant has full locus standi under Section 2(7)(ii) as the designated beneficiary of services paid via brother's card.
            * **Statutory Grounds & Specific Breaches:**
              - Deficiency/Breach: [CPA 2019 Section 2(11)(i)/(ii)] (Deficiency in Service) — Delivery of counterfeit, substandard brake pads causing brake failure and withholding diagnostic information.
              - Deceptive/Unfair Trade Practice: [CPA 2019 Section 2(47)] (Unfair Trade Practice) — Bad-faith suppression and deletion of truthful customer reviews on the platform.
              - Operative Hazardous Liability: [CPA 2019 Section 85] (Liability of Product Service Provider) — Liability for supplying dangerous and defective service resulting in highway collision and endangerment.
            * **Judicial Precedents & Pleading Traps:** Anchor claim in Charan Singh v. Healing Touch Hospital. Comply with the General Motors doctrine pleading punitive damages under a distinct prayer.

            ## CONCRETE FINANCIAL STRATEGY
            * **Actual Financial Loss:** Rs. 85,000 (service fee) plus Rs. 1,45,000 (vehicle collision repairs).
            * **Mental Agony & Exemplary Brackets:**
              * **Conservative Bracket:** Rs. 50,000 to Rs. 1,00,000 for distress and emergency hardship.
              * **Deterrent Bracket:** Rs. 3,00,000 to Rs. 5,00,000 for gross safety negligence and suppression of consumer grievances.

            ## FORENSIC EVIDENCE PROTOCOL
            * **Digital Artifacts:** Preserve app booking invoices, transaction receipts, and chat logs. Secure Section 63 certificate under Bharatiya Sakshya Adhiniyam, 2023.
            * **Physical & Expert Verification:** Securely preserve failed brake pads. Mandate independent diagnostic inspection report from an authorized automotive engineer.

            ## EXECUTABLE ACTION PLAN & ARTIFACTS
            1. **Evidence Lockdown:** Secure failed brake pads and obtain Section 63 certificate for all digital interactions.
            2. **Dispute / Banking Protocol:** Notify the credit card issuing bank immediately: "I wish to dispute the transaction under Defective Services / Breach of Contract due to catastrophic service failure and hazardous defects. The merchant provided counterfeit parts endangering life, and this dispute is initiated under merchant breach rather than voluntary cancellation." Do not claim the charge was unauthorized.
            3. **Statutory Legal Notice:** Dispatch a 15-day formal legal notice to both AutoCare Express and the on-ground service center detailing Section 85 product service liability.
            4. **Regulatory / Judicial Escalation:** If unaddressed within 15 days, file a formal consumer complaint before the District Consumer Disputes Redressal Commission, Bengaluru via e-Daakhil.
            """;

    @BeforeEach
    void setUp() {
        List<ForumResolver> resolvers = List.of(new ForumResolver() {
            @Override
            public boolean supports(LegalDomain domain) {
                return LegalDomain.CONSUMER_RIGHTS.equals(domain);
            }

            @Override
            public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
                return new ResolvedForum(
                        "District Consumer Disputes Redressal Commission, Bengaluru",
                        "e-Daakhil",
                        "Pecuniary claim <= 50 Lakhs under CPA 2019"
                );
            }
        });

        StatuteRetriever statuteRetriever = facts -> new StatuteRetriever.StatuteRetrievalResult(List.of(
                new StatutePin("Consumer Protection Act, 2019", "Section 2(7)(ii)", StatuteType.STANDING_DEFINITION, "Defines consumer beneficiary", "Includes any beneficiary of services..."),
                new StatutePin("Consumer Protection Act, 2019", "Section 2(42)", StatuteType.STANDING_DEFINITION, "Defines service inclusion for platforms", "Service includes facilities provided through electronic means..."),
                new StatutePin("Consumer Protection Act, 2019", "Section 2(11)(i)/(ii)", StatuteType.SUBSTANTIVE_BREACH, "Defines deficiency in service", "Any fault, imperfection, shortcoming or inadequacy..."),
                new StatutePin("Consumer Protection Act, 2019", "Section 2(47)", StatuteType.SUBSTANTIVE_BREACH, "Defines unfair trade practices", "Any unfair method or deceptive practice..."),
                new StatutePin("Consumer Protection Act, 2019", "Section 85", StatuteType.OPERATIVE_LIABILITY, "Product service provider liability", "A product service provider shall be liable...")
        ), List.of());

        OpenAiBriefGenerator briefGenerator = new OpenAiBriefGenerator(chatClient);

        orchestrator = new TriagePipelineOrchestratorImpl(
                factExtractor,
                resolvers,
                statuteRetriever,
                briefGenerator
        );
    }

    @Test
    @DisplayName("End-to-End Pipeline Integration Test validating all 4 acceptance criteria")
    void executesEndToEndPipelineAndMeetsAcceptanceCriteria() {
        // Step 1: Mock extraction
        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                List.of(
                        new com.app.refirm.triage.domain.PartyEntity("AutoCare Express", EntityRole.PLATFORM_AGGREGATOR, com.app.refirm.triage.domain.RelationshipType.OPPOSING_ENTITY, com.app.refirm.triage.domain.FinancialRole.NONE),
                        new com.app.refirm.triage.domain.PartyEntity("Service Center", EntityRole.OPPOSITE_PARTY, com.app.refirm.triage.domain.RelationshipType.OPPOSING_ENTITY, com.app.refirm.triage.domain.FinancialRole.NONE)
                ),
                List.of("Defective brake installation", "Platform review suppression", "Highway accident hazard"),
                85000.0
        );

        when(factExtractor.extract(VEHICLE_REPAIR_NARRATIVE)).thenReturn(facts);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(MOCK_MODEL_OUTPUT);

        // Execute Pipeline
        String finalBrief = orchestrator.processCase(VEHICLE_REPAIR_NARRATIVE, "Bengaluru");

        // Verify Prompt Context Interpolation
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(promptCaptor.capture());
        Prompt sentPrompt = promptCaptor.getValue();
        UserMessage userMessage = (UserMessage) sentPrompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElseThrow();
        String promptPayload = userMessage.getText();

        // Check target forum passed into prompt
        assertThat(promptPayload).contains("District Consumer Disputes Redressal Commission, Bengaluru");
        assertThat(promptPayload).contains("e-Daakhil");
        // Check Section 85 in statute pack
        assertThat(promptPayload).contains("Section 85");
        assertThat(promptPayload).contains("OPERATIVE_LIABILITY");

        // ══════════════════════════════════════════════════════════════
        // ACCEPTANCE CRITERIA 1: Step 4 mentions ONLY District Commission via e-Daakhil
        // (Zero mentions of RERA or RBI Ombudsman)
        // ══════════════════════════════════════════════════════════════
        assertThat(finalBrief).contains("District Consumer Disputes Redressal Commission");
        assertThat(finalBrief).contains("e-Daakhil");
        assertThat(finalBrief).doesNotContain("RERA");
        assertThat(finalBrief).doesNotContain("RBI Ombudsman");
        assertThat(finalBrief).doesNotContain("NCLT");

        // ══════════════════════════════════════════════════════════════
        // ACCEPTANCE CRITERIA 2: Step 2 contains verbatim banking script without
        // creating sub-numbering items 3, 4, or 5 in the Action Plan
        // ══════════════════════════════════════════════════════════════
        assertThat(finalBrief).contains("Dispute / Banking Protocol");
        assertThat(finalBrief).contains("I wish to dispute the transaction under Defective Services / Breach of Contract");

        // Find Action Plan section and extract numbered lines
        String actionPlanSection = finalBrief.substring(finalBrief.indexOf("## EXECUTABLE ACTION PLAN & ARTIFACTS"));
        Matcher numberedMatcher = Pattern.compile("^\\s*([0-9]+)\\.", Pattern.MULTILINE).matcher(actionPlanSection);
        List<Integer> numberedSteps = numberedMatcher.results()
                .map(mr -> Integer.parseInt(mr.group(1)))
                .toList();

        assertThat(numberedSteps).containsExactly(1, 2, 3, 4);

        // ══════════════════════════════════════════════════════════════
        // ACCEPTANCE CRITERIA 3: Section II uses atomic Triad Schema format:
        // [Section] (Standard) — [Breach], properly citing Section 85 for hazardous liability
        // ══════════════════════════════════════════════════════════════
        assertThat(finalBrief).contains("Section 85");
        assertThat(finalBrief).contains("[CPA 2019 Section 85]");
        Pattern triadPattern = Pattern.compile("\\[.*?\\]\\s*\\(.*?\\)\\s*—\\s*.*?");
        assertThat(triadPattern.matcher(finalBrief).find()).isTrue();
        assertThat(finalBrief).doesNotContain("Section 2(22)"); // No pure harm definitions

        // ══════════════════════════════════════════════════════════════
        // ACCEPTANCE CRITERIA 4: Final Brief is non-empty and well-formed
        // ══════════════════════════════════════════════════════════════
        assertThat(finalBrief).isNotBlank();
        assertThat(finalBrief).doesNotStartWith("```");
        assertThat(finalBrief).doesNotEndWith("```");
    }
}
