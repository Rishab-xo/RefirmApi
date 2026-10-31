package com.app.refirm.core.service;

import com.app.refirm.rules.evaluation.*;
import com.app.refirm.rules.model.PecuniaryJurisdiction;
import com.app.refirm.rules.model.StatuteRule;
import com.app.refirm.rules.registry.StatuteRegistry;
import com.app.refirm.triage.entities.LegalCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Rules Engine Pipeline Integration & Drafting LLM Wiring Tests")
class LegalRetrievalAndDraftingIntegrationTest {

    private StatuteRegistry statuteRegistry;
    private StatuteRuleEvaluator evaluator;
    private LegalRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        statuteRegistry = mock(StatuteRegistry.class);

        PecuniarySpecification pecuniarySpec = new PecuniarySpecification();
        PhysicalSizeSpecification physicalSizeSpec = new PhysicalSizeSpecification();
        evaluator = new StatuteRuleEvaluator(List.of(pecuniarySpec, physicalSizeSpec));

        retrievalService = new LegalRetrievalService(statuteRegistry, evaluator);

        // Preload fast test fixtures into cache (avoiding 15,000+ disk reads)
        retrievalService.cacheStatuteFiles(LegalCategory.TENANCY, List.of(
                new LegalRetrievalService.StatuteFile(
                        "The_Transfer_of_Property_Act_1882",
                        "section_105.md",
                        "Section 105: Lease defined. A lease of immoveable property is a transfer of a right to enjoy such property.",
                        "section 105 lease defined transfer immoveable property"
                ),
                new LegalRetrievalService.StatuteFile(
                        "The_Delhi_Rent_Control_Act_1958",
                        "section_14.md",
                        "Section 14: Protection of tenant against eviction. No order or decree for recovery of possession shall be made.",
                        "section 14 protection tenant against eviction recovery possession"
                )
        ));
    }

    @Test
    @DisplayName("Gatekeeper excludes breached statute and drops its chunks from retrieval payload")
    void shouldExcludeBreachedStatuteAndDropFromPayload() {
        // Given: The_Transfer_of_Property_Act_1882 has pecuniary limit of 1,000,000 INR
        StatuteRule cappedRule = new StatuteRule(
                "The_Transfer_of_Property_Act_1882",
                "All India",
                null,
                new PecuniaryJurisdiction(true, 0L, 1000000L, null, null, null),
                null,
                null,
                List.of()
        );
        when(statuteRegistry.getRule("The_Transfer_of_Property_Act_1882")).thenReturn(Optional.of(cappedRule));

        // When: Client dispute amount is 5,000,000 INR (exceeds 1,000,000 ceiling)
        ExtractedFactsDTO facts = new ExtractedFactsDTO(5000000L, "Delhi", "Tenant", null);
        LegalRetrievalService.RetrievalResult result = retrievalService.resolveStatutePackWithEvaluation(
                LegalCategory.TENANCY,
                List.of("lease", "transfer", "property"),
                facts
        );

        // Then: The statute is marked as excluded and its markdown chunks are mathematically dropped
        assertTrue(result.excludedStatuteNames().contains("The_Transfer_of_Property_Act_1882"),
                "Statute should be marked as excluded");
        assertFalse(result.includedStatuteNames().contains("The_Transfer_of_Property_Act_1882"),
                "Statute should not be marked as included");
        assertFalse(result.statutePack().contains("Section 105: Lease defined"),
                "Statute pack must not contain text from the excluded statute");
    }

    @Test
    @DisplayName("Gatekeeper retains statute and aggregates caveats when financial fact is missing (Fail-Open)")
    void shouldRetainStatuteAndAggregateCaveatsWhenAmountMissing() {
        // Given: The_Delhi_Rent_Control_Act_1958 has a pecuniary limit
        StatuteRule cappedRule = new StatuteRule(
                "The_Delhi_Rent_Control_Act_1958",
                "Delhi",
                null,
                new PecuniaryJurisdiction(true, 0L, 3500L, null, null, null),
                null,
                null,
                List.of()
        );
        when(statuteRegistry.getRule("The_Delhi_Rent_Control_Act_1958")).thenReturn(Optional.of(cappedRule));

        // When: Facts have null disputeAmount
        ExtractedFactsDTO facts = new ExtractedFactsDTO(null, "Delhi", "Tenant", null);
        LegalRetrievalService.RetrievalResult result = retrievalService.resolveStatutePackWithEvaluation(
                LegalCategory.TENANCY,
                List.of("eviction", "tenant"),
                facts
        );

        // Then: Statute is retained and assumption caveat is captured
        assertTrue(result.includedStatuteNames().contains("The_Delhi_Rent_Control_Act_1958"),
                "Statute should be retained under UNVERIFIED_APPLICABLE");
        assertFalse(result.excludedStatuteNames().contains("The_Delhi_Rent_Control_Act_1958"));
        assertTrue(result.statutePack().contains("Section 14: Protection of tenant against eviction"),
                "Statute pack must contain retained statute chunks");
        assertFalse(result.assumptionCaveats().isEmpty(),
                "Assumption caveats list must not be empty");
        assertTrue(result.assumptionCaveats().stream().anyMatch(c -> c.contains("missing exact financial dispute amount")),
                "Must include caveat regarding missing financial dispute amount");
    }

    @Test
    @DisplayName("BriefGenerationService injects <assumption_caveats> into prompt when caveats are present")
    void shouldInjectAssumptionCaveatsIntoPrompt() {
        // Given: ChatClient mock with fluent request chaining
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("# FORMAL LEGAL BRIEF\n## IV. ASSUMPTIONS MADE\nAssumed jurisdiction.");

        LegalRetrievalService mockRetrieval = mock(LegalRetrievalService.class);
        BriefGenerationService draftingService = new BriefGenerationService(chatClient, mockRetrieval);

        List<String> caveats = List.of(
                "Assumed applicable; missing exact financial dispute amount to verify pecuniary limits."
        );

        // When
        String brief = draftingService.generateLegalBrief(
                "Landlord locked me out without notice.",
                "### Section 14 Delhi Rent Control Act",
                caveats
        );

        // Then
        assertNotNull(brief);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);

        verify(requestSpec).system(systemCaptor.capture());
        verify(requestSpec).user(userCaptor.capture());

        // Verify System Prompt contains the mandatory "Assumptions Made" instruction
        assertTrue(systemCaptor.getValue().toLowerCase().contains("assumptions made"),
                "System prompt must instruct LLM to include 'Assumptions Made' section");
        assertTrue(systemCaptor.getValue().contains("<assumption_caveats>"),
                "System prompt must reference <assumption_caveats>");

        // Verify User Prompt contains the formatted <assumption_caveats> XML block
        assertTrue(userCaptor.getValue().contains("<assumption_caveats>"),
                "User prompt must contain <assumption_caveats> tag");
        assertTrue(userCaptor.getValue().contains("missing exact financial dispute amount"),
                "User prompt must contain the specific caveat text");
    }

    @Test
    @DisplayName("BriefGenerationService omits <assumption_caveats> when caveat list is empty")
    void shouldOmitAssumptionCaveatsWhenEmpty() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("# FORMAL LEGAL BRIEF\n## IV. PRAYER");

        LegalRetrievalService mockRetrieval = mock(LegalRetrievalService.class);
        BriefGenerationService draftingService = new BriefGenerationService(chatClient, mockRetrieval);

        // When: Empty caveats list
        String brief = draftingService.generateLegalBrief(
                "Landlord locked me out without notice.",
                "### Section 14 Delhi Rent Control Act",
                List.of()
        );

        // Then
        assertNotNull(brief);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userCaptor.capture());

        assertFalse(userCaptor.getValue().contains("<assumption_caveats>"),
                "User prompt must NOT contain <assumption_caveats> when list is empty");
    }
}
