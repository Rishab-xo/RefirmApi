package com.app.refirm.triage;

import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.EntityRole;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.FinancialRole;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.PartyEntity;
import com.app.refirm.triage.domain.RelationshipType;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.domain.StatuteType;
import com.app.refirm.triage.pipeline.impl.OpenAiBriefGenerator;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiBriefGeneratorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private OpenAiBriefGenerator briefGenerator;

    @BeforeEach
    void setUp() {
        briefGenerator = new OpenAiBriefGenerator(chatClient);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if context is null")
    void throwsExceptionOnNullContext() {
        assertThatThrownBy(() -> briefGenerator.generateDraft(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DraftingContext cannot be null");
    }

    @Test
    @DisplayName("Generates empathetic Markdown brief with correctly interpolated XML payload")
    void generatesEmpatheticMarkdownBrief() {
        PartyEntity claimant = new PartyEntity("Rahul Sharma", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        PartyEntity oppositeParty = new PartyEntity("AutoFix Workshop", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                claimant,
                null,
                List.of(oppositeParty),
                List.of("Defective repair engine seized"),
                85000.0
        );

        ResolvedForum forum = new ResolvedForum(
                "District Consumer Disputes Redressal Commission, Bengaluru",
                "e-Daakhil",
                "Pecuniary jurisdiction <= Rs. 50 Lakhs"
        );

        List<StatutePin> pins = List.of(
                new StatutePin("Consumer Protection Act, 2019", "Section 85", StatuteType.OPERATIVE_LIABILITY, "Product Service Provider", "Operative liability")
        );

        DraftingContext context = new DraftingContext(
                "My vehicle repair was completely defective.",
                facts,
                pins,
                forum,
                List.of("Subject to repair receipts"),
                24,
                false,
                "Section 63 certificate"
        );

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("""
                ```markdown
                ## CASE VALIDATION & DE-ESCALATION
                I understand the immense anxiety and danger caused by the catastrophic brake failure on the highway.
                
                ## STRATEGIC MERITS, STATUTES & PRECEDENTS
                * **Accountable Parties & Joint Liability:** AutoFix Workshop
                * **Procedural Standing (Locus Standi):** Rahul Sharma holds sole standing.
                * **Statutory Grounds & Specific Breaches:** Section 85
                * **Statutory Limitation Notice:** 24 Months
                * **Judicial Precedents & Pleading Traps:** Anchor under SC doctrine.
                
                ## CONCRETE FINANCIAL STRATEGY
                * **Actual Financial Loss:** Rs. 85,000
                
                ## FORENSIC EVIDENCE PROTOCOL
                * **Digital Artifacts:** Section 63 BSA certificate.
                
                ## EXECUTABLE ACTION PLAN & ARTIFACTS
                1. **Evidence Lockdown:** Lock evidence.
                2. **Dispute / Banking Protocol:** Dispute script.
                3. **Statutory Legal Notice:** Send legal notice.
                4. **Regulatory / Judicial Escalation:** File before District Commission via e-Daakhil.
                ```
                """);

        String result = briefGenerator.generateDraft(context);

        assertThat(result).isNotNull();
        assertThat(result).doesNotStartWith("```");
        assertThat(result).doesNotEndWith("```");
        assertThat(result).contains("## CASE VALIDATION & DE-ESCALATION");
        assertThat(result).contains("## STRATEGIC MERITS, STATUTES & PRECEDENTS");
        assertThat(result).contains("AutoFix Workshop");
        assertThat(result).contains("## CONCRETE FINANCIAL STRATEGY");
        assertThat(result).contains("## EXECUTABLE ACTION PLAN & ARTIFACTS");
        assertThat(result).contains("## ASSUMPTIONS MADE");
        assertThat(result).contains("Subject to repair receipts");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(promptCaptor.capture());

        Prompt capturedPrompt = promptCaptor.getValue();
        UserMessage userMsg = (UserMessage) capturedPrompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElseThrow();

        String payload = userMsg.getText();
        assertThat(payload).contains("<user_narrative>");
        assertThat(payload).contains("<claimant>");
        assertThat(payload).contains("Rahul Sharma");
        assertThat(payload).contains("<opposite_parties>");
        assertThat(payload).contains("AutoFix Workshop");
        assertThat(payload).contains("<statute_pack>");
        assertThat(payload).contains("Section 85");
        assertThat(payload).contains("<target_forum>");
        assertThat(payload).contains("District Consumer Disputes Redressal Commission, Bengaluru");
        assertThat(payload).contains("e-Daakhil");
        assertThat(payload).contains("<statutory_invariants>");
        assertThat(payload).contains("24 Months");
        assertThat(payload).contains("<assumption_caveats>");
    }
}
