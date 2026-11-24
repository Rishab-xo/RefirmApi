package com.app.refirm.arguments;

import com.app.refirm.arguments.domain.VerifiedPrecedentAuthority;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.arguments.service.ArgumentMarkdownRenderer;
import com.app.refirm.arguments.service.ArgumentPrepService;
import com.app.refirm.arguments.service.AuthorityFirewallSanitizer;
import com.app.refirm.arguments.service.PrecedentAuthorityProvider;
import com.app.refirm.core.service.LegalRetrievalService;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("Step 5 Refinement: Quality-First N-Cluster Prompt-Cache Alignment Tests")
class ArgumentPrepPromptCacheAlignmentTest {

    private ArgumentPrepService argumentPrepService;

    @BeforeEach
    void setUp() {
        LegalWorkspaceRepository workspaceRepo = mock(LegalWorkspaceRepository.class);
        WorkspaceDocumentRepository docRepo = mock(WorkspaceDocumentRepository.class);
        WorkspaceArgumentSheetRepository sheetRepo = mock(WorkspaceArgumentSheetRepository.class);
        PrecedentAuthorityProvider precedentProvider = mock(PrecedentAuthorityProvider.class);
        AuthorityFirewallSanitizer firewall = mock(AuthorityFirewallSanitizer.class);
        ArgumentMarkdownRenderer renderer = mock(ArgumentMarkdownRenderer.class);
        ChatClient chatClient = mock(ChatClient.class);
        ObjectMapper mapper = new ObjectMapper();
        LegalRetrievalService retrievalService = mock(LegalRetrievalService.class);

        argumentPrepService = new ArgumentPrepService(
                workspaceRepo, docRepo, sheetRepo, precedentProvider,
                firewall, renderer, chatClient, mapper, retrievalService
        );
    }

    @Test
    @DisplayName("Quality First: Same-topic follow-up preserves relative ordering while allowing CATALOG_STUB -> IRAC_ENRICHED text upgrades")
    void testSameTopicFollowUpPreservesOrderAndAllowsTextUpgrade() {
        UUID wsId = UUID.randomUUID();

        // Turn 1 on Regular Bail: CATALOG_STUB text
        VerifiedPrecedentAuthority authA_stub = new VerifiedPrecedentAuthority(
                "auth-satender",
                "Satender Kumar Antil vs CBI (2022 INSC 690)",
                List.of("Bail guidelines", "Trial delay"),
                "[Supports: CLAIMANT_PETITIONER] Bail is the rule (CATALOG_STUB)",
                "VERIFIED_CORPUS"
        );
        VerifiedPrecedentAuthority authB_stub = new VerifiedPrecedentAuthority(
                "auth-arnesh",
                "Arnesh Kumar vs State of Bihar (2014 INSC 500)",
                List.of("Section 41A notice"),
                "[Supports: CLAIMANT_PETITIONER] Arrest guidelines under 41A (CATALOG_STUB)",
                "VERIFIED_CORPUS"
        );

        List<VerifiedPrecedentAuthority> turn1Result = argumentPrepService.applyPromptCacheAlignment(
                wsId,
                List.of(authA_stub, authB_stub),
                null,
                List.of("BNSS:483", "CRPC:439"),
                false
        );

        assertEquals(2, turn1Result.size());
        assertEquals("auth-satender", turn1Result.get(0).authorityId());
        assertEquals("auth-arnesh", turn1Result.get(1).authorityId());

        // Turn 2 on same topic: Precedent search returns authorities in different order, upgraded to IRAC_ENRICHED
        VerifiedPrecedentAuthority authB_enriched = new VerifiedPrecedentAuthority(
                "auth-arnesh",
                "Arnesh Kumar vs State of Bihar (2014 INSC 500)",
                List.of("Section 41A notice", "Checklist compliance"),
                "[Supports: CLAIMANT_PETITIONER] Mandatory checklist under 41A must be provided before arrest. (IRAC_ENRICHED)",
                "VERIFIED_CORPUS"
        );
        VerifiedPrecedentAuthority authA_enriched = new VerifiedPrecedentAuthority(
                "auth-satender",
                "Satender Kumar Antil vs CBI (2022 INSC 690)",
                List.of("Bail guidelines", "Category A offences", "Article 21"),
                "[Supports: CLAIMANT_PETITIONER] Incarceration without trial violates Art 21; parity applies. (IRAC_ENRICHED)",
                "VERIFIED_CORPUS"
        );
        VerifiedPrecedentAuthority authC_new = new VerifiedPrecedentAuthority(
                "auth-chidambaram",
                "P. Chidambaram vs Directorate of Enforcement (2019 INSC 100)",
                List.of("Gravity of offence"),
                "[Supports: CLAIMANT_PETITIONER] Gravity of offence alone is not sufficient to deny bail.",
                "VERIFIED_CORPUS"
        );

        // Turn 2 query with raw list: [authB_enriched, authA_enriched, authC_new]
        List<VerifiedPrecedentAuthority> turn2Result = argumentPrepService.applyPromptCacheAlignment(
                wsId,
                List.of(authB_enriched, authA_enriched, authC_new),
                null,
                List.of("BNSS:483"),
                false
        );

        assertEquals(3, turn2Result.size());

        // 1. Check relative order: auth-satender (Turn 1 index 0) must appear BEFORE auth-arnesh (Turn 1 index 1)!
        assertEquals("auth-satender", turn2Result.get(0).authorityId(),
                "Prior relative ordering must be preserved (Satender first)");
        assertEquals("auth-arnesh", turn2Result.get(1).authorityId(),
                "Prior relative ordering must be preserved (Arnesh second)");
        assertEquals("auth-chidambaram", turn2Result.get(2).authorityId(),
                "Newly discovered authority must be appended at the end in Group 2");

        // 2. Output Quality First: Verify that text is upgraded to IRAC_ENRICHED, NOT frozen to old stub!
        assertTrue(turn2Result.get(0).ratioSummary().contains("IRAC_ENRICHED"),
                "Text must use the newly enriched ratioSummary, not stale CATALOG_STUB");
        assertTrue(turn2Result.get(1).ratioSummary().contains("IRAC_ENRICHED"),
                "Text must use the newly enriched ratioSummary, not stale CATALOG_STUB");
    }

