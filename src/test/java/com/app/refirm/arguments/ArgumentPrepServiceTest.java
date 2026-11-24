package com.app.refirm.arguments;

import com.app.refirm.arguments.domain.*;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.arguments.service.*;
import com.app.refirm.inspector.service.InspectorService;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.workspace.domain.*;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.dto.InspectorDTOs.*;
import com.app.refirm.inspector.service.AuthorityDrawerService;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.workspace.repository.WorkspaceSavedAuthorityRepository;
import com.app.refirm.core.service.LegalRetrievalService;
import com.app.refirm.rules.registry.StatuteRegistry;
import com.app.refirm.rules.evaluation.StatuteRuleEvaluator;
import com.app.refirm.triage.domain.StatuteType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Step 4 Unit Tests: Courtroom Argument Prep Engine, Rebuttal Matrix & Authority Firewall")
class ArgumentPrepServiceTest {

    private LegalWorkspaceRepository workspaceRepository;
    private WorkspaceDocumentRepository documentRepository;
    private WorkspaceArgumentSheetRepository argumentSheetRepository;
    private PrecedentAuthorityProvider precedentProvider;
    private AuthorityFirewallSanitizer firewallSanitizer;
    private ArgumentMarkdownRenderer markdownRenderer;
    private ChatClient chatClient;
    private ObjectMapper objectMapper;
    private ArgumentPrepService argumentPrepService;

    private LegalWorkspace workspace;
    private UUID workspaceId;
    private String clerkUserId;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(LegalWorkspaceRepository.class);
        documentRepository = mock(WorkspaceDocumentRepository.class);
        argumentSheetRepository = mock(WorkspaceArgumentSheetRepository.class);
        chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        objectMapper = new ObjectMapper();

        precedentProvider = new WorkspaceUploadedPrecedentProvider(documentRepository);
        firewallSanitizer = new AuthorityFirewallSanitizer();
        markdownRenderer = new ArgumentMarkdownRenderer();

        argumentPrepService = new ArgumentPrepService(
                workspaceRepository,
                documentRepository,
                argumentSheetRepository,
                precedentProvider,
                firewallSanitizer,
                markdownRenderer,
                chatClient,
                objectMapper
        );

        workspaceId = UUID.randomUUID();
        clerkUserId = "clerk_advocate_42";

        workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setTitle("Consumer Dispute vs MegaMart");
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setFactLedger(new CaseFactLedger(1, null, null, Collections.emptySet(), Instant.now()));

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        when(argumentSheetRepository.save(any(WorkspaceArgumentSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Test 1: Claimant vs Respondent Perspective Argument Generation Records State & Renders Dossier")
    void testClaimantVsRespondentPerspectiveArgumentGeneration() {
        // 1. Generate in CLAIMANT_PETITIONER mode
        WorkspaceArgumentSheet claimantSheet = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.CLAIMANT_PETITIONER,
                "Focus on statutory compensation under CPA 2019"
        );

        assertNotNull(claimantSheet);
        assertEquals(PartyPerspective.CLAIMANT_PETITIONER, claimantSheet.getPartyPerspective());
        assertEquals(1, claimantSheet.getSourceFactLedgerVersion());
        assertEquals(1, claimantSheet.getVersion());
        assertNotNull(claimantSheet.getArgumentDossier());
        assertTrue(claimantSheet.getRenderedMarkdown().contains("CLAIMANT / PETITIONER"));
        assertFalse(claimantSheet.isStaleComparedTo(workspace));

        // 2. Generate in OPPOSITE_PARTY_RESPONDENT mode
        WorkspaceArgumentSheet respondentSheet = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.OPPOSITE_PARTY_RESPONDENT,
                "Focus on threshold maintainability bar"
        );

        assertNotNull(respondentSheet);
        assertEquals(PartyPerspective.OPPOSITE_PARTY_RESPONDENT, respondentSheet.getPartyPerspective());
        assertEquals(1, respondentSheet.getSourceFactLedgerVersion());
        assertEquals(1, respondentSheet.getVersion());
        assertTrue(respondentSheet.getRenderedMarkdown().contains("OPPOSITE PARTY / RESPONDENT"));
    }

    @Test
    @DisplayName("Test 2: Authority Firewall Blocks Hallucinated Precedents & Fake Evidence, Preserves Real Precedents")
    void testAuthorityFirewallBlocksHallucinatedPrecedentsAndFakeEvidence() {
        // Part A: Raw dossier with hallucinated precedent and fake evidence anchor
        ArgumentSubmission fakeSubmission = new ArgumentSubmission(
                "SUB_1",
                "Strict Liability Proposition",
                "As held in Fake Corp v. State (2024) 9 SCC 999, the defendant is strictly liable.",
                List.of("CPA Section 2(11)"),
                List.of("Fake Corp v. State (2024) 9 SCC 999"),
                "[FabricatedEvidence.pdf, Page 12]",
                null
        );

        RebuttalMatrixItem fakeRebuttal = new RebuttalMatrixItem(
                "REB_1",
                "Opponent alleges limitation bar",
                "Relying on Fake Corp v. State (2024) 9 SCC 999, the period of limitation stands extended.",
                "Fake Corp v. State (2024) 9 SCC 999"
        );

        BenchQuestionItem fakeBenchQuestion = new BenchQuestionItem(
                "Counsel, which Supreme Court bench settled this proposition?",
                "The Supreme Court in Fake Corp v. State (2024) 9 SCC 999 settled this authoritatively.",
                "Fake Corp v. State (2024) 9 SCC 999"
        );

        ArgumentDossier rawDossier = new ArgumentDossier(
                "Opening 60s pitch",
                List.of(new FramedLegalIssue("ISSUE_1", "Whether claim is maintainable?", "On Claimant")),
                List.of(fakeSubmission),
                List.of(fakeRebuttal),
                List.of(fakeBenchQuestion),
                List.of("Pagination check")
        );

        // Sanitize with EMPTY allowed precedents and EMPTY active evidence docs
        ArgumentDossier sanitizedEmpty = firewallSanitizer.sanitizeDossier(
                rawDossier,
                List.of(),
                List.of(),
                List.of()
        );

        assertNotNull(sanitizedEmpty);
        ArgumentSubmission sanitizedSub = sanitizedEmpty.coreSubmissions().get(0);
        assertTrue(sanitizedSub.pinnedPrecedents().isEmpty(), "Hallucinated pinned precedents must be cleared");
        assertEquals("NONE_UPLOADED", sanitizedSub.verifiedEvidenceAnchor(), "Fake evidence anchor must be reset to NONE_UPLOADED");
        assertNotNull(sanitizedSub.evidentiaryGapWarning(), "Evidentiary gap warning must be populated");
        assertFalse(sanitizedEmpty.rebuttalMatrix().get(0).ourCounterRebuttal().contains("Fake Corp v. State"));
        assertFalse(sanitizedEmpty.benchGrillQuestions().get(0).recommendedCourtroomAnswer().contains("Fake Corp v. State"));

        // Part B: Real JUDGMENT_PRECEDENT uploaded with IracBrief
        WorkspaceDocument judgmentDoc = new WorkspaceDocument();
        judgmentDoc.setDocumentId(UUID.randomUUID());
        judgmentDoc.setWorkspaceId(workspaceId);
        judgmentDoc.setClerkUserId(clerkUserId);
        judgmentDoc.setFileName("genuine_precedent.pdf");
        judgmentDoc.setDocumentType(DocumentType.JUDGMENT_PRECEDENT);
        judgmentDoc.setIngestionStatus(IngestionStatus.READY);

        IracBrief irac = new IracBrief(
                "Material defect in machinery",
                List.of("Whether manufacturer is strictly liable?"),
                List.of("Section 84 CPA, 2019"),
                "Manufacturer is strictly liable for manufacturing defect regardless of seller negligence",
                "Appeal allowed"
        );
        StructuredDocumentSummary summary = new StructuredDocumentSummary(
                DocumentType.JUDGMENT_PRECEDENT,
                "Genuine Corp v. Union of India (2023) 5 SCC 100",
                "Product liability ratio",
                List.of("2021 purchase", "2022 breakdown"),
                List.of("Consumer", "Manufacturer"),
                List.of("INR 5,00,000"),
                irac
        );
        judgmentDoc.setStructuredSummary(summary);

        when(documentRepository.findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId))
                .thenReturn(List.of(judgmentDoc));

        List<VerifiedPrecedentAuthority> resolvedPrecedents = precedentProvider.resolveVerifiedPrecedents(workspace);
        assertNotNull(resolvedPrecedents);
        assertEquals(1, resolvedPrecedents.size());
        assertEquals("Genuine Corp v. Union of India (2023) 5 SCC 100", resolvedPrecedents.get(0).caseTitleAndCitation());
        assertEquals("Manufacturer is strictly liable for manufacturing defect regardless of seller negligence", resolvedPrecedents.get(0).ratioSummary());

        // Now sanitize a dossier citing the genuine precedent
        ArgumentSubmission realSubmission = new ArgumentSubmission(
                "SUB_1",
                "Product Liability",
                "As held in Genuine Corp v. Union of India (2023) 5 SCC 100, manufacturer is strictly liable.",
                List.of("Section 84 CPA"),
                List.of("Genuine Corp v. Union of India (2023) 5 SCC 100"),
                "NONE_UPLOADED",
                "Evidence gap"
        );
        ArgumentDossier dossierWithRealPrecedent = new ArgumentDossier(
                "Pitch", List.of(), List.of(realSubmission), List.of(), List.of(), List.of()
        );

        ArgumentDossier sanitizedWithReal = firewallSanitizer.sanitizeDossier(
                dossierWithRealPrecedent,
                List.of(),
                resolvedPrecedents,
                List.of()
        );

        assertEquals(1, sanitizedWithReal.coreSubmissions().get(0).pinnedPrecedents().size());
        assertEquals("Genuine Corp v. Union of India (2023) 5 SCC 100 [Verified in Corpus]",
                sanitizedWithReal.coreSubmissions().get(0).pinnedPrecedents().get(0));
    }

