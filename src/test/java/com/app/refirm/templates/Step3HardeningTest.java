package com.app.refirm.templates;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.templates.domain.*;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.templates.service.*;
import com.app.refirm.triage.domain.*;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Step 3.1 Hardening Tests: Modular OKF Bundles, Fact Mapping, Stage Scoring & Block Locking")
class Step3HardeningTest {

    private LegalWorkspaceRepository workspaceRepository;
    private WorkspaceDraftRepository draftRepository;
    private WorkspaceDocumentRepository documentRepository;
    private TemplateRegistryService registryService;
    private TemplateFactResolver factResolver;
    private TemplateRenderer renderer;
    private ChatClient chatClient;
    private HybridTemplateDraftService draftService;
    private TemplateRecommendationService recommendationService;

    private LegalWorkspace workspace;
    private UUID workspaceId;
    private String clerkUserId;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(LegalWorkspaceRepository.class);
        draftRepository = mock(WorkspaceDraftRepository.class);
        documentRepository = mock(WorkspaceDocumentRepository.class);
        chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);

        registryService = new TemplateRegistryService(new ObjectMapper());
        registryService.loadTemplates();

        factResolver = new TemplateFactResolver();
        renderer = new TemplateRenderer();

        draftService = new HybridTemplateDraftService(
                workspaceRepository,
                draftRepository,
                documentRepository,
                registryService,
                factResolver,
                renderer,
                chatClient
        );

        recommendationService = new TemplateRecommendationService(registryService, documentRepository);

        workspaceId = UUID.randomUUID();
        clerkUserId = "advocate_test_user_301";

        workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setTitle("Consumer Dispute with GoMechanic");
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        when(draftRepository.save(any(WorkspaceDraft.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("Synthesized Statutory Narrative Content Grounded In Law.");
    }

    @Test
    @DisplayName("1. Modular Directory Template Loading: Master JSON + Section 138 Modular Directory Bundle")
    void testModularDirectoryTemplateLoading() {
        List<TemplateManifest> all = registryService.getAllTemplates();
        assertTrue(all.size() >= 11, "Must contain at least 10 master templates + 1 modular Section 138 template");

        Optional<TemplateManifest> opt138 = registryService.findTemplate("ni_act_sec138_dishonour_notice");
        assertTrue(opt138.isPresent(), "Section 138 modular template must be loaded from classpath");

        TemplateManifest m = opt138.get();
        assertEquals("ni_act_sec138_dishonour_notice", m.templateId());
        assertTrue(m.title().contains("Section 138"), "Title must reference Section 138");
        assertEquals(TemplateCategory.NOTICES, m.category());
        assertEquals(TemplateStage.PRE_LITIGATION_NOTICE, m.stage(), "Stage must infer or be PRE_LITIGATION_NOTICE");
        assertEquals(LegalDomain.BANKING, m.primaryDomain());

        // Verify external skeleton.md was attached cleanly
        assertNotNull(m.skeletonMarkdown(), "skeletonMarkdown must not be null");
        assertTrue(m.skeletonMarkdown().contains("SUBJECT: STATUTORY DEMAND NOTICE UNDER PROVISO (B) TO SECTION 138"),
                "External skeleton.md must be loaded and attached");
        assertTrue(m.skeletonMarkdown().contains("{{FACT:cheque_number}}"));

        // Verify mandatory statutory ingredients
        assertNotNull(m.mandatoryStatutoryIngredients());
        assertEquals(6, m.mandatoryStatutoryIngredients().size(), "Must contain 6 statutory ingredients");

        // Verify getByDomainAndStage lookup
        List<TemplateManifest> bankingNotices = registryService.getByDomainAndStage(LegalDomain.BANKING, TemplateStage.PRE_LITIGATION_NOTICE);
        assertEquals(1, bankingNotices.size());
        assertEquals("ni_act_sec138_dishonour_notice", bankingNotices.get(0).templateId());
    }

    @Test
    @DisplayName("2. Multi-Party & Deep Fact Resolution: Formats Multiple OPs, Limitation & Statute")
    void testMultiPartyAndDeepFactResolution() {
        PartyEntity claimant = new PartyEntity("Rajesh Kumar", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.PAID_CONSIDERATION);
        PartyEntity op1 = new PartyEntity("GoMechanic", null, null, null);
        PartyEntity op2 = new PartyEntity("Motorades Gurgaon", null, null, null);

        ExtractedFacts facts = new ExtractedFacts(LegalDomain.CONSUMER, claimant, null, List.of(op1, op2), List.of(), 54000.0);
        ExtractedFactsDTO ruleFacts = new ExtractedFactsDTO(com.app.refirm.rules.model.LegalDomain.CONSUMER, 54000L, "Gurugram", "Garage", 150);
        workspace.setFactLedger(CaseFactLedger.initial(facts, ruleFacts));

        StatutePin pin = new StatutePin("Consumer Protection Act, 2019", "Section 2(11)", StatuteType.OPERATIVE, "Chapter I", "Deficiency in service definition");
        DraftingContext context = new DraftingContext(
                "Car engine seized after oil pump replacement",
                facts,
                List.of(pin),
                new ResolvedForum("District Commission, Gurugram", "e-Daakhil", "Sec 34"),
                List.of(),
                24,
                false
        );
        workspace.setLatestDraftingContext(context);

        TemplateManifest testManifest = new TemplateManifest(
                "test_multi_party_manifest",
                "Test Pleading",
                TemplateCategory.PLEADINGS_COMPLAINTS,
                LegalDomain.CONSUMER,
                PartyPerspective.CLAIMANT_PETITIONER,
                "when",
                "variant",
                "need",
                "basis",
                List.of(
                        new TemplateParticular("all_ops", "All Opposite Parties", "desc", true, "oppositeParties.allFormatted"),
                        new TemplateParticular("second_op", "Second OP", "desc", true, "oppositeParties.second"),
                        new TemplateParticular("limitation", "Limitation Period", "desc", true, "limitationMonths"),
                        new TemplateParticular("statute", "Primary Statute", "desc", true, "primaryStatuteCited"),
                        new TemplateParticular("property_size", "Property Size", "desc", true, "propertySizeSqMeters")
                ),
                List.of(),
                "# PLEADING\n{{FACT:all_ops}}\n{{FACT:second_op}}\n{{FACT:limitation}}\n{{FACT:statute}}\n{{FACT:property_size}}"
        );

        Map<String, String> resolved = factResolver.resolveParticularsFromWorkspace(testManifest, workspace);

        assertEquals("1. GoMechanic, 2. Motorades Gurgaon", resolved.get("all_ops"),
                "Multiple opposite parties must format as '1. Party1, 2. Party2'");
        assertEquals("Motorades Gurgaon", resolved.get("second_op"),
                "Second opposite party must resolve correctly");
        assertTrue(resolved.get("limitation").contains("24 months"),
                "Limitation months must resolve and format properly");
        assertTrue(resolved.get("statute").contains("Consumer Protection Act, 2019"),
                "Primary statute must cite act name");
        assertTrue(resolved.get("statute").contains("Section 2(11)"),
                "Primary statute must cite section");
        assertEquals("150", resolved.get("property_size"),
                "Property size in sq meters must resolve from ruleEvaluationFacts");
    }

    @Test
    @DisplayName("3. Stage-Aware Recommendation: Notices & Complaints Rank Above Appeals for Fresh Claimants")
    void testStageAwareRecommendation() {
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setLatestDraftingContext(new DraftingContext("Complaint regarding undelivered appliance", null, List.of(), null));

        List<TemplateRecommendation> recs = recommendationService.recommendForWorkspace(workspace);
        assertFalse(recs.isEmpty());

        int noticeScore = recs.stream()
                .filter(r -> "consumer_legal_notice_deficiency".equals(r.manifest().templateId()))
                .findFirst()
                .map(TemplateRecommendation::matchScore)
                .orElse(0);

        int complaintScore = recs.stream()
                .filter(r -> "consumer_complaint_sec35_edaakhil".equals(r.manifest().templateId()))
                .findFirst()
                .map(TemplateRecommendation::matchScore)
                .orElse(0);

        int appealScore = recs.stream()
                .filter(r -> "consumer_appeal_sec41_state_comm".equals(r.manifest().templateId()))
                .findFirst()
                .map(TemplateRecommendation::matchScore)
                .orElse(0);

        assertTrue(noticeScore > appealScore, "Notice (+20) must score strictly higher than Appeal (-20 for fresh matter)");
        assertTrue(complaintScore > appealScore, "Complaint (+15) must score strictly higher than Appeal (-20 for fresh matter)");

        // Now test with preferredStage = APPELLATE
        List<TemplateRecommendation> appellateRecs = recommendationService.recommendForWorkspace(workspace, TemplateStage.APPELLATE);
        int boostedAppealScore = appellateRecs.stream()
                .filter(r -> "consumer_appeal_sec41_state_comm".equals(r.manifest().templateId()))
                .findFirst()
                .map(TemplateRecommendation::matchScore)
                .orElse(0);

        assertTrue(boostedAppealScore >= appealScore + 35, "Appellate template must gain +35 boost when preferredStage is APPELLATE");
    }

    @Test
    @DisplayName("4. Block Locking & Single-Block Chat Refinement Lifecycle")
    void testBlockLockingAndSingleBlockRefinement() {
        // Step A: Initialize v1 draft
        WorkspaceDraft draftV1 = draftService.initializeDraft(
                workspaceId,
                clerkUserId,
                "consumer_complaint_sec35_edaakhil",
                Map.of("place", "New Delhi")
        );
        assertEquals(1, draftV1.getVersion());
        assertTrue(draftV1.getLockedAiBlockKeys().isEmpty(), "Initially no blocks should be locked");

        // Step B: Lawyer manually edits facts_of_complaint and locks it
        when(draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftV1.getDraftId(), workspaceId, clerkUserId))
                .thenReturn(Optional.of(draftV1));

        String manualFacts = "1. Complainant purchased laptop on 15.01.2024.\n2. Machine stopped working on 20.01.2024.";
        WorkspaceDraft draftV2 = draftService.updateAiBlockManually(
                workspaceId,
                draftV1.getDraftId(),
                clerkUserId,
                "facts_of_complaint",
                manualFacts,
                true // lockBlock
        );

        assertEquals(2, draftV2.getVersion(), "Manual update increments version to 2");
        assertTrue(draftV2.isAiBlockLocked("facts_of_complaint"), "facts_of_complaint must be locked");
        assertEquals(manualFacts, draftV2.getGeneratedAiBlocks().get("facts_of_complaint"));
        assertTrue(draftV2.getRenderedMarkdown().contains(manualFacts));

        // Step C: Run bulk populateAiNarrativeBlocks -> MUST NOT OVERWRITE facts_of_complaint!
        when(draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftV2.getDraftId(), workspaceId, clerkUserId))
                .thenReturn(Optional.of(draftV2));

        WorkspaceDraft draftV3 = draftService.populateAiNarrativeBlocks(
                workspaceId,
                draftV2.getDraftId(),
                clerkUserId,
                "Cover deficiency in service"
        );

        assertEquals(3, draftV3.getVersion());
        // CRITICAL CHECK: Locked block is strictly preserved verbatim!
        assertEquals(manualFacts, draftV3.getGeneratedAiBlocks().get("facts_of_complaint"),
                "Locked facts_of_complaint MUST NOT be overwritten by bulk AI population");
        assertTrue(draftV3.getRenderedMarkdown().contains(manualFacts));
        // Unlocked blocks (like itemized_prayer) were populated by mock ChatClient
        assertTrue(draftV3.getGeneratedAiBlocks().containsKey("itemized_prayer"));
        assertTrue(draftV3.getRenderedMarkdown().contains("Synthesized Statutory Narrative Content Grounded In Law."));

        // Step D: Run refineSingleAiBlock on itemized_prayer
        when(draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftV3.getDraftId(), workspaceId, clerkUserId))
                .thenReturn(Optional.of(draftV3));

        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("PRAYER REFINED: Refund ₹54,000 with 18% p.a. interest plus ₹25,000 damages.");

        WorkspaceDraft draftV4 = draftService.refineSingleAiBlock(
                workspaceId,
                draftV3.getDraftId(),
                clerkUserId,
                "itemized_prayer",
                "Emphasize 18% statutory interest under CPA 2019",
                true // lockAfterRefine
        );

        assertEquals(4, draftV4.getVersion());
        assertTrue(draftV4.isAiBlockLocked("itemized_prayer"), "itemized_prayer should now be locked");
        // Verify only itemized_prayer was updated, manual facts remain intact
        assertEquals(manualFacts, draftV4.getGeneratedAiBlocks().get("facts_of_complaint"),
                "Manual facts must remain intact after single block refinement");
        assertEquals("PRAYER REFINED: Refund ₹54,000 with 18% p.a. interest plus ₹25,000 damages.",
                draftV4.getGeneratedAiBlocks().get("itemized_prayer"));
        assertTrue(draftV4.getRenderedMarkdown().contains("PRAYER REFINED: Refund ₹54,000"));
        assertTrue(draftV4.getRenderedMarkdown().contains(manualFacts));
    }
}
