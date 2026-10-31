package com.app.refirm.templates;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.templates.service.HybridTemplateDraftService;
import com.app.refirm.templates.service.TemplateFactResolver;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.templates.service.TemplateRenderer;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Step 3: Stateful Hybrid Draft Lifecycle Tests (v1 -> v2 -> v3)")
class StatefulHybridDraftLifecycleTest {

    private LegalWorkspaceRepository workspaceRepository;
    private WorkspaceDraftRepository draftRepository;
    private WorkspaceDocumentRepository documentRepository;
    private TemplateRegistryService registryService;
    private TemplateFactResolver factResolver;
    private TemplateRenderer renderer;
    private org.springframework.ai.chat.client.ChatClient chatClient;
    private HybridTemplateDraftService draftService;

    private LegalWorkspace workspace;
    private UUID workspaceId;
    private String clerkUserId;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(LegalWorkspaceRepository.class);
        draftRepository = mock(WorkspaceDraftRepository.class);
        documentRepository = mock(WorkspaceDocumentRepository.class);
        chatClient = mock(org.springframework.ai.chat.client.ChatClient.class, Answers.RETURNS_DEEP_STUBS);

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

        workspaceId = UUID.randomUUID();
        clerkUserId = "user_advocate_101";

        workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);

        PartyEntity claimant = new PartyEntity("Sunita Rao", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.PAID_CONSIDERATION);
        PartyEntity op = new PartyEntity("QuickCart Logistics Ltd.", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);

        ExtractedFacts facts = new ExtractedFacts(LegalDomain.CONSUMER, claimant, null, List.of(op), List.of(), 28500.0);
        ExtractedFactsDTO ruleFacts = new ExtractedFactsDTO(com.app.refirm.rules.model.LegalDomain.CONSUMER, 28500L, "Bengaluru", "Vendor", null);
        workspace.setFactLedger(CaseFactLedger.initial(facts, ruleFacts));

        DraftingContext draftingContext = new DraftingContext(
                "Defective laptop delivered",
                facts,
                List.of(),
                new ResolvedForum("District Consumer Disputes Redressal Commission, Bengaluru Urban", "e-Daakhil", "Sec 34 CPA 2019")
        );
        workspace.setLatestDraftingContext(draftingContext);

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        when(draftRepository.save(any(WorkspaceDraft.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("AI Generated Narrative Pleading Content Grounded In Statute.");
    }

    @Test
    @DisplayName("Should verify full v1 (0-token) -> v2 (AI blocks) -> v3 (Particulars update preserving AI blocks) lifecycle")
    void testFullDraftLifecycle() {
        // ─── STEP 1: INITIALIZE DRAFT (v1) ───
        WorkspaceDraft draftV1 = draftService.initializeDraft(
                workspaceId,
                clerkUserId,
                "consumer_complaint_sec35_edaakhil",
                Map.of("complainant_address", "123 Indiranagar, Bengaluru")
        );

        assertNotNull(draftV1);
        assertEquals(1, draftV1.getVersion(), "Initial draft must be v1");
        assertEquals("Sunita Rao", draftV1.getFilledParticulars().get("complainant_name"));
        assertEquals("123 Indiranagar, Bengaluru", draftV1.getFilledParticulars().get("complainant_address"));
        assertTrue(draftV1.getGeneratedAiBlocks().isEmpty(), "v1 must have 0 AI blocks generated");
        assertTrue(draftV1.getRenderedMarkdown().contains("Sunita Rao"));
        assertTrue(draftV1.getRenderedMarkdown().contains("VERIFICATION"), "Outer skeleton verification must exist");
        assertTrue(draftV1.getRenderedMarkdown().contains("[AI DRAFT BLOCK:"), "AI placeholders should be unpopulated");

        // ─── STEP 2: POPULATE AI NARRATIVE BLOCKS (v2) ───
        when(draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftV1.getDraftId(), workspaceId, clerkUserId))
                .thenReturn(Optional.of(draftV1));

        WorkspaceDraft draftV2 = draftService.populateAiNarrativeBlocks(
                workspaceId,
                draftV1.getDraftId(),
                clerkUserId,
                "Ensure strict claim under Section 35 CPA 2019"
        );

        assertEquals(2, draftV2.getVersion(), "Draft version must increment to 2");
        assertFalse(draftV2.getGeneratedAiBlocks().isEmpty(), "AI narrative blocks must be populated");
        assertTrue(draftV2.getGeneratedAiBlocks().containsKey("facts_of_complaint"));
        assertTrue(draftV2.getRenderedMarkdown().contains("AI Generated Narrative Pleading Content Grounded In Statute."));
        assertTrue(draftV2.getRenderedMarkdown().contains("VERIFICATION"), "Outer skeleton verification must remain 100% intact");
        assertFalse(draftV2.getRenderedMarkdown().contains("[AI DRAFT BLOCK:"), "No unpopulated AI block placeholders should remain");

        // ─── STEP 3: UPDATE PARTICULARS PRESERVING AI BLOCKS (v3) ───
        when(draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftV2.getDraftId(), workspaceId, clerkUserId))
                .thenReturn(Optional.of(draftV2));

        WorkspaceDraft draftV3 = draftService.updateParticulars(
                workspaceId,
                draftV2.getDraftId(),
                clerkUserId,
                Map.of("place", "Gurugram")
        );

        assertEquals(3, draftV3.getVersion(), "Draft version must increment to 3");
        assertEquals("Gurugram", draftV3.getFilledParticulars().get("place"));
        assertTrue(draftV3.getRenderedMarkdown().contains("Gurugram"), "Updated particular must be spliced in");

        // CRITICAL INVARIANT: AI narrative blocks from v2 MUST BE STRICTLY PRESERVED in v3!
        assertTrue(draftV3.getRenderedMarkdown().contains("AI Generated Narrative Pleading Content Grounded In Statute."),
                "AI narrative blocks synthesized in v2 must be preserved when updating particulars in v3");
        assertTrue(draftV3.getRenderedMarkdown().contains("VERIFICATION"),
                "Verification clause must remain intact in v3");
    }
}