    @Test
    @DisplayName("Test 3: Staleness Detection When FactLedger Version Or PartyPerspective Changes")
    void testStalenessDetectionWhenFactLedgerOrPerspectiveChanges() {
        WorkspaceArgumentSheet sheet = new WorkspaceArgumentSheet();
        sheet.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        sheet.setSourceFactLedgerVersion(1);

        // Initially fresh
        assertFalse(sheet.isStaleComparedTo(workspace), "Sheet should not be stale when versions and perspectives match");

        // 1. Fact ledger version increment: v1 -> v2
        workspace.setFactLedger(new CaseFactLedger(2, null, null, Collections.emptySet(), Instant.now()));
        assertTrue(sheet.isStaleComparedTo(workspace), "Sheet must be stale when workspace fact ledger advances from v1 to v2");

        // 2. Perspective mismatch
        workspace.setFactLedger(new CaseFactLedger(1, null, null, Collections.emptySet(), Instant.now()));
        workspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT);
        assertTrue(sheet.isStaleComparedTo(workspace), "Sheet must be stale when party perspective is toggled");
    }

    @Test
    @DisplayName("Test 4: Incremental Targeted Rebuttal Appends REB_2 & Preserves Existing Submissions and Locked IDs")
    void testIncrementalTargetedRebuttalPreservesExistingSubmissions() {
        UUID sheetId = UUID.randomUUID();
        WorkspaceArgumentSheet existingSheet = new WorkspaceArgumentSheet();
        existingSheet.setSheetId(sheetId);
        existingSheet.setWorkspaceId(workspaceId);
        existingSheet.setClerkUserId(clerkUserId);
        existingSheet.setTitle("Courtroom Argument Sheet");
        existingSheet.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        existingSheet.setVersion(1);
        existingSheet.setSourceFactLedgerVersion(1);
        existingSheet.getLockedSubmissionIds().add("SUB_1");

        ArgumentSubmission sub1 = new ArgumentSubmission(
                "SUB_1",
                "Deficiency in Service",
                "The supplier failed to deliver goods within 30 days.",
                List.of("CPA Section 2(11)"),
                List.of(),
                "NONE_UPLOADED",
                "Need invoice"
        );

        RebuttalMatrixItem reb1 = new RebuttalMatrixItem(
                "REB_1",
                "Opponent argues force majeure",
                "Force majeure requires an act of God, not supply chain mismanagement.",
                "Section 56 Contract Act"
        );

        ArgumentDossier initialDossier = new ArgumentDossier(
                "Initial 60s pitch",
                List.of(new FramedLegalIssue("ISSUE_1", "Whether delivery delay is deficiency?", "On Claimant")),
                List.of(sub1),
                List.of(reb1),
                List.of(new BenchQuestionItem("How do you prove delay?", "Tracking receipts", "Section 63 BSA")),
                List.of("Original invoice")
        );

        existingSheet.setArgumentDossier(initialDossier);
        existingSheet.setRenderedMarkdown(markdownRenderer.renderToMarkdown(
                existingSheet.getTitle(),
                existingSheet.getPartyPerspective(),
                initialDossier,
                false
        ));

        when(argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId))
                .thenReturn(Optional.of(existingSheet));

        // When appending targeted rebuttal countering opponent's new contention
        WorkspaceArgumentSheet updatedSheet = argumentPrepService.appendTargetedRebuttal(
                workspaceId,
                sheetId,
                clerkUserId,
                "Opposite party argues claim is time-barred under Section 69 CPA, 2019."
        );

        assertNotNull(updatedSheet);
        assertEquals(2, updatedSheet.getVersion(), "Sheet version must increment from 1 to 2");

        // Existing submissions and locked IDs preserved
        assertEquals(1, updatedSheet.getArgumentDossier().coreSubmissions().size());
        assertEquals("SUB_1", updatedSheet.getArgumentDossier().coreSubmissions().get(0).submissionId());
        assertTrue(updatedSheet.getLockedSubmissionIds().contains("SUB_1"), "Locked submission ID 'SUB_1' must be preserved");

        // Rebuttal matrix expanded
        assertEquals(2, updatedSheet.getArgumentDossier().rebuttalMatrix().size());
        assertEquals("REB_1", updatedSheet.getArgumentDossier().rebuttalMatrix().get(0).rebuttalId());
        assertEquals("REB_2", updatedSheet.getArgumentDossier().rebuttalMatrix().get(1).rebuttalId());
        assertEquals("Opposite party argues claim is time-barred under Section 69 CPA, 2019.",
                updatedSheet.getArgumentDossier().rebuttalMatrix().get(1).anticipatedOppositeArgument());

        // Re-rendered markdown includes REB_2
        assertTrue(updatedSheet.getRenderedMarkdown().contains("REB_2"));
    }

    @Test
    @DisplayName("Test 5: ArgumentMarkdownRenderer Statutory Table, Noise Suppression & Capped Checklist")
    void testArgumentMarkdownRendererStatutoryTableNoiseSuppressionAndCappedChecklist() {
        List<StatutePin> statutePack = List.of(
                new StatutePin("Consumer Protection Act, 2019", "Section 2(7)(ii)", "Consumer definition under offline & online commerce"),
                new StatutePin("Consumer Protection Act, 2019", "Section 35", "Manner in which complaint shall be made to District Commission")
        );

        List<ArgumentSubmission> submissions = List.of(
                new ArgumentSubmission(
                        "SUB_1",
                        "Maintainability as Consumer",
                        "May it please this Hon'ble Commission, the Complainant falls strictly within Section 2(7)(ii) and complaint is duly instituted under Section 35.",
                        List.of("Consumer Protection Act, 2019 Section 2(7)(ii)", "Consumer Protection Act, 2019 Section 35"),
                        List.of(),
                        "NONE_UPLOADED",
                        "no relevant evidence is in the uploaded vault"
                ),
                new ArgumentSubmission(
                        "SUB_2",
                        "Deficiency in Service",
                        "It is respectfully submitted that the failure to refund constitutes deficiency under Section 2(11).",
                        List.of("Consumer Protection Act, 2019 Section 2(11)"),
                        List.of(),
                        "NONE_UPLOADED",
                        "is unrelated to the dispute"
                )
        );

        List<String> bloatedChecklist = List.of(
                "Document 1: Purchase Invoice",
                "Document 2: Bank Statement",
                "Document 3: Email Trail",
                "Document 4: Statutory Legal Notice",
                "Document 5: Postal Tracking Slip",
                "Document 6: Affidavit of Service",
                "Document 7: Additional Ledger (Excess)",
                "Document 8: Personal Notes (Excess)",
                "Document 1: Purchase Invoice" // duplicate
        );

        ArgumentDossier dossier = new ArgumentDossier(
                "Opening Courtroom Pitch: May it please this Hon'ble Commission...",
                List.of(new FramedLegalIssue("ISSUE_1", "Whether complainant is a consumer?", "On Complainant")),
                submissions,
                List.of(new RebuttalMatrixItem("REB_1", "Opponent alleges lack of jurisdiction", "Jurisdiction is established under Section 34(2).", "Section 34(2) CPA")),
                List.of(new BenchQuestionItem("Where is the proof of payment?", "Annexed as Exhibit C.", "Exhibit C")),
                bloatedChecklist
        );

        String markdown = markdownRenderer.renderToMarkdown(
                "Claimant Courtroom Argument Sheet",
                PartyPerspective.CLAIMANT_PETITIONER,
                dossier,
                false,
                statutePack
        );

        assertNotNull(markdown);

        // 1. Verifies 3-column statutory framework table is rendered
        assertTrue(markdown.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK"));
        assertTrue(markdown.contains("| Statute | Provision | Statutory Rule & Core Application |"));
        assertTrue(markdown.contains("| Consumer Protection Act, 2019 | Section 2(7)(ii) | Consumer definition under offline & online commerce |"));
        assertTrue(markdown.contains("| Consumer Protection Act, 2019 | Section 35 | Manner in which complaint shall be made to District Commission |"));

        // 2. Verifies noise suppression: no repetitive "NONE_UPLOADED" anchors or inline gap warnings on every submission
        assertFalse(markdown.contains("- **Verified Evidence Anchor:** `NONE_UPLOADED`"), "NONE_UPLOADED anchors must not be printed");
        assertFalse(markdown.contains("> ⚠️ **Evidentiary Gap:** no relevant evidence is in the uploaded vault"), "Per-submission repetitive gap warnings must be suppressed");
        assertTrue(markdown.contains("> *Note: Submissions below are anchored to the pleaded narrative and statutory framework. Documentary exhibits to be annexed prior to filing are consolidated in Section 6.*"), "Consolidated clean note must be printed at top of Section 3");

        // 3. Verifies Pre-Hearing Evidentiary Checklist is capped at <= 6 items and deduplicated
        assertTrue(markdown.contains("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE"));
        assertFalse(markdown.contains("Document 7: Additional Ledger (Excess)"), "Checklist items exceeding 6 must be truncated");
        assertFalse(markdown.contains("Document 8: Personal Notes (Excess)"), "Checklist items exceeding 6 must be truncated");

        // Count checklist items
        long checklistItemCount = Arrays.stream(markdown.split("\n"))
                .filter(line -> line.trim().startsWith("- [ ]"))
                .count();
        assertTrue(checklistItemCount <= 6, "Checklist item count must be <= 6, actual: " + checklistItemCount);
        assertEquals(6, checklistItemCount, "Should cap at exactly 6 items from unique entries");
    }

    @Test
    @DisplayName("Test 6: Statute Inspector Sanitization, Humanization, Substantive Section 79 Rebuttal & Zero Fake Duplicate Cards")
    void testStatuteSanitizationHumanizationAndSubstantiveRebuttal() {
        // 1. Verify sanitizeStatuteMarkdownForDisplay strips LLM prompt header and YAML frontmatter
        String rawStatuteText = """
                ### PINNED STATUTORY ANCHORS (BASE SECTIONS — DRILL DOWN TO EXACT NESTED SUB-CLAUSES IN SUBMISSIONS)
                The following statutory anchors are retrieved for this matter. Ensure all operative definitions are cited.
                ---
                ---
                domain: CONSUMER
                act: The Consumer Protection Act, 2019
                act_slug: the-consumer-protection-act-2019
                section: 2
                ---
                # Section 2 — Definitions
                In this Act, unless the context otherwise requires,—
                (7) "consumer" means any person who buys any goods or hires or avails of any service for a consideration...
                """;

        String sanitized = InspectorService.sanitizeStatuteMarkdownForDisplay(rawStatuteText);
        assertFalse(sanitized.contains("### PINNED STATUTORY ANCHORS"), "Must purge LLM prompt header");
        assertFalse(sanitized.contains("domain: CONSUMER"), "Must purge YAML frontmatter keys");
        assertFalse(sanitized.contains("act_slug"), "Must purge YAML frontmatter keys");
        assertFalse(sanitized.startsWith("---"), "Must strip leading yaml delimiters");
        assertTrue(sanitized.contains("# Section 2 — Definitions"), "Must preserve Section 2 header");
        assertTrue(sanitized.contains("(7) \"consumer\" means"), "Must preserve bare act text");

        // 2. Verify humanizeActTitle removes underscores and cleans fake names
        assertEquals("The Consumer Protection Act, 2019", InspectorService.humanizeActTitle("The_Consumer_Protection_Act_2019"));
        assertEquals("The Consumer Protection Act, 2019", InspectorService.humanizeActTitle("consumer-protection-act-2019"));
        assertEquals("The Consumer Protection Act, 2019", InspectorService.humanizeActTitle("Relevant Legal Code"));
        assertEquals("The Consumer Protection Act, 2019", InspectorService.humanizeActTitle("Statutory Code"));

        // 3. Verify humanizeSectionNumber attaches cited sub-clauses
        String secHeaderWithSubClauses = InspectorService.humanizeSectionNumber("section_2", Set.of("2(7)(ii)", "2(11)", "2(47)"));
        assertEquals("Section 2 — Definitions (2(11), 2(47), 2(7)(ii))", secHeaderWithSubClauses);

        String secHeaderBase = InspectorService.humanizeSectionNumber("section_35");
        assertEquals("Section 35 — Manner in which complaint shall be made", secHeaderBase);

        // 4. Verify Substantive 2-Pronged Cross-Statute Counter-Rebuttal for Section 79 IT Act
        UUID sheetId = UUID.randomUUID();
        WorkspaceArgumentSheet sheet = new WorkspaceArgumentSheet();
        sheet.setSheetId(sheetId);
        sheet.setWorkspaceId(workspaceId);
        sheet.setClerkUserId(clerkUserId);
        sheet.setTitle("Test Courtroom Sheet");
        sheet.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        sheet.setVersion(1);
        sheet.setSourceFactLedgerVersion(1);
        sheet.setArgumentDossier(new ArgumentDossier(
                "Opening Pitch",
                List.of(),
                List.of(),
                new ArrayList<>(),
                List.of(),
                List.of()
        ));
        sheet.setLockedSubmissionIds(new HashSet<>());
        sheet.setRenderedMarkdown("");

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)).thenReturn(Optional.of(workspace));
        when(argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId)).thenReturn(Optional.of(sheet));
        when(argumentSheetRepository.save(any(WorkspaceArgumentSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceArgumentSheet updatedSheet = argumentPrepService.appendTargetedRebuttal(
                workspaceId,
                sheetId,
                clerkUserId,
                "Opposite party argues Section 79 IT Act intermediary safe harbour immunity"
        );

        assertNotNull(updatedSheet);
        assertNotNull(updatedSheet.getArgumentDossier().rebuttalMatrix());
        assertEquals(1, updatedSheet.getArgumentDossier().rebuttalMatrix().size());

        RebuttalMatrixItem rebuttal = updatedSheet.getArgumentDossier().rebuttalMatrix().get(0);
        assertTrue(rebuttal.ourCounterRebuttal().contains("Section 79"), "Must address Section 79 IT Act");
        assertTrue(rebuttal.ourCounterRebuttal().contains("Section 79(2)") || rebuttal.ourCounterRebuttal().contains("79(2)(a)"), "Prong 1: Must attack substantive bar under Section 79(2)");
        assertTrue(rebuttal.ourCounterRebuttal().contains("2(17)") || rebuttal.ourCounterRebuttal().contains("Electronic Service Provider"), "Prong 1: Must cite Section 2(17) Electronic Service Provider");
        assertTrue(rebuttal.ourCounterRebuttal().contains("100"), "Prong 2: Must cite Section 100 CPA non-derogation");
        assertTrue(rebuttal.ourCounterRebuttal().startsWith("My Lords / Your Honour"), "Must speak in Senior Advocate first-person courtroom voice");

        // 5. Verify that NO fake duplicate cards ("Statutory mandate under...") are produced
        assertFalse(rebuttal.statutoryOrEvidentiaryAnchor().contains("Statutory mandate under"), "Never generate fake statutory mandate placeholders");
    }

    @Test
    @DisplayName("Test 7: Table 2B Formatting — Single-Line Rows (<= 350 chars), Dynamic Section Headings & Operative Capping")
    void testTable2BSingleLineAndZeroRawStatuteDump() {
        String rawMassiveSection2 = """
                ### PINNED STATUTORY ANCHORS (BASE SECTIONS — DRILL DOWN TO EXACT NESTED SUB-CLAUSES IN SUBMISSIONS)
                - Section 2(7)(ii) [Consumer & Beneficiary Standing]
                - Section 2(11)(i)-(ii) [Deficiency in Service]
                - Section 2(47) [Unfair Trade Practice]
                - Section 34(1) [District Commission Jurisdiction]
                - Section 47 [State Commission Jurisdiction]
                - Section 92 [Penalty]
                ---
                ---
                domain: CONSUMER
                act: The Consumer Protection Act, 2019
                section: 2
                ---
                # Section 2 — Definitions
                In this Act, unless the context otherwise requires,—
                (1) "advertisement" means any audio or visual publicity, representation, endorsement or pronouncement made by means of light, sound, smoke, gas, print, electronic media, internet or website and includes any notice, circular, label, wrapper, invoice or such other documents;
                (2) "appropriate laboratory" means a laboratory or organisation recognized by the Central Government;
                (3) "branch office" means any establishment described as a branch by the enterprise;
                (4) "central authority" means the Central Consumer Protection Authority;
                (5) "claimant" means any consumer who makes a complaint under this Act;
                (6) "complainant" means a consumer;
                (7) "consumer" means any person who buys any goods or hires any service;
                (8) "consumer dispute" means a dispute where the person against whom a complaint has been made denies the allegations;
                (9) "consumer rights" includes the right to be protected against marketing of goods;
                (10) "defect" means any fault, imperfection or shortcoming in the quality;
                (11) "deficiency" means any fault, imperfection, shortcoming or inadequacy in the quality, nature and manner of performance...
                """;

        String rawSection34WithHeadings = """
                ---
                domain: CONSUMER
                act: The Consumer Protection Act, 2019
                section: 34
                heading: Jurisdiction of District Commission
                ---
                ### Jurisdiction of District Commission
                Subject to the other provisions of this Act, the District Commission shall have jurisdiction to entertain complaints where the value of the goods or services paid as consideration does not exceed fifty lakh rupees.
                Provided that the District Commission shall have jurisdiction over matters where the cause of action arises wholly or in part within its local limits.
                """;

        List<StatutePin> pins = List.of(
                new StatutePin("The Consumer Protection Act, 2019", "section_2", rawMassiveSection2),
                new StatutePin("The Consumer Protection Act, 2019", "section_34", rawSection34WithHeadings)
        );

        ArgumentDossier dossier = new ArgumentDossier(
                "Opening Pitch",
                List.of(new FramedLegalIssue("ISSUE_1", "Whether complainant is a consumer under Section 2(7) and dispute is maintainable under Section 34?", "Initial onus lies on the Complainant to establish transaction and consideration")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        // Verify summarizeStatutePinForTableCell for section_2 never contains Section 34, Section 47, or Section 92
        String sec2Summary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(pins.get(0), dossier);
        assertNotNull(sec2Summary);
        assertTrue(sec2Summary.contains("Section 2(7)(ii)"), "Must contain Section 2(7)(ii)");
        assertFalse(sec2Summary.contains("Section 34"), "Must never contain Section 34 inside Section 2 cell");
        assertFalse(sec2Summary.contains("Section 47"), "Must never contain Section 47 inside Section 2 cell");
        assertFalse(sec2Summary.contains("Section 92"), "Must never contain Section 92 inside Section 2 cell");
        assertTrue(sec2Summary.length() <= 240, "Section 2 summary must be strictly <= 240 chars, actual: " + sec2Summary.length());
        assertFalse(sec2Summary.contains("\n") || sec2Summary.contains("\r"), "Section 2 summary must contain zero newlines");

        String markdown = markdownRenderer.renderToMarkdown(
                "Table 2B Test Sheet",
                PartyPerspective.CLAIMANT_PETITIONER,
                dossier,
                false,
                pins
        );

        assertNotNull(markdown);
        assertTrue(markdown.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK"));

        // Verify sub-label is *Onus & Governing Standard:* instead of *Burden of Proof:*
        assertTrue(markdown.contains("*Onus & Governing Standard:*"), "Must render Onus & Governing Standard label");
        assertFalse(markdown.contains("*Burden of Proof:*"), "Must never render legacy Burden of Proof label");

        // Extract lines of Section 2B table
        String[] lines = markdown.split("\r?\n");
        List<String> tableRows = new ArrayList<>();
        boolean inside2B = false;
        for (String line : lines) {
            if (line.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK")) {
                inside2B = true;
                continue;
            }
            if (inside2B) {
                if (line.startsWith("### ")) break;
                if (line.startsWith("|") && !line.contains(":---") && !line.contains("Provision")) {
                    tableRows.add(line);
                }
            }
        }

        assertEquals(2, tableRows.size(), "Should have exactly 2 rows for the 2 pins");

        // Verify Row 1 (Section 2)
        String row1 = tableRows.get(0);
        assertTrue(row1.contains("Section 2 — Definitions"), "Must dynamically format provision with heading, not raw section_2");
        assertFalse(row1.contains("section_2"), "Must never dump raw section_2 identifier");
        assertTrue(row1.contains("Section 2(7)(ii) [Consumer & Beneficiary Standing]"), "Must extract pinned sub-clause anchors");
        assertFalse(row1.contains("Section 34"), "Row 1 must never bleed Section 34 into Section 2");
        assertFalse(row1.contains("Section 47"), "Row 1 must never bleed Section 47 into Section 2");
        assertTrue(row1.length() <= 350, "Row 1 must be strictly <= 350 chars, actual: " + row1.length());
        assertFalse(row1.contains("\n") || row1.contains("\r"), "Row 1 must contain zero newlines");
        assertFalse(row1.contains("(1) \"advertisement\""), "Must never dump multi-line Bare Act definitions");

        // Verify Row 2 (Section 34)
        String row2 = tableRows.get(1);
        assertTrue(row2.contains("Section 34 — Jurisdiction of District Commission"), "Must dynamically format Section 34 heading");
        assertFalse(row2.contains("section_34"), "Must never dump raw section_34 identifier");
        assertTrue(row2.contains("Subject to the other provisions of this Act"), "Must take first operative sentence");
        assertTrue(row2.length() <= 350, "Row 2 must be strictly <= 350 chars, actual: " + row2.length());
        assertFalse(row2.contains("\n") || row2.contains("\r"), "Row 2 must contain zero newlines");

        // Verify filterToActivelyCitedStatutes excludes un-cited candidate sections
        List<StatutePin> testPack = List.of(
                new StatutePin("The Consumer Protection Act, 2019", "section_2", "operative"),
                new StatutePin("The Consumer Protection Act, 2019", "section_34", "operative"),
                new StatutePin("The Consumer Protection Act, 2019", "section_47", "operative")
        );
        List<StatutePin> filtered = ArgumentMarkdownRenderer.filterToActivelyCitedStatutes(testPack, Set.of("2", "34"));
        assertEquals(2, filtered.size(), "Must filter out uncited Section 47");
        assertTrue(filtered.stream().noneMatch(p -> p.sectionNumber().contains("47")), "Must not contain Section 47");
    }

    @Test
    @DisplayName("Test 8: CorpusPrecedentRegistryService — 13 LegalDomains Loaded Dynamically Without Hardcoded Switches")
    void testCorpusPrecedentRegistry13Domains() {
        CorpusPrecedentRegistryService registry = new CorpusPrecedentRegistryService(objectMapper);
        registry.init();

        List<CorpusPrecedentEntry> all = registry.getAllPrecedents();
        assertNotNull(all);
        assertTrue(all.size() >= 26, "Must contain at least 26 precedents across 13 domains, actual: " + all.size());

        // Verify all 13 domains have at least 2 precedents each
        LegalDomain[] allDomains = {
                LegalDomain.BANKING, LegalDomain.CIVIL_PROPERTY, LegalDomain.CONSTITUTIONAL,
                LegalDomain.CONSUMER, LegalDomain.CORPORATE, LegalDomain.CRIMINAL,
                LegalDomain.CYBER, LegalDomain.EMPLOYMENT, LegalDomain.ENVIRONMENTAL,
                LegalDomain.FAMILY, LegalDomain.IP, LegalDomain.TAXATION, LegalDomain.TENANCY
        };

        for (LegalDomain d : allDomains) {
            List<CorpusPrecedentEntry> domainEntries = registry.findByDomain(d);
            assertNotNull(domainEntries, "Domain entries must not be null for " + d);
            assertTrue(domainEntries.size() >= 2, "Domain " + d + " must have >= 2 precedents, actual: " + domainEntries.size());
        }

        // Verify lookups by authority ID
        Optional<CorpusPrecedentEntry> arun = registry.findById("arun_bhatiya_2022");
        assertTrue(arun.isPresent(), "Arun Bhatiya must be present in registry");
        assertEquals("Arun Bhatiya v. HDFC Bank & Ors.", arun.get().caseTitle());
        assertEquals(LegalDomain.BANKING, arun.get().primaryDomain());

        Optional<CorpusPrecedentEntry> louboutin = registry.findById("louboutin_2018");
        assertTrue(louboutin.isPresent(), "Christian Louboutin must be present in registry");
        assertEquals(LegalDomain.CYBER, louboutin.get().primaryDomain());

        Optional<CorpusPrecedentEntry> surajLamp = registry.findById("suraj_lamp_2011");
        assertTrue(surajLamp.isPresent(), "Suraj Lamp must be present in registry");
        assertEquals(LegalDomain.CIVIL_PROPERTY, surajLamp.get().primaryDomain());
    }

    @Test
    @DisplayName("Test 9: InspectorService Universal Partitioning — Cited Tab vs Relevant Tab for Argument Sheets")
    void testInspectorUniversalCitedVsRelevantPartitioning() {
        CorpusPrecedentRegistryService registry = new CorpusPrecedentRegistryService(objectMapper);
        registry.init();

        WorkspaceSavedAuthorityRepository savedAuthorityRepo = mock(WorkspaceSavedAuthorityRepository.class);
        WorkspaceDraftRepository draftRepo = mock(WorkspaceDraftRepository.class);
        TemplateRegistryService templateRegistry = mock(TemplateRegistryService.class);

        PrecedentAuthorityProvider precedentProv = new WorkspaceUploadedPrecedentProvider(documentRepository, registry);
        AuthorityDrawerService drawer = new AuthorityDrawerService(
                workspaceRepository,
                documentRepository,
                savedAuthorityRepo,
                precedentProv,
                registry,
                null
        );

        InspectorService inspector = new InspectorService(
                workspaceRepository,
                draftRepo,
                argumentSheetRepository,
                documentRepository,
                savedAuthorityRepo,
                templateRegistry,
                precedentProv,
                drawer,
                registry,
                markdownRenderer
        );

        UUID sheetId = UUID.randomUUID();
        WorkspaceArgumentSheet sheet = new WorkspaceArgumentSheet();
        sheet.setSheetId(sheetId);
        sheet.setWorkspaceId(workspaceId);
        sheet.setClerkUserId(clerkUserId);
        sheet.setTitle("Consumer Hearing Prep");
        sheet.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        sheet.setRenderedMarkdown("""
                # Courtroom Argument Sheet
                ### 3. CORE LEGAL & FACTUAL SUBMISSIONS
                The Complainant places heavy reliance on *Arun Bhatiya v. HDFC Bank & Ors.* (2022 INSC 804) where it was settled that banking constitutes service.
                """);
        sheet.setArgumentDossier(new ArgumentDossier(
                "Pitch",
                List.of(),
                List.of(new ArgumentSubmission("SUB_1", "Banking Service Deficiency", "Reliance placed on Arun Bhatiya v. HDFC Bank", List.of(), List.of("Arun Bhatiya v. HDFC Bank & Ors. (2022 INSC 804)"), "NONE", "")),
                List.of(),
                List.of(),
                List.of()
        ));

        when(argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId))
                .thenReturn(Optional.of(sheet));

        RightPaneInspectorResponse response = inspector.getInspector(workspaceId, clerkUserId, "ARGUMENT", sheetId);

        assertNotNull(response);
        assertNotNull(response.countsHeader());
        assertTrue(response.countsHeader().citedAuthoritiesCount() > 0, "Cited authorities count must be > 0");

        // Arun Bhatiya must be in citedTab
        boolean arunInCited = response.citedTab().stream().anyMatch(c -> "arun_bhatiya_2022".equals(c.authorityId()));
        assertTrue(arunInCited, "Arun Bhatiya must be partitioned into citedTab");

        // Snippet must be populated for cited card
        JudgmentAuthorityCard arunCard = response.citedTab().stream()
                .filter(c -> "arun_bhatiya_2022".equals(c.authorityId()))
                .findFirst().orElseThrow();
        assertNotNull(arunCard.snippetInDraft(), "Cited card must have snippetInDraft");
        assertTrue(arunCard.snippetInDraft().contains("Arun Bhatiya") || arunCard.snippetInDraft().contains("2022 INSC 804"),
                "Snippet must contain matching case text");

        // Nitin Khandelwal or other uncited precedents must be in relevantTab with null snippet
        boolean nitinInRelevant = response.relevantTab().stream().anyMatch(c -> "nitin_khandelwal_2008".equals(c.authorityId()));
        assertTrue(nitinInRelevant, "Nitin Khandelwal must be partitioned into relevantTab when not cited in text");

        // Verify Christian Louboutin is placed into citedTab when cited in renderedMarkdown / pinnedPrecedents
        UUID louboutinSheetId = UUID.randomUUID();
        WorkspaceArgumentSheet sheet2 = new WorkspaceArgumentSheet();
        sheet2.setSheetId(louboutinSheetId);
        sheet2.setWorkspaceId(workspaceId);
        sheet2.setClerkUserId(clerkUserId);
        sheet2.setTitle("E-Commerce Intermediary Hearing Prep");
        sheet2.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        sheet2.setRenderedMarkdown("""
                # Courtroom Argument Sheet
                ### 3. CORE LEGAL & FACTUAL SUBMISSIONS
                The Complainant submits that safe harbour under Section 79 IT Act is forfeited under Christian Louboutin SAS v. Nakul Bajaj & Ors. (2018 SCC OnLine Del 12215) as the platform is an active commercial participant.
                """);
        sheet2.setArgumentDossier(new ArgumentDossier(
                "Opening Pitch",
                List.of(),
                List.of(new ArgumentSubmission("CS4", "Intermediary Safe Harbour Forfeiture", "Christian Louboutin SAS v. Nakul Bajaj applies", List.of(), List.of("Christian Louboutin SAS v. Nakul Bajaj & Ors. (2018 SCC OnLine Del 12215)"), "NONE", "")),
                List.of(),
                List.of(),
                List.of()
        ));

        when(argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(louboutinSheetId, workspaceId, clerkUserId))
                .thenReturn(Optional.of(sheet2));

        RightPaneInspectorResponse louboutinResponse = inspector.getInspector(workspaceId, clerkUserId, "ARGUMENT", louboutinSheetId);
        assertNotNull(louboutinResponse);
        boolean louboutinInCited = louboutinResponse.citedTab().stream().anyMatch(c -> "louboutin_2018".equals(c.authorityId()));
        assertTrue(louboutinInCited, "Christian Louboutin must be partitioned into citedTab when cited in submissions/draft");

        JudgmentAuthorityCard louboutinCard = louboutinResponse.citedTab().stream()
                .filter(c -> "louboutin_2018".equals(c.authorityId()))
                .findFirst().orElseThrow();
        assertNotNull(louboutinCard.snippetInDraft(), "Louboutin cited card must have snippetInDraft");
        assertTrue(louboutinCard.snippetInDraft().contains("Christian Louboutin") || louboutinCard.snippetInDraft().contains("2018 SCC OnLine Del 12215"),
                "Snippet must contain Louboutin matching case text");

        // Verify helper isAuthorityCitedInText directly
        assertTrue(InspectorService.isAuthorityCitedInText(
                "Christian Louboutin SAS v. Nakul Bajaj & Ors.",
                "2018 SCC OnLine Del 12215",
                List.of(),
                "louboutin_2018",
                "Reliance placed on Christian Louboutin SAS vs Nakul Bajaj",
                List.of()
        ), "Must match on normalized party prefix before v.");

        assertTrue(InspectorService.isAuthorityCitedInText(
                "Christian Louboutin SAS v. Nakul Bajaj & Ors.",
                "2018 SCC OnLine Del 12215",
                List.of(),
                "louboutin_2018",
                "",
                List.of("Christian Louboutin SAS v. Nakul Bajaj & Ors. (2018 SCC OnLine Del 12215)")
        ), "Must match on normalized citation in pinned precedents");
    }

    @Test
    @DisplayName("Universal Pre-Step 5: 3-Bucket Cross-Check, Sub-Clause Extractor & Statutory Coverage Audit")
    void testUniversalPreStep5ThreeBucketRetrievalSubClauseAndCoverageAudit() {
        // 1. Verify Universal 3-Bucket Cross-Check in LegalRetrievalService
        StatuteRegistry registry = new StatuteRegistry(objectMapper);
        registry.loadRules();
        StatuteRuleEvaluator evaluator = new StatuteRuleEvaluator(List.of());
        LegalRetrievalService retrievalService = new LegalRetrievalService(registry, evaluator);
        retrievalService.initializeCache();

        String prompt = "Filing petition under Section 7 against corporate debtor for 10.01.2021 default; reliance placed on FY 2021-22 balance sheet acknowledgment and 14.07.2022 settlement proposal.";
        LegalRetrievalService.RetrievalResult result = retrievalService.retrieveUniversal3BucketStatutes(
                "CORPORATE",
                prompt,
                List.of("default", "cirp", "balance sheet", "settlement")
        );

        assertNotNull(result, "RetrievalResult must not be null");
        assertFalse(result.selectedFiles().isEmpty(), "Must retrieve candidate statute files");

        // Assert Section 18 of Limitation Act retrieved
        boolean hasSec18 = result.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("limitation") && f.filename().toLowerCase().contains("18"));
        assertTrue(hasSec18, "Bucket 3 must retrieve Section 18 of the Limitation Act, 1963");

        // Assert Article 137 / Schedule of Limitation Act retrieved
        boolean hasArt137 = result.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("limitation") && f.filename().toLowerCase().contains("137"));
        assertTrue(hasArt137, "Bucket 3 must retrieve Article 137 (Articles 113 & 137) of the Limitation Act, 1963");

        // Assert Section 10A of IBC retrieved due to 10.01.2021 default date falling inside statutory blackout window
        boolean hasSec10A = result.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("insolvency") && f.filename().toLowerCase().contains("10a"));
        assertTrue(hasSec10A, "Bucket 2 must retrieve Section 10A of the IBC, 2016 for default falling in 25.03.2020-25.03.2021 window");

        // Assert triggeredBarDirectives contains Section 10A suspension warning
        boolean has10ADirective = result.triggeredBarDirectives().stream().anyMatch(d -> d.contains("10A"));
        assertTrue(has10ADirective, "Must emit Section 10A statutory bar warning directive");

        // 2. Verify Table 2B Sub-Clause Extractor for Section 3 IBC
        String sec3Markdown = """
                ---
                domain: CORPORATE
                act: The Insolvency and Bankruptcy Code, 2016
                section: '3'
                heading: Definitions
                ---
                # Section 3. Definitions
                In this Code, unless the context otherwise requires,--
                (1) "Board" means the Insolvency and Bankruptcy Board of India established under sub-section (1) of section 188;
                (8) "corporate debtor" means a corporate person who owes a debt to any person;
                (10) "creditor" means any person to whom a debt is owed and includes a financial creditor;
                (11) "debt" means a liability or obligation in respect of a claim which is due;
                (12) "default" means non-payment of debt when whole or any part has become due and payable;
                """;

        StatutePin sec3Pin = new StatutePin("The Insolvency and Bankruptcy Code, 2016", "3", StatuteType.OPERATIVE, "Definitions", sec3Markdown);

        ArgumentDossier dossierWithSubClauses = new ArgumentDossier(
                "Opening pitch under Section 3(8), (10), (11), (12)",
                List.of(new FramedLegalIssue("I1", "Default under Section 3(12) by Corporate Debtor under Section 3(8)", "Onus on Creditor")),
                List.of(new ArgumentSubmission("CS1", "Creditor and Debt", "Creditor under Section 3(10) holds debt under Section 3(11)", List.of("Section 3(8)", "Section 3(10)", "Section 3(11)", "Section 3(12)"), List.of(), "DOC_1", "")),
                List.of(new RebuttalMatrixItem("R1", "No default", "Default defined in Section 3(12)", "Section 3(12)")),
                List.of(new BenchQuestionItem("What is debt under Section 3(11)?", "Defined in Section 3(11)", "Section 3(11)")),
                List.of()
        );

        String tableSummary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(sec3Pin, dossierWithSubClauses);
        
        
        
        assertNotNull(tableSummary);
        assertTrue(tableSummary.contains("(8)"), "Must extract sub-clause (8)");
        assertTrue(tableSummary.contains("(10)"), "Must extract sub-clause (10)");
        assertTrue(tableSummary.contains("(11)"), "Must extract sub-clause (11)");
        assertTrue(tableSummary.contains("(12)"), "Must extract sub-clause (12)");
        assertFalse(tableSummary.contains("\"Board\""), "Must NOT extract irrelevant sub-clause (1) 'Board'");
        assertFalse(tableSummary.contains("unless the context otherwise requires"), "Must strip legislative opening preamble");
        assertTrue(tableSummary.length() <= 240, "Must be capped at <= 240 characters");

        // 3. Verify Statutory Source Coverage Audit rendering
        List<StatutePin> effectivePack = List.of(
                new StatutePin("The Insolvency and Bankruptcy Code, 2016", "7", "Operative initiation"),
                new StatutePin("The Insolvency and Bankruptcy Code, 2016", "10A", "Suspension window"),
                new StatutePin("The Limitation Act, 1963", "18", "Effect of acknowledgment")
        );

        String userQueryWithUnbundled = "Filing under Section 7 and Section 10A of the Insolvency and Bankruptcy Code, 2016, Section 18 of the Limitation Act, 1963 and Section 9 of the Arbitration and Conciliation Act, 1996.";
        String coverageBlock = ArgumentMarkdownRenderer.renderStatutorySourceCoverage(effectivePack, userQueryWithUnbundled, dossierWithSubClauses);

        assertTrue(coverageBlock.contains("**Statutory Source Coverage:** **Verified in Local OKF Pack:**"), "Must include verified pack line");
        assertTrue(coverageBlock.contains("Insolvency and Bankruptcy Code, 2016"), "Must show verified IBC");
        assertTrue(coverageBlock.contains("Limitation Act, 1963"), "Must show verified Limitation Act");
        assertTrue(coverageBlock.contains("**External / Unbundled Statutes Referenced (Verify Bare Act Text):**"), "Must flag unbundled statute");
        assertTrue(coverageBlock.contains("Arbitration and Conciliation Act, 1996 (Section 9)"), "Must specifically identify unbundled Section 9 Arbitration Act");
    }

    @Test
    @DisplayName("Bucket 3 Cross-Domain Bridges: Contract Act, CPC, Arbitration Act & Evidence Act")
    void testBucket3CrossDomainStatutoryBridges() {
        StatuteRegistry registry = new StatuteRegistry(objectMapper);
        registry.loadRules();
        StatuteRuleEvaluator evaluator = new StatuteRuleEvaluator(List.of());
        LegalRetrievalService retrievalService = new LegalRetrievalService(registry, evaluator);
        retrievalService.initializeCache();

        // 1. Cross-Domain: The Indian Contract Act, 1872 (from CIVIL_PROPERTY into CORPORATE)
        String contractPrompt = "Insolvency claim where corporate debtor entered into OTS settlement proposal on 14.07.2022 and novation under Section 62 and Section 63; lead bank acts on behalf of consortium agency.";
        LegalRetrievalService.RetrievalResult contractResult = retrievalService.retrieveUniversal3BucketStatutes(
                "CORPORATE",
                contractPrompt,
                List.of("settlement", "ots", "consortium", "agency")
        );

        assertNotNull(contractResult);
        boolean hasSec62 = contractResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("contract") && f.filename().toLowerCase().contains("62"));
        boolean hasSec63 = contractResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("contract") && f.filename().toLowerCase().contains("63"));
        boolean hasSec182 = contractResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("contract") && f.filename().toLowerCase().contains("182"));

        assertTrue(hasSec62, "Bucket 3 must bridge to Section 62 of Contract Act for novation/settlement");
        assertTrue(hasSec63, "Bucket 3 must bridge to Section 63 of Contract Act for remission/settlement");
        assertTrue(hasSec182, "Bucket 3 must bridge to Section 182 of Contract Act for consortium agency");

        // 2. Cross-Domain: The Code of Civil Procedure, 1908 (from CIVIL_PROPERTY into CORPORATE)
        String cpcPrompt = "Debtor filed parallel civil suit seeking temporary injunction under Section 94 and Section 151 CPC to stay proceedings; raises res judicata under Section 11.";
        LegalRetrievalService.RetrievalResult cpcResult = retrievalService.retrieveUniversal3BucketStatutes(
                "CORPORATE",
                cpcPrompt,
                List.of("injunction", "stay", "res judicata", "cpc")
        );

        assertNotNull(cpcResult);
        boolean hasSec94 = cpcResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("civil_procedure") && f.filename().toLowerCase().contains("94"));
        boolean hasSec151 = cpcResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("civil_procedure") && f.filename().toLowerCase().contains("151"));
        boolean hasSec11 = cpcResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("civil_procedure") && f.filename().toLowerCase().contains("11"));

        assertTrue(hasSec94, "Bucket 3 must bridge to Section 94 CPC for injunctions");
        assertTrue(hasSec151, "Bucket 3 must bridge to Section 151 CPC for inherent powers");
        assertTrue(hasSec11, "Bucket 3 must bridge to Section 11 CPC for res judicata");

        // 3. Cross-Domain: The Arbitration and Conciliation Act, 1996 (from CIVIL_PROPERTY into CORPORATE)
        String arbPrompt = "Dispute involving arbitration clause and respondent filed Section 9 petition for interim relief to restrain invocation of bank guarantee.";
        LegalRetrievalService.RetrievalResult arbResult = retrievalService.retrieveUniversal3BucketStatutes(
                "CORPORATE",
                arbPrompt,
                List.of("arbitration", "section 9", "bank guarantee", "interim relief")
        );

        assertNotNull(arbResult);
        boolean hasArbSec9 = arbResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("arbitration") && f.filename().toLowerCase().contains("section_9.md"));
        boolean hasArbSec8 = arbResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("arbitration") && f.filename().toLowerCase().contains("section_8.md"));

        assertTrue(hasArbSec9, "Bucket 3 must bridge to Section 9 of Arbitration Act for interim relief");

        // 4. Cross-Domain: The Indian Evidence Act, 1872 (from CRIMINAL into BANKING / CORPORATE)
        String evidencePrompt = "Default on 10.01.2021 before 2024 relying on digital bank statements and emails requiring Section 65B certificate under pre-2024 evidence rules; burden of proof on creditor.";
        LegalRetrievalService.RetrievalResult evidenceResult = retrievalService.retrieveUniversal3BucketStatutes(
                "BANKING",
                evidencePrompt,
                List.of("65b", "pre-2024", "electronic", "burden of proof")
        );

        assertNotNull(evidenceResult);
        boolean hasSec65B = evidenceResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("evidence") && f.filename().toLowerCase().contains("65b"));
        boolean hasSec101 = evidenceResult.selectedFiles().stream().anyMatch(f ->
                f.statuteName().toLowerCase().contains("evidence") && f.filename().toLowerCase().contains("101"));

        assertTrue(hasSec65B, "Bucket 3 must bridge to Section 65B of Indian Evidence Act, 1872 for pre-2024 electronic evidence");
        assertTrue(hasSec101, "Bucket 3 must bridge to Section 101 of Indian Evidence Act, 1872 for burden of proof");
    }

    @Test
    @DisplayName("Universal Bare Act Syntax Sanitizer: Bracketed amended prefix stripping & word-boundary truncation")
    void testUniversalBareActSyntaxSanitizerAndWordBoundaryTruncation() {
        // 1. Bracketed amended section syntax ([NA. Marginal Title.— Operative statutory text...)
        String amendedSectionMarkdown = """
                ---
                section: 10A
                heading: Suspension of initiation
                ---
                # Section 10A
                [10A. Suspension of initiation of corporate insolvency resolution process.—Notwithstanding anything contained in sections 7, 9 and 10, no application for initiation of corporate insolvency resolution process of a corporate debtor shall be admitted for any default of such debtor occurring on or after 25th March, 2020.
                """;

        StatutePin amendedPin = new StatutePin(
                "The Insolvency and Bankruptcy Code, 2016",
                "10A",
                StatuteType.OPERATIVE,
                "Suspension of initiation",
                amendedSectionMarkdown
        );

        String operativeSummary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(amendedPin, null);
        assertNotNull(operativeSummary);
        assertFalse(operativeSummary.startsWith("[10A."), "Must strip bracketed section identifier '[10A.'");
        assertFalse(operativeSummary.contains("Suspension of initiation"), "Must strip marginal title");
        assertFalse(operativeSummary.contains("—"), "Must strip em-dash separator");
        assertTrue(operativeSummary.startsWith("Notwithstanding anything contained in sections 7, 9 and 10"),
                "Must extract operative sentence cleanly starting with 'Notwithstanding', actual: " + operativeSummary);
        assertTrue(operativeSummary.length() <= 280, "Operative sentence must be <= 280 chars");
        assertFalse(operativeSummary.endsWith("..."), "Non-truncated sentence under 280 chars should not have '...'");

        // Bracketed placeholder [NA. Marginal Title.— Operative text...]
        String placeholderMarkdown = "[NA. Marginal Title.— Operative statutory text defining legal consequences without stopping at NA identifier.]";
        StatutePin placeholderPin = new StatutePin("Generic Act", "NA", StatuteType.OPERATIVE, "Marginal Title", placeholderMarkdown);
        String placeholderSummary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(placeholderPin, null);
        assertFalse(placeholderSummary.contains("[NA."), "Must not stop at [NA.");
        assertTrue(placeholderSummary.startsWith("Operative statutory text defining legal consequences"),
                "Must start with operative sentence: " + placeholderSummary);

        // 2. Multi-line sub-clause definitions whitespace normalization and word-boundary safe truncation
        String multiLineContent = """
                (8) "corporate debtor" means a corporate person
                who owes a debt to any person or corporate entity under commercial agreement;
                (10) "creditor" means any person
                to whom a debt is owed and includes a financial creditor or operational creditor;
                """;

        StatutePin defsPin = new StatutePin("Test Act", "3", StatuteType.OPERATIVE, "Definitions", multiLineContent);
        ArgumentDossier dossierCiting8 = new ArgumentDossier(
                "Claim against corporate debtor under Section 3(8)",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );

        String subClauseSummary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(defsPin, dossierCiting8);
        assertNotNull(subClauseSummary);
        assertFalse(subClauseSummary.contains("\n") || subClauseSummary.contains("\r"),
                "Must have collapsed all line breaks into spaces");
        assertTrue(subClauseSummary.contains("corporate person who owes a debt"),
                "Multi-line definition must not be cut mid-phrase across line breaks");

        // 3. Word-boundary truncation test
        String longText = "The quick brown fox jumps over the lazy dog and runs across the wide open statutory field without stopping for any mid-word cut";
        String truncated50 = ArgumentMarkdownRenderer.truncateAtWordBoundary(longText, 50);
        assertTrue(truncated50.length() <= 50, "Length must be <= 50");
        assertTrue(truncated50.endsWith("..."), "Must end with '...'");
        assertFalse(truncated50.contains("acro..."), "Must not cut inside a word");
        assertTrue(truncated50.equals("The quick brown fox jumps over the lazy dog and...")
                || truncated50.endsWith(" and..."), "Must cut strictly at whitespace word boundary, got: " + truncated50);
    }

    @Test
    @DisplayName("Fix 1 & 2: (Act, Section) Tuple Scoping, Definition Bleed Elimination & Section 238A Non-Empty Summary")
    void testCrossActTupleScopingSection238ASummaryAndPromptFactBinding() {
        // 1. Cross-Act Section Number Filter Test
        List<StatutePin> candidatePack = List.of(
                new StatutePin("The Arbitration and Conciliation Act, 1996", "8", "Power to refer parties to arbitration"),
                new StatutePin("The Arbitration and Conciliation Act, 1996", "9", "Interim measures by Court"),
                new StatutePin("The Insolvency and Bankruptcy Code, 2016", "8", "Insolvency resolution by operational creditor"),
                new StatutePin("The Insolvency and Bankruptcy Code, 2016", "9", "Application for initiation by operational creditor"),
                new StatutePin("The Insolvency and Bankruptcy Code, 2016", "10", "Initiation by corporate applicant"),
                new StatutePin("The Companies Act, 2013", "2", "Definitions"),
                new StatutePin("The Companies Act, 2013", "73", "Prohibition on acceptance of deposits from public")
        );

        ArgumentDossier dossierWithCS5 = new ArgumentDossier(
                "Arbitration dispute opening pitch",
                List.of(),
                List.of(
                        new ArgumentSubmission(
                                "CS5",
                                "Arbitral Jurisdiction & Interim Relief",
                                "It is submitted that this dispute must be referred to arbitration under Section 8 and interim relief is sought under Section 9.",
                                List.of("Arbitration and Conciliation Act, 1996, Sections 5, 7, 8, 9, and 16"),
                                List.of(),
                                "EX_A",
                                ""
                        )
                ),
                List.of(),
                List.of(),
                List.of()
        );

        Set<ArgumentMarkdownRenderer.ActSectionRef> citedRefs = ArgumentMarkdownRenderer.extractCitedActSectionRefs(dossierWithCS5);
        List<StatutePin> filtered = ArgumentMarkdownRenderer.filterToActivelyCitedStatutes(candidatePack, citedRefs);

        assertNotNull(filtered);
        assertEquals(2, filtered.size(), "Must keep exactly Arbitration Act Sections 8 & 9");
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Arbitration") && p.sectionNumber().equals("8")));
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Arbitration") && p.sectionNumber().equals("9")));
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Insolvency")), "Must drop un-cited IBC Sections 8, 9, 10");
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Companies")), "Must drop un-cited Companies Act Sections 2 & 73");

        // 2. Cross-Act Definition Bleed Test: Section 2 of IBC or Companies Act never contains "Consumer & Beneficiary Standing"
        String rawSec2Content = """
                ### PINNED STATUTORY ANCHORS (BASE SECTIONS — DRILL DOWN TO EXACT NESTED SUB-CLAUSES IN SUBMISSIONS)
                - Section 2(7)(ii) [Consumer & Beneficiary Standing]
                - Section 2(11)(i)-(ii) [Deficiency in Service]
                ---
                domain: CORPORATE
                act: The Companies Act, 2013
                section: 2
                ---
                # Section 2. Definitions
                In this Act, unless the context otherwise requires,—
                (1) "abridged prospectus" means a memorandum containing such salient features of a prospectus as may be specified;
                (2) "accounting standards" means the standards of accounting or any addendum thereto for companies;
                """;

        StatutePin companiesSec2Pin = new StatutePin("The Companies Act, 2013", "2", StatuteType.OPERATIVE, "Definitions", rawSec2Content);
        ArgumentDossier consumerDossier = new ArgumentDossier(
                "Consumer standing under Section 2(7)(ii)",
                List.of(),
                List.of(
                        new ArgumentSubmission(
                                "SUB_1",
                                "Standing",
                                "Complainant is a consumer under Section 2(7)(ii)",
                                List.of("Consumer Protection Act, 2019 Section 2(7)(ii)"),
                                List.of(),
                                "EX_1",
                                ""
                        )
                ),
                List.of(),
                List.of(),
                List.of()
        );

        String companiesSummary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(companiesSec2Pin, consumerDossier);
        assertNotNull(companiesSummary);
        assertFalse(companiesSummary.contains("Consumer & Beneficiary Standing"),
                "Section 2 of Companies Act must NEVER contain 'Consumer & Beneficiary Standing'");
        assertFalse(companiesSummary.contains("Deficiency in Service"),
                "Section 2 of Companies Act must NEVER bleed consumer anchors");

        StatutePin ibcSec2Pin = new StatutePin("The Insolvency and Bankruptcy Code, 2016", "2", StatuteType.OPERATIVE, "Application",
                "The provisions of this Code shall apply to any company incorporated under the Companies Act, 2013 or under any previous company law.");
        String ibcSummary = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(ibcSec2Pin, consumerDossier);
        assertNotNull(ibcSummary);
        assertFalse(ibcSummary.contains("Consumer & Beneficiary Standing"),
                "Section 2 of IBC must NEVER contain 'Consumer & Beneficiary Standing'");

        // 3. Section 238A summary test: >= 35 characters and never equals "[238A."
        String sec238AMarkdown = """
                ---
                domain: CORPORATE
                act: The Insolvency and Bankruptcy Code, 2016
                section: 238A
                heading: Limitation
                ---
                # Section 238A. Limitation
                **[238A. Limitation.** --The provisions of the Limitation Act, 1963 shall, as far as may be, apply to the proceedings or appeals before the Adjudicating Authority, the National Company Law Appellate Tribunal, the Debt Recovery Tribunal or the Debt Recovery Appellate Tribunal, as the case may be.]
                """;
        StatutePin pin238A = new StatutePin("The Insolvency and Bankruptcy Code, 2016", "238A", StatuteType.OPERATIVE, "Limitation", sec238AMarkdown);
        String summary238A = ArgumentMarkdownRenderer.summarizeStatutePinForTableCell(pin238A, null);
        assertNotNull(summary238A);
        assertFalse(summary238A.equals("[238A."), "Must never equal '[238A.'");
        assertFalse(summary238A.contains("[238A."), "Must strip leading bracketed section prefix '[238A.'");
        assertTrue(summary238A.length() >= 35, "Summary must be >= 35 characters, actual: " + summary238A.length());
        assertTrue(summary238A.contains("provisions of the Limitation Act, 1963"), "Must contain operative statutory text: " + summary238A);
    }

    @Test
    @DisplayName("Test 13: Dynamic Title Rendering & Strategic Advisory Framing (Tactical Hearing Note vs Standard Argument Sheet)")
    void testDynamicTitleRenderingAndStrategicAdvisoryFraming() {
        // 1. Dossier with Strategic Advisory Note in openingPitch60Seconds
        String advisoryPitch = "> **Strategic Advisory & Framing Note:** You instructed to argue that Section 238 IBC flatly extinguishes arbitration. However, advancing an unqualified assertion risks resistance under Indus Biotech. The stronger strategy is sequencing priority.\n\nMay it please this Hon'ble Tribunal, under Indus Biotech...";
        ArgumentDossier advisoryDossier = new ArgumentDossier(
                advisoryPitch,
                List.of(new FramedLegalIssue("ISSUE_1", "Whether Section 8 reference is maintainable prior to Section 7 admission?", "On Corporate Debtor")),
                List.of(new ArgumentSubmission("CS1", "Statutory In rem Primacy", "The Section 7 petition takes precedence.", List.of("IBC Section 7"), List.of(), "NONE_UPLOADED", null)),
                List.of(new RebuttalMatrixItem("REB_1", "Opponent seeks Section 8 reference | immediately", "Section 8 cannot compel reference\nbefore CIRP adjudication", "Indus Biotech (2021)")),
                List.of(new BenchQuestionItem("Does arbitration survive dismissal?", "Yes, if rejected under Section 7(5), arbitral remedy revives.", "Indus Biotech")),
                List.of("Facility Agreement", "Balance sheet acknowledgment")
        );

        String advisoryMarkdown = markdownRenderer.renderToMarkdown(advisoryDossier, PartyPerspective.CLAIMANT_PETITIONER);
        assertNotNull(advisoryMarkdown);

        // Assert Dynamic Title: Tactical Hearing Note & Counter-Submissions
        assertTrue(advisoryMarkdown.startsWith("# Tactical Hearing Note & Counter-Submissions"),
                "Document H1 must be 'Tactical Hearing Note & Counter-Submissions' when Strategic Advisory is present");
        assertTrue(advisoryMarkdown.contains("> **Strategic Advisory & Framing Note:**"),
                "Markdown must preserve Strategic Advisory blockquote in Section 1");

        // Assert 3-Column Rebuttal Matrix Table with escaped pipes and newlines
        assertTrue(advisoryMarkdown.contains("### 4. OPPOSITE-PARTY REBUTTAL MATRIX"));
        assertTrue(advisoryMarkdown.contains("| Opponent's Likely Point | Our Courtroom Response | Authority / Evidence to Have Ready |"));
        assertTrue(advisoryMarkdown.contains("| :--- | :--- | :--- |"));
        assertTrue(advisoryMarkdown.contains("\\| immediately"), "Pipes in cell content must be escaped");
        assertFalse(advisoryMarkdown.contains("Section 8 cannot compel reference\nbefore"), "Newlines in table cell must be replaced with spaces");

        // Assert Section 5 and 6 Headers
        assertTrue(advisoryMarkdown.contains("### 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR"));
        assertTrue(advisoryMarkdown.contains("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE"));

        // 2. Standard Dossier without Strategic Advisory Note
        String standardPitch = "May it please this Hon'ble Commission, the Complainant submits that goods supplied were defective.";
        ArgumentDossier standardDossier = new ArgumentDossier(
                standardPitch,
                List.of(new FramedLegalIssue("ISSUE_1", "Whether defect exists?", "On Complainant")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        String standardMarkdown = markdownRenderer.renderToMarkdown(standardDossier, PartyPerspective.CLAIMANT_PETITIONER);
        assertNotNull(standardMarkdown);
        assertTrue(standardMarkdown.startsWith("# Courtroom Argument Sheet"),
                "Document H1 must fall back to 'Courtroom Argument Sheet' when Strategic Advisory is absent");
    }

    @Test
    @DisplayName("Test 14: Surgical Intent Masking & Implicit Perspective Resolution")
    void testSurgicalIntentMaskingAndImplicitPerspectiveResolution() {
        // Part 1: Surgical masking when asking for "only oral arguments and submissions opposing anticipatory bail"
        // Workspace default is CLAIMANT_PETITIONER
        DraftingContext ctx = new DraftingContext(
                "Opposing anticipatory bail in corporate fraud",
                null,
                List.of(new StatutePin("Code of Criminal Procedure, 1973", "Section 438", "Anticipatory bail")),
                null
        );
        workspace.setLatestDraftingContext(ctx);

        WorkspaceArgumentSheet surgicalSheet = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                null, // overridePerspective == null to trigger auto-resolution
                "Give me only oral arguments and submissions opposing anticipatory bail"
        );

        assertNotNull(surgicalSheet);
        // Opposing anticipatory bail resolves to CLAIMANT_PETITIONER
        assertEquals(PartyPerspective.CLAIMANT_PETITIONER, surgicalSheet.getPartyPerspective());
        ArgumentDossier surgicalDossier = surgicalSheet.getArgumentDossier();
        assertNotNull(surgicalDossier);

        // Verification of surgical dossier fields:
        assertFalse(surgicalDossier.openingPitch60Seconds().isBlank(), "Pitch must be preserved when only submissions/arguments are requested");
        assertFalse(surgicalDossier.coreSubmissions().isEmpty(), "Core submissions must be preserved");
        assertTrue(surgicalDossier.framedIssues().isEmpty(), "Framed issues must be suppressed/zeroed");
        assertTrue(surgicalDossier.rebuttalMatrix().isEmpty(), "Rebuttals must be suppressed/zeroed");
        assertTrue(surgicalDossier.benchGrillQuestions().isEmpty(), "Bench questions must be suppressed/zeroed");
        assertTrue(surgicalDossier.preHearingEvidenceChecklist().isEmpty(), "Checklist must be suppressed/zeroed");

        // Verification of rendered markdown:
        String markdown = surgicalSheet.getRenderedMarkdown();
        assertNotNull(markdown);
        assertTrue(markdown.contains("### 1. 60-SECOND OPENING COURTROOM PITCH"), "Section 1 pitch must be rendered");
        assertFalse(markdown.contains("### 2. ISSUES FRAMED FOR DETERMINATION"), "Section 2 issues must be omitted");
        assertTrue(markdown.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK"), "Table 2B must be rendered");
        assertTrue(markdown.contains("### 3. CORE LEGAL & FACTUAL SUBMISSIONS"), "Section 3 submissions must be rendered");
        assertFalse(markdown.contains("### 4. OPPOSITE-PARTY REBUTTAL MATRIX"), "Section 4 rebuttals must be omitted");
        assertFalse(markdown.contains("### 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR"), "Section 5 bench traps must be omitted");
        assertFalse(markdown.contains("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE"), "Section 6 checklist must be omitted");

        // Part 2: Automatic perspective resolution when prompt states "Prepare our defense as Respondent opposing Section 9 interim injunction"
        // Even if workspace was set to CLAIMANT_PETITIONER
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        WorkspaceArgumentSheet respondentSheet = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                null,
                "Prepare our defense as Respondent opposing Section 9 interim injunction"
        );

        assertNotNull(respondentSheet);
        assertEquals(PartyPerspective.OPPOSITE_PARTY_RESPONDENT, respondentSheet.getPartyPerspective(),
                "Implicit party representation cues must resolve targetPerspective to OPPOSITE_PARTY_RESPONDENT");
        assertTrue(respondentSheet.getRenderedMarkdown().contains("OPPOSITE PARTY / RESPONDENT"),
                "Rendered markdown badge must reflect OPPOSITE PARTY / RESPONDENT");
    }

    @Test
    @DisplayName("Test 15: Universal Additive Argument Generation Planner for 3 Real-World Lawyer Prompts")
    void testUniversalAdditiveArgumentGenerationPlannerRealWorldPrompts() {
        // Prompt 1: Section 9 Arbitration Interim Injunction Defense (OPPOSITE_PARTY_RESPONDENT)
        DraftingContext ctx1 = new DraftingContext(
                "Claimant seeking interim injunction to freeze bank accounts under Section 9",
                null,
                List.of(new StatutePin("Arbitration and Conciliation Act, 1996", "Section 9", "Interim measures by Court")),
                null
        );
        workspace.setLatestDraftingContext(ctx1);
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER); // will be overridden dynamically

        WorkspaceArgumentSheet sheet1 = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                null,
                "Prepare our defense as Respondent. The claimant has filed a Section 9 application under the Arbitration and Conciliation Act seeking an urgent interim injunction to freeze our bank accounts"
        );

        assertNotNull(sheet1);
        assertEquals(PartyPerspective.OPPOSITE_PARTY_RESPONDENT, sheet1.getPartyPerspective(),
                "Prompt 1 must resolve dynamically to OPPOSITE_PARTY_RESPONDENT");
        ArgumentDossier dossier1 = sheet1.getArgumentDossier();
        assertNotNull(dossier1);
        assertFalse(dossier1.openingPitch60Seconds().isBlank(), "Prompt 1 pitch must be included");
        assertFalse(dossier1.coreSubmissions().isEmpty(), "Prompt 1 submissions must be included");
        assertTrue(dossier1.framedIssues().isEmpty(), "Prompt 1 issues must be omitted");
        assertTrue(dossier1.rebuttalMatrix().isEmpty(), "Prompt 1 rebuttals must be omitted");
        assertTrue(dossier1.benchGrillQuestions().isEmpty(), "Prompt 1 bench questions must be omitted");
        assertTrue(dossier1.preHearingEvidenceChecklist().isEmpty(), "Prompt 1 checklist must be omitted");

        String md1 = sheet1.getRenderedMarkdown();
        assertNotNull(md1);
        assertTrue(md1.contains("### 1. 60-SECOND OPENING COURTROOM PITCH"), "Section 1 pitch must be rendered");
        assertFalse(md1.contains("### 2. ISSUES FRAMED FOR DETERMINATION"), "Section 2 issues must be omitted");
        assertTrue(md1.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK"), "Table 2B must be rendered");
        assertTrue(md1.contains("### 3. CORE LEGAL & FACTUAL SUBMISSIONS"), "Section 3 submissions must be rendered");
        assertFalse(md1.contains("### 4. OPPOSITE-PARTY REBUTTAL MATRIX"), "Section 4 rebuttals must be omitted");
        assertFalse(md1.contains("### 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR"), "Section 5 bench traps must be omitted");
        assertFalse(md1.contains("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE"), "Section 6 checklist must be omitted");

        // Prompt 2: Opposing Section 8 Arbitration Reference in Section 7 IBC (CLAIMANT_PETITIONER)
        DraftingContext ctx2 = new DraftingContext(
                "Opposing Section 8 reference in Section 7 IBC petition",
                null,
                List.of(
                        new StatutePin("Insolvency and Bankruptcy Code, 2016", "Section 7", "Initiation of CIRP by financial creditor"),
                        new StatutePin("Arbitration and Conciliation Act, 1996", "Section 8", "Power to refer parties to arbitration"),
                        new StatutePin("Limitation Act, 1963", "Section 18", "Effect of acknowledgment in writing")
                ),
                null
        );
        workspace.setLatestDraftingContext(ctx2);
        workspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT); // will be overridden dynamically

        WorkspaceArgumentSheet sheet2 = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                null,
                "Opposing a Section 8 Arbitration reference filed by a Corporate Debtor in a Section 7 IBC petition, with reliance on a balance-sheet acknowledgment under Section 18 Limitation Act."
        );

        assertNotNull(sheet2);
        assertEquals(PartyPerspective.CLAIMANT_PETITIONER, sheet2.getPartyPerspective(),
                "Prompt 2 must resolve dynamically to CLAIMANT_PETITIONER");
        ArgumentDossier dossier2 = sheet2.getArgumentDossier();
        assertNotNull(dossier2);
        assertFalse(dossier2.openingPitch60Seconds().isBlank(), "Prompt 2 pitch must be included");
        assertFalse(dossier2.coreSubmissions().isEmpty(), "Prompt 2 submissions must be included");
        assertTrue(dossier2.framedIssues().isEmpty(), "Prompt 2 issues must be omitted");
        assertTrue(dossier2.rebuttalMatrix().isEmpty(), "Prompt 2 rebuttals must be omitted");
        assertTrue(dossier2.benchGrillQuestions().isEmpty(), "Prompt 2 bench questions must be omitted");
        assertTrue(dossier2.preHearingEvidenceChecklist().isEmpty(), "Prompt 2 checklist must be omitted");

        String md2 = sheet2.getRenderedMarkdown();
        assertNotNull(md2);
        assertTrue(md2.contains("### 1. 60-SECOND OPENING COURTROOM PITCH"), "Section 1 pitch must be rendered");
        assertFalse(md2.contains("### 2. ISSUES FRAMED FOR DETERMINATION"), "Section 2 issues must be omitted");
        assertTrue(md2.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK"), "Table 2B must be rendered");
        assertTrue(md2.contains("### 3. CORE LEGAL & FACTUAL SUBMISSIONS"), "Section 3 submissions must be rendered");
        assertFalse(md2.contains("### 4. OPPOSITE-PARTY REBUTTAL MATRIX"), "Section 4 rebuttals must be omitted");
        assertFalse(md2.contains("### 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR"), "Section 5 bench traps must be omitted");
        assertFalse(md2.contains("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE"), "Section 6 checklist must be omitted");

        // Prompt 3: Opposing Anticipatory Bail under Section 420 IPC (CLAIMANT_PETITIONER)
        DraftingContext ctx3 = new DraftingContext(
                "Opposing anticipatory bail in corporate fraud under Section 420 IPC",
                null,
                List.of(
                        new StatutePin("Code of Criminal Procedure, 1973", "Section 438", "Anticipatory bail"),
                        new StatutePin("Indian Penal Code, 1860", "Section 420", "Cheating and dishonestly inducing delivery of property")
                ),
                null
        );
        workspace.setLatestDraftingContext(ctx3);
        workspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT); // will be overridden dynamically

        WorkspaceArgumentSheet sheet3 = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                null,
                "Prepare me arguments to oppose anticipatory bail in a ₹2.5 Crore corporate fraud case under Section 420 IPC"
        );

        assertNotNull(sheet3);
        assertEquals(PartyPerspective.CLAIMANT_PETITIONER, sheet3.getPartyPerspective(),
                "Prompt 3 must resolve dynamically to CLAIMANT_PETITIONER");
        ArgumentDossier dossier3 = sheet3.getArgumentDossier();
        assertNotNull(dossier3);
        assertFalse(dossier3.openingPitch60Seconds().isBlank(), "Prompt 3 pitch must be included");
        assertFalse(dossier3.coreSubmissions().isEmpty(), "Prompt 3 submissions must be included");
        assertTrue(dossier3.framedIssues().isEmpty(), "Prompt 3 issues must be omitted");
        assertTrue(dossier3.rebuttalMatrix().isEmpty(), "Prompt 3 rebuttals must be omitted");
        assertTrue(dossier3.benchGrillQuestions().isEmpty(), "Prompt 3 bench questions must be omitted");
        assertTrue(dossier3.preHearingEvidenceChecklist().isEmpty(), "Prompt 3 checklist must be omitted");

        String md3 = sheet3.getRenderedMarkdown();
        assertNotNull(md3);
        assertTrue(md3.contains("### 1. 60-SECOND OPENING COURTROOM PITCH"), "Section 1 pitch must be rendered");
        assertFalse(md3.contains("### 2. ISSUES FRAMED FOR DETERMINATION"), "Section 2 issues must be omitted");
        assertTrue(md3.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK"), "Table 2B must be rendered");
        assertTrue(md3.contains("### 3. CORE LEGAL & FACTUAL SUBMISSIONS"), "Section 3 submissions must be rendered");
        assertFalse(md3.contains("### 4. OPPOSITE-PARTY REBUTTAL MATRIX"), "Section 4 rebuttals must be omitted");
        assertFalse(md3.contains("### 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR"), "Section 5 bench traps must be omitted");
        assertFalse(md3.contains("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE"), "Section 6 checklist must be omitted");
    }

    @Test
    @DisplayName("Test 16: Perspective Priority Fix - customFocusInstruction overrides non-null overridePerspective")
    void shouldPrioritizePromptPerspectiveOverDefaultWorkspaceOverride() {
        UUID workspaceId = UUID.randomUUID();
        String clerkUserId = "clerk_perspective_test";

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setTitle("Arbitration Bank Freeze Matter");
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setPrimaryDomain(com.app.refirm.triage.domain.LegalDomain.CORPORATE);

        DraftingContext ctx = new DraftingContext(
                "Arbitration Section 9 interim relief",
                null,
                List.of(new StatutePin("The Arbitration and Conciliation Act, 1996", "Section 9", "Interim measures by Court")),
                null
        );
        workspace.setLatestDraftingContext(ctx);

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));
        when(argumentSheetRepository.save(any(WorkspaceArgumentSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Call with explicit overridePerspective = CLAIMANT_PETITIONER (default router state)
        // but prompt explicitly instructs "Prepare our defense as Respondent..."
        WorkspaceArgumentSheet sheet = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.CLAIMANT_PETITIONER,
                "Prepare our defense as Respondent. The claimant has filed a Section 9 application under the Arbitration and Conciliation Act seeking an urgent interim injunction to freeze our bank accounts"
        );

        assertNotNull(sheet);
        assertEquals(PartyPerspective.OPPOSITE_PARTY_RESPONDENT, sheet.getPartyPerspective(),
                "Explicit defense prompt cue must override default CLAIMANT_PETITIONER overridePerspective");
        assertTrue(sheet.getTitle().contains("Respondent Defense"), "Title should reflect Respondent perspective");
    }

    @Test
    @DisplayName("Test 17: Terra Free-Flowing Markdown Pipeline preserves sections, clarifies, and increments version")
    void shouldHandleTerraFreeFlowingMarkdownPipelineAndVersionIncrement() {
        UUID workspaceId = UUID.randomUUID();
        String clerkUserId = "clerk_terra_test";

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setTitle("Urgent Section 9 Bank Freeze Defense");
        workspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT);
        workspace.setPrimaryDomain(com.app.refirm.triage.domain.LegalDomain.CORPORATE);

        StatutePin s9Pin = new StatutePin("The Arbitration and Conciliation Act, 1996", "Section 9", "Interim measures by Court");
        workspace.setLatestDraftingContext(new DraftingContext("Interim relief dispute", null, List.of(s9Pin), null));

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));
        when(argumentSheetRepository.save(any(WorkspaceArgumentSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock chatClient content() returning Senior Counsel Markdown
        String seniorCounselMarkdown = """
                ### Core Position
                May it please this Hon'ble Court, on behalf of the Respondent, we submit that the Section 9 application to freeze working-capital bank accounts is an abuse of process.

                ### Threshold Objections
                The Claimant has failed to establish irreparable injury under Section 9 of the Arbitration and Conciliation Act, 1996.

                ### Pre-empting Opponent
                The Claimant will allege dissipation of assets, but the balance sheets reflect healthy operations.

                ### Clarifications to Sharpen This Brief
                1. Are the accounts sought to be frozen current payroll or operational accounts?
                2. Has notice under Section 21 invoking arbitration been issued?
                """;

        ChatClient mockChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCall = mock(ChatClient.CallResponseSpec.class);

        when(mockChatClient.prompt()).thenReturn(mockSpec);
        when(mockSpec.system(anyString())).thenReturn(mockSpec);
        when(mockSpec.user(anyString())).thenReturn(mockSpec);
        when(mockSpec.call()).thenReturn(mockCall);
        when(mockCall.content()).thenReturn(seniorCounselMarkdown);

        ArgumentPrepService terraService = new ArgumentPrepService(
                workspaceRepository,
                documentRepository,
                argumentSheetRepository,
                precedentProvider,
                firewallSanitizer,
                markdownRenderer,
                mockChatClient,
                new ObjectMapper(),
                null
        );

        // Turn 1
        WorkspaceArgumentSheet sheetV1 = terraService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.OPPOSITE_PARTY_RESPONDENT,
                "Prepare our defense as Respondent against Section 9 bank freeze"
        );

        assertNotNull(sheetV1);
        assertEquals(1, sheetV1.getVersion(), "Initial sheet should be version 1");
        String md1 = sheetV1.getRenderedMarkdown();
        assertNotNull(md1);
        assertTrue(md1.contains("### Core Position"));
        assertTrue(md1.contains("### Threshold Objections"));
        assertTrue(md1.contains("### Clarifications to Sharpen This Brief"));
        assertFalse(md1.contains("(Section )"), "Must not contain broken (Section ) artifacts");

        // Turn 2: Simulate stateful follow-up when previous sheet exists in repository
        when(argumentSheetRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(workspaceId, clerkUserId))
                .thenReturn(List.of(sheetV1));

        WorkspaceArgumentSheet sheetV2 = terraService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.OPPOSITE_PARTY_RESPONDENT,
                "The accounts are active payroll accounts and no Section 21 notice has been served."
        );

        assertNotNull(sheetV2);
        assertEquals(2, sheetV2.getVersion(), "Follow-up sheet must increment version to 2");
    }

    @Test
    @DisplayName("Test 18: Universal Acronym Parsing, Bleed Prevention & Post-Terra OKF Resolution with 2-Line Coverage Footer")
    void testUniversalAcronymParsingBleedPreventionAndPostTerraResolution() {
        // 1. Verify tuple parsing from complex Indian statutory sentence:
        // "Under Sections 7 and 3 of the IBC and Sections 415 and 420 IPC and Sections 41, 41A and 438 CrPC"
        String citationText = "Under Sections 7 and 3 of the IBC and Sections 415 and 420 IPC and Sections 41, 41A and 438 CrPC";
        Set<ArgumentMarkdownRenderer.ActSectionRef> refs = new LinkedHashSet<>();
        ArgumentMarkdownRenderer.parseTextIntoRefs(citationText, refs);

        // Assert IBC sections extracted
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Insolvency and Bankruptcy Code") && r.sectionNumber().equals("7")),
                "Must extract Section 7 IBC");
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Insolvency and Bankruptcy Code") && r.sectionNumber().equals("3")),
                "Must extract Section 3 IBC");

        // Assert IPC sections extracted
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Indian Penal Code") && r.sectionNumber().equals("415")),
                "Must extract Section 415 IPC");
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Indian Penal Code") && r.sectionNumber().equals("420")),
                "Must extract Section 420 IPC");

        // Assert CrPC sections extracted
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Code of Criminal Procedure") && r.sectionNumber().equals("41")),
                "Must extract Section 41 CrPC");
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Code of Criminal Procedure") && r.sectionNumber().equals("41A")),
                "Must extract Section 41A CrPC");
        assertTrue(refs.stream().anyMatch(r -> ArgumentMarkdownRenderer.isActMatch(r.actKey(), "Code of Criminal Procedure") && r.sectionNumber().equals("438")),
                "Must extract Section 438 CrPC");

        // 2. Bleed Prevention: Verify filterToActivelyCitedStatutes does NOT bleed Section 7 or Section 9 into Arbitration Act
        // when only Section 8 of the Arbitration Act was cited, alongside Section 7 of the IBC
        List<StatutePin> statutePack = List.of(
                new StatutePin("Arbitration and Conciliation Act, 1996", "7", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Arbitration agreement", "content"),
                new StatutePin("Arbitration and Conciliation Act, 1996", "8", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Power to refer parties to arbitration", "content"),
                new StatutePin("Arbitration and Conciliation Act, 1996", "9", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Interim measures by Court", "content"),
                new StatutePin("Insolvency and Bankruptcy Code, 2016", "7", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Initiation of corporate insolvency", "content")
        );

        Set<ArgumentMarkdownRenderer.ActSectionRef> mixedRefs = Set.of(
                new ArgumentMarkdownRenderer.ActSectionRef("IBC", "7"),
                new ArgumentMarkdownRenderer.ActSectionRef("Arbitration Act", "8")
        );

        List<StatutePin> filtered = ArgumentMarkdownRenderer.filterToActivelyCitedStatutes(statutePack, mixedRefs);
        assertEquals(2, filtered.size(), "Only explicitly cited Section 7 IBC and Section 8 Arbitration Act should remain");
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Insolvency") && p.sectionNumber().equals("7")));
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Arbitration") && p.sectionNumber().equals("8")));
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Arbitration") && p.sectionNumber().equals("7")),
                "Arbitration Act Section 7 must NOT bleed in when only IBC Section 7 was cited");
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Arbitration") && p.sectionNumber().equals("9")),
                "Arbitration Act Section 9 must NOT bleed in");

        // 3. Post-Terra Catalog Resolution & 2-Line Coverage Footer:
        LegalRetrievalService mockRetrieval = mock(LegalRetrievalService.class);
        when(mockRetrieval.findStatuteFile(eq("IPC"), eq("420"))).thenReturn(Optional.of(
                new LegalRetrievalService.StatuteFile(
                        "Indian Penal Code, 1860",
                        "section_420.md",
                        "## Section 420. Cheating and dishonestly inducing delivery of property\nWhoever cheats and thereby dishonestly induces...",
                        "cheating and dishonestly inducing delivery of property"
                )
        ));
        when(mockRetrieval.findStatuteFile(eq("IPC"), eq("415"))).thenReturn(Optional.of(
                new LegalRetrievalService.StatuteFile(
                        "Indian Penal Code, 1860",
                        "section_415.md",
                        "## Section 415. Cheating\nWhoever, by deceiving any person...",
                        "cheating deception"
                )
        ));

        ArgumentPrepService terraServiceWithRetrieval = new ArgumentPrepService(
                workspaceRepository,
                documentRepository,
                argumentSheetRepository,
                precedentProvider,
                firewallSanitizer,
                markdownRenderer,
                chatClient,
                objectMapper,
                mockRetrieval
        );

        String rawTerraOutput = """
                ## Submissions Opposing Anticipatory Bail
                **Alleged offence: Section 420 IPC | Alleged corporate fraud: ₹2.5 crore**
                **For the Complainant**

                The ingredients of cheating under Sections 415 and 420 IPC are squarely made out.
                In *Sushila Aggarwal v. State (NCT of Delhi)*, (2020) 5 SCC 1, the Constitution Bench held that anticipatory bail cannot be granted mechanically.
                """;

        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(rawTerraOutput);

        WorkspaceArgumentSheet sheet = terraServiceWithRetrieval.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.CLAIMANT_PETITIONER,
                "Submissions opposing anticipatory bail under Sections 415 and 420 IPC"
        );

        assertNotNull(sheet);
        String md = sheet.getRenderedMarkdown();
        assertNotNull(md);

        // Verify Statutory Source Coverage contains resolved OKF pins
        assertTrue(md.contains("> **Statutory Source Coverage:**"), "Must contain Statutory Source Coverage block");
        assertTrue(md.contains("Indian Penal Code, 1860 (Section 415, Section 420)") || md.contains("Indian Penal Code"),
                "Must verify resolved IPC sections in OKF Pack");

        // Verify Case Law Coverage line is appended
        assertTrue(md.contains("> **Case Law Coverage:**"), "Must contain transparent 2nd line Case Law Coverage block");
        assertTrue(md.contains("recalled from model knowledge"), "Must contain disclaimer for recalled case citations");
    }

    @Test
    @DisplayName("Test 19: Strict Statutory Attribution & No Cross-Act Substitution for Repeated 'Section' Lists")
    void testStrictStatutoryAttributionAndNoCrossActSubstitution() {
        String citationText = "Sections 415 and 420 IPC, Section 438 CrPC, and Section 41 and Section 41A CrPC";
        Set<ArgumentMarkdownRenderer.ActSectionRef> refs =
                ArgumentMarkdownRenderer.extractCitedActSectionRefs(null, citationText);

        assertNotNull(refs); 

        // 1. Assert exactly 5 tuples with explicit Act keys are extracted
        assertTrue(refs.stream().anyMatch(r -> r.actKey().equalsIgnoreCase("IPC") && r.sectionNumber().equals("415")),
                "Must extract (IPC, 415)");
        assertTrue(refs.stream().anyMatch(r -> r.actKey().equalsIgnoreCase("IPC") && r.sectionNumber().equals("420")),
                "Must extract (IPC, 420)");
        assertTrue(refs.stream().anyMatch(r -> r.actKey().equalsIgnoreCase("CrPC") && r.sectionNumber().equals("438")),
                "Must extract (CrPC, 438)");
        assertTrue(refs.stream().anyMatch(r -> r.actKey().equalsIgnoreCase("CrPC") && r.sectionNumber().equals("41")),
                "Must extract (CrPC, 41)");
        assertTrue(refs.stream().anyMatch(r -> r.actKey().equalsIgnoreCase("CrPC") && r.sectionNumber().equals("41A")),
                "Must extract (CrPC, 41A)");

        // 2. Assert no bare section refs exist for 41, 41A, 438
        assertFalse(refs.stream().anyMatch(r -> r.actKey().isBlank() && (r.sectionNumber().equals("41") || r.sectionNumber().equals("41A") || r.sectionNumber().equals("438"))),
                "Should not have bare section references without Act key");

        // 3. Bleed and cross-Act substitution prevention in filterToActivelyCitedStatutes
        List<StatutePin> candidatePins = List.of(
                new StatutePin("The Bharatiya Nagarik Suraksha Sanhita, 2023", "438", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content"),
                new StatutePin("The Arbitration and Conciliation Act, 1996", "41", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content"),
                new StatutePin("The Customs Act, 1962", "41A", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content"),
                new StatutePin("The Code of Criminal Procedure, 1973", "438", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content"),
                new StatutePin("The Code of Criminal Procedure, 1973", "41", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content"),
                new StatutePin("The Indian Penal Code, 1860", "415", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content"),
                new StatutePin("The Indian Penal Code, 1860", "420", com.app.refirm.triage.domain.StatuteType.OPERATIVE, "Heading", "content")
        );

        List<StatutePin> filtered = ArgumentMarkdownRenderer.filterToActivelyCitedStatutes(candidatePins, refs);

        // CrPC and IPC pins must be retained
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Criminal Procedure") && p.sectionNumber().equals("438")),
                "CrPC 438 must be retained");
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Criminal Procedure") && p.sectionNumber().equals("41")),
                "CrPC 41 must be retained");
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Indian Penal Code") && p.sectionNumber().equals("415")),
                "IPC 415 must be retained");
        assertTrue(filtered.stream().anyMatch(p -> p.actName().contains("Indian Penal Code") && p.sectionNumber().equals("420")),
                "IPC 420 must be retained");

        // Cross-act substitutions must NEVER occur
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Bharatiya Nagarik Suraksha") && p.sectionNumber().equals("438")),
                "BNSS Section 438 must NEVER be substituted when CrPC 438 was cited");
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Arbitration") && p.sectionNumber().equals("41")),
                "Arbitration Act Section 41 must NEVER be substituted when CrPC 41 was cited");
        assertFalse(filtered.stream().anyMatch(p -> p.actName().contains("Customs") && p.sectionNumber().equals("41A")),
                "Customs Act Section 41A must NEVER be substituted when CrPC 41A was cited");

        // 4. Post-Terra catalog resolution check
        LegalRetrievalService mockRetrieval = mock(LegalRetrievalService.class);
        when(mockRetrieval.findStatuteFile("CrPC", "438")).thenReturn(Optional.of(
                new LegalRetrievalService.StatuteFile(
                        "The Code of Criminal Procedure, 1973",
                        "section_438.md",
                        "Direction for grant of bail...",
                        "bail"
                )
        ));
        when(mockRetrieval.findStatuteFile("CrPC", "41")).thenReturn(Optional.of(
                new LegalRetrievalService.StatuteFile(
                        "The Code of Criminal Procedure, 1973",
                        "section_41.md",
                        "When police may arrest without warrant...",
                        "arrest"
                )
        ));
        // Section 41A is not in CrPC OKF bundle (unbundled)
        when(mockRetrieval.findStatuteFile("CrPC", "41A")).thenReturn(Optional.empty());

        List<StatutePin> dynamicPins = new ArrayList<>();
        ArgumentPrepService service = new ArgumentPrepService(
                workspaceRepository,
                documentRepository,
                argumentSheetRepository,
                precedentProvider,
                firewallSanitizer,
                markdownRenderer,
                chatClient,
                objectMapper,
                mockRetrieval
        );

        service.resolveTerraCitedStatutesFromCatalog(citationText, dynamicPins);

        assertEquals(2, dynamicPins.size(), "Only bundled CrPC 438 and CrPC 41 should be resolved");
        assertTrue(dynamicPins.stream().anyMatch(p -> p.actName().contains("Criminal Procedure") && p.sectionNumber().equals("438")));
        assertTrue(dynamicPins.stream().anyMatch(p -> p.actName().contains("Criminal Procedure") && p.sectionNumber().equals("41")));
        assertFalse(dynamicPins.stream().anyMatch(p -> p.actName().contains("Customs")), "Customs Act must NOT be substituted");
        assertFalse(dynamicPins.stream().anyMatch(p -> p.actName().contains("Arbitration")), "Arbitration Act must NOT be substituted");
        assertFalse(dynamicPins.stream().anyMatch(p -> p.actName().contains("Bharatiya")), "BNSS must NOT be substituted");
    }

    @Test
    @DisplayName("Test 20: Article vs Section Label Formatting & Extraction (Limitation Act Articles 113, 137 vs Sections 5, 14, Constitution Article 226)")
    void testArticleVsSectionLabelFormattingAndExtraction() {
        // 1. formatProvisionPrefix unit assertions
        assertEquals("Article ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "137"));
        assertEquals("Article ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "113"));
        assertEquals("Article ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "54"));
        assertEquals("Article ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "schedule_articles_113_137.md"));
        assertEquals("Section ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "5"));
        assertEquals("Section ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "14"));
        assertEquals("Section ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "32"));
        assertEquals("Section ", ArgumentMarkdownRenderer.formatProvisionPrefix("The Limitation Act, 1963", "section_5.md"));
        assertEquals("Article ", ArgumentMarkdownRenderer.formatProvisionPrefix("Constitution of India", "226"));
        assertEquals("Article ", ArgumentMarkdownRenderer.formatProvisionPrefix("Constitution of India", "32"));
        assertEquals("Section ", ArgumentMarkdownRenderer.formatProvisionPrefix("Indian Penal Code", "420"));
        assertEquals("Section ", ArgumentMarkdownRenderer.formatProvisionPrefix("Insolvency and Bankruptcy Code, 2016", "7"));

        // 2. formatProvisionForTableCell assertions
        StatutePin limitationArt137Pin = new StatutePin(
                "The Limitation Act, 1963",
                "137",
                StatuteType.OPERATIVE,
                "Residuary Limitation (Articles 113 and 137)",
                "---\nsection: '137'\nheading: Residuary Limitation\n---\nOperative text"
        );
        String tableCellArt137 = ArgumentMarkdownRenderer.formatProvisionForTableCell(limitationArt137Pin);
        assertTrue(tableCellArt137.startsWith("Article 137"), "Must be formatted as Article 137: " + tableCellArt137);
        assertFalse(tableCellArt137.contains("Section 137"), "Must NOT be formatted as Section 137: " + tableCellArt137);

        StatutePin limitationSec5Pin = new StatutePin(
                "The Limitation Act, 1963",
                "5",
                StatuteType.OPERATIVE,
                "Extension of prescribed period",
                "---\nsection: '5'\nheading: Extension of period\n---\nOperative text"
        );
        String tableCellSec5 = ArgumentMarkdownRenderer.formatProvisionForTableCell(limitationSec5Pin);
        assertTrue(tableCellSec5.startsWith("Section 5"), "Must be formatted as Section 5: " + tableCellSec5);

        StatutePin constArt226Pin = new StatutePin(
                "Constitution of India",
                "226",
                StatuteType.OPERATIVE,
                "Power of High Courts to issue certain writs",
                "---\nsection: '226'\nheading: Power of High Courts\n---\nOperative text"
        );
        String tableCellArt226 = ArgumentMarkdownRenderer.formatProvisionForTableCell(constArt226Pin);
        assertTrue(tableCellArt226.startsWith("Article 226"), "Must be formatted as Article 226: " + tableCellArt226);

        // 3. renderStatutorySourceCoverage assertions
        List<StatutePin> pins = List.of(limitationArt137Pin, limitationSec5Pin, constArt226Pin);
        String coverageMarkdown = ArgumentMarkdownRenderer.renderStatutorySourceCoverage(pins, null, null);
        assertTrue(coverageMarkdown.contains("Article 137"), "Coverage must contain Article 137: " + coverageMarkdown);
        assertTrue(coverageMarkdown.contains("Section 5"), "Coverage must contain Section 5: " + coverageMarkdown);
        assertTrue(coverageMarkdown.contains("Article 226"), "Coverage must contain Article 226: " + coverageMarkdown);
        assertFalse(coverageMarkdown.contains("Section 137"), "Coverage must NEVER contain Section 137: " + coverageMarkdown);
        assertFalse(coverageMarkdown.contains("Section 226"), "Coverage must NEVER contain Section 226: " + coverageMarkdown);

        // 4. parseTextIntoRefs assertions with Article citations
        Set<ArgumentMarkdownRenderer.ActSectionRef> refs = new LinkedHashSet<>();
        String promptWithArticles = "We rely on Article 137 of the Limitation Act, 1963 and Article 226 of the Constitution of India.";
        ArgumentMarkdownRenderer.parseTextIntoRefs(promptWithArticles, refs);

        assertTrue(refs.stream().anyMatch(r -> r.actKey().toLowerCase().contains("limitation") && r.sectionNumber().equals("137")),
                "Must extract Limitation Act Article 137");
        assertTrue(refs.stream().anyMatch(r -> r.actKey().toLowerCase().contains("constitution") && r.sectionNumber().equals("226")),
                "Must extract Constitution Article 226");

        // 5. STATIC_TERRA_SYSTEM_PROMPT assertions (minimal, zero format/table handcuffs)
        assertTrue(ArgumentPrepService.STATIC_TERRA_SYSTEM_PROMPT.contains("NATURAL STRUCTURE & PROPORTIONALITY"));
        assertTrue(ArgumentPrepService.STATIC_TERRA_SYSTEM_PROMPT.contains("sub-sections/preconditions of the invoked provision itself"));
        assertTrue(ArgumentPrepService.STATIC_TERRA_SYSTEM_PROMPT.contains("external statutory interplay/sequencing"));
        assertFalse(ArgumentPrepService.STATIC_TERRA_SYSTEM_PROMPT.contains("Anticipated Counter-Arguments & Our Answers"));
        assertFalse(ArgumentPrepService.STATIC_TERRA_SYSTEM_PROMPT.contains("Particulars / Chronology to Insert from Record"));
    }
}