    @Test
    @DisplayName("Topic Switch: Switching to non-overlapping statutory topic in same tab returns 100% fresh precedents without pollution")
    void testTopicSwitchInSameTabReturnsPureFreshPrecedentsWithoutPollution() {
        UUID wsId = UUID.randomUUID();

        // Turn 1: Bail petition in tab
        VerifiedPrecedentAuthority bail1 = new VerifiedPrecedentAuthority(
                "auth-satender", "Satender Kumar Antil (2022 INSC 690)", List.of(), "Bail ratio", "VERIFIED_CORPUS");
        VerifiedPrecedentAuthority bail2 = new VerifiedPrecedentAuthority(
                "auth-arnesh", "Arnesh Kumar (2014 INSC 500)", List.of(), "41A ratio", "VERIFIED_CORPUS");

        argumentPrepService.applyPromptCacheAlignment(
                wsId, List.of(bail1, bail2), null, List.of("BNSS:482", "CRPC:438"), false);

        // Turn 2: Lawyer switches to Cheque Dishonour under Section 138 NI Act in the SAME tab
        VerifiedPrecedentAuthority ni1 = new VerifiedPrecedentAuthority(
                "auth-rangappa", "Rangappa vs Sri Mohan (2010 INSC 300)", List.of(), "Presumption under 139 NI Act", "VERIFIED_CORPUS");
        VerifiedPrecedentAuthority ni2 = new VerifiedPrecedentAuthority(
                "auth-kaushalya", "Kaushalya Devi vs Kishore Kumar (2020 INSC 400)", List.of(), "Cheque dishonour liability", "VERIFIED_CORPUS");

        List<VerifiedPrecedentAuthority> niResult = argumentPrepService.applyPromptCacheAlignment(
                wsId,
                List.of(ni1, ni2),
                null,
                List.of("NI_ACT:138"),
                false
        );

        // Verify: 100% of slots dedicated to NI Act precedents. ZERO bail precedents carried over!
        assertEquals(2, niResult.size());
        assertEquals("auth-rangappa", niResult.get(0).authorityId());
        assertEquals("auth-kaushalya", niResult.get(1).authorityId());

        boolean containsBail = niResult.stream().anyMatch(a -> a.authorityId().contains("satender") || a.authorityId().contains("arnesh"));
        assertFalse(containsBail, "Topic switch must NOT pollute the list with prior unrelated precedents!");
    }

    @Test
    @DisplayName("Conversational Follow-Up: Empty tuples matches most recent cluster and preserves order")
    void testConversationalFollowUpWithEmptyTuplesMatchesMostRecentCluster() {
        UUID wsId = UUID.randomUUID();

        VerifiedPrecedentAuthority ni1 = new VerifiedPrecedentAuthority(
                "auth-rangappa", "Rangappa (2010 INSC 300)", List.of(), "Ratio 1", "VERIFIED_CORPUS");
        VerifiedPrecedentAuthority ni2 = new VerifiedPrecedentAuthority(
                "auth-kaushalya", "Kaushalya Devi (2020 INSC 400)", List.of(), "Ratio 2", "VERIFIED_CORPUS");

        argumentPrepService.applyPromptCacheAlignment(
                wsId, List.of(ni1, ni2), null, List.of("NI_ACT:138"), false);

        // Follow-up: Lawyer says "strengthen argument 2", resolvedTuples is empty
        VerifiedPrecedentAuthority ni3_new = new VerifiedPrecedentAuthority(
                "auth-bir-singh", "Bir Singh (2019 INSC 500)", List.of(), "Ratio 3", "VERIFIED_CORPUS");

        List<VerifiedPrecedentAuthority> followUpResult = argumentPrepService.applyPromptCacheAlignment(
                wsId,
                List.of(ni2, ni1, ni3_new), // Search returned ni2 first
                null,
                List.of(), // Empty tuples
                false
        );

        assertEquals(3, followUpResult.size());
        // Must preserve prior order (ni1 before ni2)
        assertEquals("auth-rangappa", followUpResult.get(0).authorityId());
        assertEquals("auth-kaushalya", followUpResult.get(1).authorityId());
        assertEquals("auth-bir-singh", followUpResult.get(2).authorityId());
    }

    @Test
    @DisplayName("Topic Switch & Return: Returning to earlier topic restores that cluster's ordering")
    void testReturningToEarlierTopicRestoresEarlierClusterOrder() {
        UUID wsId = UUID.randomUUID();

        // 1. Topic 1: Bail
        VerifiedPrecedentAuthority bail1 = new VerifiedPrecedentAuthority("auth-satender", "Satender", List.of(), "Ratio", "VERIFIED_CORPUS");
        VerifiedPrecedentAuthority bail2 = new VerifiedPrecedentAuthority("auth-arnesh", "Arnesh", List.of(), "Ratio", "VERIFIED_CORPUS");
        argumentPrepService.applyPromptCacheAlignment(wsId, List.of(bail1, bail2), null, List.of("CRPC:439"), false);

        // 2. Topic 2: NI Act
        VerifiedPrecedentAuthority ni1 = new VerifiedPrecedentAuthority("auth-rangappa", "Rangappa", List.of(), "Ratio", "VERIFIED_CORPUS");
        argumentPrepService.applyPromptCacheAlignment(wsId, List.of(ni1), null, List.of("NI_ACT:138"), false);

        // 3. Return to Topic 1: Bail
        List<VerifiedPrecedentAuthority> returnBailResult = argumentPrepService.applyPromptCacheAlignment(
                wsId,
                List.of(bail2, bail1), // Search returned bail2 first
                null,
                List.of("BNSS:483"), // Pari Materia equivalent to CRPC:439
                false
        );

        assertEquals(2, returnBailResult.size());
        assertEquals("auth-satender", returnBailResult.get(0).authorityId(), "Must restore earlier Bail cluster order");
        assertEquals("auth-arnesh", returnBailResult.get(1).authorityId());
    }
}

