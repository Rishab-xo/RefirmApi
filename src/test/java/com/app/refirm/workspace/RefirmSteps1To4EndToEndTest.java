package com.app.refirm.workspace;

import com.app.refirm.arguments.domain.*;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.arguments.service.*;
import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateRecommendation;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.templates.service.*;
import com.app.refirm.triage.domain.*;
import com.app.refirm.triage.pipeline.TriagePipelineOrchestrator;
import com.app.refirm.workspace.domain.*;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceDocumentPage;
import com.app.refirm.workspace.ingestion.DocumentOkfIndexer;
import com.app.refirm.workspace.ingestion.DocumentPageExtractor;
import com.app.refirm.workspace.ingestion.OcrQualityGate;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.service.CaseDocumentOkfRetriever;
import com.app.refirm.workspace.service.FactReconcilerService;
import com.app.refirm.workspace.service.WorkspaceAssistantService;
import com.app.refirm.workspace.service.WorkspaceOrchestratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-End Multi-Module Integration Test for Steps 1, 2, 3 (3.1 & 3.2), and 4.
 *
 * Simulates the complete 12-stage legal workflow for the GoMechanic car service dispute:
 * - Stage 1: Quick Consult creation & strategic triage -> asserts primaryDomain == CONSUMER
 * - Stage 2: Lawyer locks opposite parties to ["GoMechanic (SmartAutoWeb Pvt. Ltd.)", "Motorades Gurgaon"]
 * - Stage 3: Invoice ingestion (₹36,776) -> asserts unlocked disputeAmount updates, locked parties preserved
 * - Stage 4: SC Aggregator Liability precedent ingestion -> asserts zero ledger contamination
 * - Stage 5: Lawyer Scoped Q&A -> asserts CaseDocumentOkfRetriever pins invoice page and returns PinnedPageCitation
 * - Stage 6: Mode promotion to CASE_PROJECT -> "Car Owner vs. GoMechanic & Motorades"
 * - Stage 7: Procedural recommender -> ranks consumer_complaint_sec35_edaakhil & legal notice at top
 * - Stage 8: 0-Token Auto-Fill v1 -> verifies zero unresolved {{FACT: and {{AI_BLOCK: tokens remain
 * - Stage 9: AI narrative block synthesis (v2) -> lawyer locks block -> particulars updated (v3) with verification preserved
 * - Stage 10: Courtroom Argument Prep & Authority Firewall -> verifies real precedent kept, hallucinated citation stripped
 * - Stage 11: Incremental Rebuttal Injection -> appends counter to Section 79 IT Act defense, advancing version to 2
 * - Stage 12: Staleness & perspective flip -> flips to OPPOSITE_PARTY_RESPONDENT, verifying staleness flag and WS recommendation
 */
@DisplayName("Refirm Steps 1-4 End-to-End Multi-Module Integration Test (GoMechanic 12-Stage Scenario)")
public class RefirmSteps1To4EndToEndTest {

    // ─── External IO Mock Repositories with In-Memory State ───
    private LegalWorkspaceRepository workspaceRepository;
    private WorkspaceDocumentRepository documentRepository;
    private WorkspaceDocumentPageRepository pageRepository;
    private WorkspaceDraftRepository draftRepository;
    private WorkspaceArgumentSheetRepository argumentSheetRepository;

    // ─── External Pipeline Mocks ───
    private TriagePipelineOrchestrator triagePipelineOrchestrator;
    private DocumentPageExtractor pageExtractor;
    private OcrQualityGate qualityGate;
    private DocumentOkfIndexer okfIndexer;
    private ChatClient assistantChatClient;
    private ChatClient draftingChatClient;
    private ChatClient argumentChatClient;

    // ─── Real Business Services Wired Together ───
    private FactReconcilerService factReconcilerService;
    private WorkspaceOrchestratorService workspaceOrchestratorService;
    private CaseDocumentOkfRetriever okfRetriever;
    private WorkspaceAssistantService workspaceAssistantService;
    private TemplateRegistryService templateRegistryService;
    private TemplateRecommendationService templateRecommendationService;
    private TemplateFactResolver templateFactResolver;
    private TemplateRenderer templateRenderer;
    private HybridTemplateDraftService hybridTemplateDraftService;
    private WorkspaceUploadedPrecedentProvider precedentProvider;
    private AuthorityFirewallSanitizer firewallSanitizer;
    private ArgumentMarkdownRenderer argumentMarkdownRenderer;
    private ArgumentPrepService argumentPrepService;

    // ─── In-Memory Datastores ───
    private final Map<UUID, LegalWorkspace> workspaces = new ConcurrentHashMap<>();
    private final Map<UUID, WorkspaceDocument> documents = new ConcurrentHashMap<>();
    private final List<WorkspaceDocumentPage> documentPages = new CopyOnWriteArrayList<>();
    private final Map<UUID, WorkspaceDraft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, WorkspaceArgumentSheet> argumentSheets = new ConcurrentHashMap<>();

    private final String clerkUserId = "advocate_sharma_delhi";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        workspaces.clear();
        documents.clear();
        documentPages.clear();
        drafts.clear();
        argumentSheets.clear();

        // 1. Initialize Mockito Mocks
        workspaceRepository = mock(LegalWorkspaceRepository.class);
        documentRepository = mock(WorkspaceDocumentRepository.class);
        pageRepository = mock(WorkspaceDocumentPageRepository.class);
        draftRepository = mock(WorkspaceDraftRepository.class);
        argumentSheetRepository = mock(WorkspaceArgumentSheetRepository.class);

        triagePipelineOrchestrator = mock(TriagePipelineOrchestrator.class);
        pageExtractor = mock(DocumentPageExtractor.class);
        qualityGate = mock(OcrQualityGate.class);
        okfIndexer = mock(DocumentOkfIndexer.class);

        assistantChatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        draftingChatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        argumentChatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);

        // 2. Wire In-Memory Repository Behaviors
        when(workspaceRepository.save(any(LegalWorkspace.class))).thenAnswer(inv -> {
            LegalWorkspace ws = inv.getArgument(0);
            workspaces.put(ws.getWorkspaceId(), ws);
            return ws;
        });

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(any(UUID.class), anyString())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            String user = inv.getArgument(1);
            LegalWorkspace ws = workspaces.get(id);
            if (ws != null && ws.getClerkUserId().equals(user)) {
                return Optional.of(ws);
            }
            return Optional.empty();
        });

        when(documentRepository.save(any(WorkspaceDocument.class))).thenAnswer(inv -> {
            WorkspaceDocument doc = inv.getArgument(0);
            if (doc.getDocumentId() == null) {
                doc.setDocumentId(UUID.randomUUID());
            }
            documents.put(doc.getDocumentId(), doc);
            return doc;
        });

        when(documentRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(any(UUID.class), anyString())).thenAnswer(inv -> {
            UUID wsId = inv.getArgument(0);
            String user = inv.getArgument(1);
            return documents.values().stream()
                    .filter(d -> d.getWorkspaceId().equals(wsId) && d.getClerkUserId().equals(user))
                    .sorted(Comparator.comparing(WorkspaceDocument::getCreatedAt))
                    .toList();
        });

        when(documentRepository.findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(any(UUID.class), anyString())).thenAnswer(inv -> {
            UUID wsId = inv.getArgument(0);
            String user = inv.getArgument(1);
            return documents.values().stream()
                    .filter(d -> d.getWorkspaceId().equals(wsId) && d.getClerkUserId().equals(user) && d.isIncludedInCaseContext())
                    .sorted(Comparator.comparing(WorkspaceDocument::getCreatedAt))
                    .toList();
        });

        when(pageRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<WorkspaceDocumentPage> pages = inv.getArgument(0);
            pages.forEach(documentPages::add);
            return pages;
        });

        when(pageRepository.findByDocumentIdAndPageNumberInOrderByPageNumberAsc(any(UUID.class), anyCollection())).thenAnswer(inv -> {
            UUID docId = inv.getArgument(0);
            Collection<?> pNums = inv.getArgument(1);
            return documentPages.stream()
                    .filter(p -> p.getDocumentId().equals(docId) && pNums.contains(p.getPageNumber()))
                    .sorted(Comparator.comparingInt(WorkspaceDocumentPage::getPageNumber))
                    .toList();
        });

        when(draftRepository.save(any(WorkspaceDraft.class))).thenAnswer(inv -> {
            WorkspaceDraft d = inv.getArgument(0);
            if (d.getDraftId() == null) {
                d.setDraftId(UUID.randomUUID());
            }
            drafts.put(d.getDraftId(), d);
            return d;
        });

        when(draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(any(UUID.class), any(UUID.class), anyString())).thenAnswer(inv -> {
            UUID draftId = inv.getArgument(0);
            UUID wsId = inv.getArgument(1);
            String user = inv.getArgument(2);
            WorkspaceDraft d = drafts.get(draftId);
            if (d != null && d.getWorkspaceId().equals(wsId) && d.getClerkUserId().equals(user)) {
                return Optional.of(d);
            }
            return Optional.empty();
        });

        when(argumentSheetRepository.save(any(WorkspaceArgumentSheet.class))).thenAnswer(inv -> {
            WorkspaceArgumentSheet s = inv.getArgument(0);
            if (s.getSheetId() == null) {
                s.setSheetId(UUID.randomUUID());
            }
            argumentSheets.put(s.getSheetId(), s);
            return s;
        });

        when(argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(any(UUID.class), any(UUID.class), anyString())).thenAnswer(inv -> {
            UUID sheetId = inv.getArgument(0);
            UUID wsId = inv.getArgument(1);
            String user = inv.getArgument(2);
            WorkspaceArgumentSheet s = argumentSheets.get(sheetId);
            if (s != null && s.getWorkspaceId().equals(wsId) && s.getClerkUserId().equals(user)) {
                return Optional.of(s);
            }
            return Optional.empty();
        });

        // 3. Initialize REAL Services
        factReconcilerService = new FactReconcilerService();

        workspaceOrchestratorService = new WorkspaceOrchestratorService(
                workspaceRepository,
                documentRepository,
                pageRepository,
                factReconcilerService,
                triagePipelineOrchestrator,
                pageExtractor,
                qualityGate,
                okfIndexer
        );

        okfRetriever = new CaseDocumentOkfRetriever(documentRepository, pageRepository);

        workspaceAssistantService = new WorkspaceAssistantService(
                workspaceRepository,
                documentRepository,
                okfRetriever,
                assistantChatClient
        );

        templateRegistryService = new TemplateRegistryService(objectMapper);
        templateRegistryService.loadTemplates();

        templateRecommendationService = new TemplateRecommendationService(
                templateRegistryService,
                documentRepository
        );

        templateFactResolver = new TemplateFactResolver();
        templateRenderer = new TemplateRenderer();

        hybridTemplateDraftService = new HybridTemplateDraftService(
                workspaceRepository,
                draftRepository,
                documentRepository,
                templateRegistryService,
                templateFactResolver,
                templateRenderer,
                draftingChatClient
        );

        precedentProvider = new WorkspaceUploadedPrecedentProvider(documentRepository);
        firewallSanitizer = new AuthorityFirewallSanitizer();
        argumentMarkdownRenderer = new ArgumentMarkdownRenderer();

        argumentPrepService = new ArgumentPrepService(
                workspaceRepository,
                documentRepository,
                argumentSheetRepository,
                precedentProvider,
                firewallSanitizer,
                argumentMarkdownRenderer,
                argumentChatClient,
                objectMapper
        );
    }

    @Test
    @DisplayName("Complete 12-Stage GoMechanic End-to-End Workflow: Triage -> Locking -> Ingestion -> Assistant -> Promotion -> Draft v1/v2/v3 -> Arguments v1/v2 -> Staleness")
    void testCompleteSteps1To4GoMechanicWorkflow() {

        // =========================================================================
        // STAGE 1: Create Quick Consult & Run Strategic Triage
        // =========================================================================
        LegalWorkspace workspace = workspaceOrchestratorService.createQuickConsult(
                clerkUserId,
                PartyPerspective.CLAIMANT_PETITIONER
        );
        assertNotNull(workspace);
        UUID workspaceId = workspace.getWorkspaceId();
        assertEquals(WorkspaceMode.QUICK_CONSULT, workspace.getMode());

        String goMechanicNarrative = """
                I booked a car engine service through GoMechanic (SmartAutoWeb Pvt. Ltd.) in Gurugram, Haryana for ₹36,776
                on 12th January 2024. The authorized partner workshop was Motorades Gurgaon. After taking delivery of my vehicle,
                the engine seized on the expressway because they poured the wrong grade engine oil and stripped the drain plug.
                GoMechanic customer support refused compensation, claiming Section 79 IT Act safe harbor as an intermediary.
                I want to file a consumer complaint against both GoMechanic and Motorades to recover my damages and expenses.
                """;

        PartyEntity initialClaimant = new PartyEntity("Car Owner", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.PAID_CONSIDERATION);
        PartyEntity initialOp1 = new PartyEntity("GoMechanic", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);
        PartyEntity initialOp2 = new PartyEntity("Motorades", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);

        ExtractedFacts triageFacts = new ExtractedFacts(
                LegalDomain.CONSUMER,
                initialClaimant,
                null,
                List.of(initialOp1, initialOp2),
                List.of("Engine seized on expressway due to stripped drain plug and improper engine oil"),
                36776.0
        );

        ResolvedForum targetForum = new ResolvedForum(
                "District Consumer Disputes Redressal Commission, Gurugram",
                "e-Daakhil",
                "Section 34 & 35 CPA 2019"
        );

        StatutePin statute1 = new StatutePin("Consumer Protection Act, 2019", "Section 2(11)", StatuteType.SUBSTANTIVE_BREACH, "Deficiency in service", "Any fault, imperfection or shortcoming in service quality");
        StatutePin statute2 = new StatutePin("Consumer Protection Act, 2019", "Section 34", StatuteType.PROCEDURAL_FILING, "Pecuniary jurisdiction of District Commission", "Jurisdiction to entertain complaints up to 50 Lakhs");
        StatutePin statute3 = new StatutePin("Consumer Protection Act, 2019", "Section 35", StatuteType.PROCEDURAL_FILING, "Manner in which complaint shall be made", "Filing of consumer complaint before District Commission");

        DraftingContext initialDraftingContext = new DraftingContext(
                goMechanicNarrative,
                triageFacts,
                List.of(statute1, statute2, statute3),
                targetForum
        );

        TriagePipelineResult triageResult = new TriagePipelineResult(
                initialDraftingContext,
                "# Strategic Triage Brief\n\nConsumer claim for deficient automotive servicing against GoMechanic and Motorades."
        );

        when(triagePipelineOrchestrator.executeWithContext(anyString(), anyString())).thenReturn(triageResult);

        LegalWorkspace triagedWorkspace = workspaceOrchestratorService.runStrategicTriageInWorkspace(
                workspaceId,
                clerkUserId,
                goMechanicNarrative,
                "Gurugram"
        );

        assertEquals(LegalDomain.CONSUMER, triagedWorkspace.getPrimaryDomain(), "Primary domain must resolve to CONSUMER");
        assertNotNull(triagedWorkspace.getFactLedger());
        assertEquals(1, triagedWorkspace.getFactLedger().version());
        assertEquals(36776.0, triagedWorkspace.getFactLedger().extractedFacts().estimatedFinancialLoss());

        // =========================================================================
        // STAGE 2: Lawyer Fact Lock (Locking oppositeParties)
        // =========================================================================
        PartyEntity lockedOp1 = new PartyEntity("GoMechanic (SmartAutoWeb Pvt. Ltd.)", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);
        PartyEntity lockedOp2 = new PartyEntity("Motorades Gurgaon", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);

        ExtractedFacts lawyerAdjustedFacts = new ExtractedFacts(
                LegalDomain.CONSUMER,
                triagedWorkspace.getFactLedger().extractedFacts().claimant(),
                null,
                List.of(lockedOp1, lockedOp2),
                triagedWorkspace.getFactLedger().extractedFacts().factualAnomalies(),
                triagedWorkspace.getFactLedger().extractedFacts().estimatedFinancialLoss()
        );

        ExtractedFactsDTO lawyerAdjustedRuleFacts = new ExtractedFactsDTO(
                com.app.refirm.rules.model.LegalDomain.CONSUMER,
                36776L,
                "Gurugram",
                "GoMechanic (SmartAutoWeb Pvt. Ltd.) & Motorades Gurgaon",
                null
        );

        CaseFactLedger lockedLedger = factReconcilerService.applyLawyerOverride(
                triagedWorkspace.getFactLedger(),
                lawyerAdjustedFacts,
                lawyerAdjustedRuleFacts,
                Set.of("oppositeParties")
        );

        triagedWorkspace.setFactLedger(lockedLedger);
        workspaceRepository.save(triagedWorkspace);

        assertEquals(2, triagedWorkspace.getFactLedger().version());
        assertTrue(triagedWorkspace.getFactLedger().lawyerLockedFields().contains("oppositeParties"));
        assertEquals(2, triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().size());
        assertEquals("GoMechanic (SmartAutoWeb Pvt. Ltd.)", triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().get(0).name());
        assertEquals("Motorades Gurgaon", triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().get(1).name());

        // =========================================================================
        // STAGE 3: Ingest Evidence (motorades_invoice_and_report.txt)
        // =========================================================================
        ExtractedPage invoicePage = new ExtractedPage(
                1,
                """
                MOTORADES GURGAON — TAX INVOICE & JOB CARD #MOT-2024-889
                Date: 12 January 2024
                Customer Name: Car Owner
                Booking Platform: GoMechanic (SmartAutoWeb Pvt. Ltd.)
                Vehicle: Maruti Suzuki Brezza (HR26-DD-4501)
                Service: Scheduled Comprehensive Periodic Maintenance
                Parts & Consumables: Synthetic Engine Oil 5W30, Engine Drain Plug
                Total Tax Invoice Amount Paid: INR 36,776.00
                Payment Mode: Online Prepaid via GoMechanic App
                """
        );

        when(pageExtractor.extractPages(eq("motorades_invoice_and_report.txt"), any())).thenReturn(List.of(invoicePage));
        when(qualityGate.evaluate(any())).thenReturn(new OcrQualityGate.QualityCheckResult(true, null, List.of(invoicePage)));

        DocumentOkfManifest invoiceManifest = new DocumentOkfManifest(
                "Motorades tax invoice INR 36,776 and vehicle service job card",
                List.of("Motorades Gurgaon", "GoMechanic (SmartAutoWeb Pvt. Ltd.)"),
                List.of("2024-01-12"),
                List.of("INR 36,776"),
                Map.of(
                        "invoice", List.of(1),
                        "amount", List.of(1),
                        "date", List.of(1),
                        "motorades", List.of(1),
                        "drain_plug", List.of(1)
                )
        );

        StructuredDocumentSummary invoiceSummary = new StructuredDocumentSummary(
                DocumentType.INVOICE_RECEIPT,
                "Motorades Invoice and Service Job Card",
                "Tax invoice totaling INR 36,776 for engine maintenance and replacement of drain plug",
                List.of("2024-01-12: Service performed and payment receipt generated"),
                List.of("Motorades Gurgaon", "GoMechanic (SmartAutoWeb Pvt. Ltd.)"),
                List.of("INR 36,776"),
                null
        );

        // Incoming facts: contains 36776.0 amount, and an attempt to overwrite oppositeParties with unverified name
        ExtractedFacts incomingInvoiceFacts = new ExtractedFacts(
                LegalDomain.CONSUMER,
                null,
                null,
                List.of(new PartyEntity("Unverified Garage Partner", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT)),
                List.of(),
                36776.0
        );

        ExtractedFactsDTO incomingInvoiceRuleFacts = new ExtractedFactsDTO(
                com.app.refirm.rules.model.LegalDomain.CONSUMER,
                36776L,
                "Gurugram",
                "Unverified Garage Partner",
                null
        );

        when(okfIndexer.indexDocument(eq("motorades_invoice_and_report.txt"), eq(DocumentType.INVOICE_RECEIPT), any()))
                .thenReturn(new DocumentOkfIndexer.IndexingOutput(invoiceManifest, invoiceSummary, incomingInvoiceFacts, incomingInvoiceRuleFacts));

        WorkspaceDocument invoiceDoc = workspaceOrchestratorService.ingestDocument(
                workspaceId,
                clerkUserId,
                "motorades_invoice_and_report.txt",
                DocumentType.INVOICE_RECEIPT,
                "dummy invoice byte payload".getBytes()
        );

        assertNotNull(invoiceDoc);
        assertEquals(IngestionStatus.READY, invoiceDoc.getIngestionStatus());

        // Assert fact reconciliation: version incremented to 3, unlocked amount updated, locked oppositeParties preserved
        assertEquals(3, triagedWorkspace.getFactLedger().version());
        assertEquals(36776L, triagedWorkspace.getFactLedger().ruleEvaluationFacts().disputeAmount());
        assertEquals(36776.0, triagedWorkspace.getFactLedger().extractedFacts().estimatedFinancialLoss());
        assertEquals(2, triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().size());
        assertEquals("GoMechanic (SmartAutoWeb Pvt. Ltd.)", triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().get(0).name());
        assertEquals("Motorades Gurgaon", triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().get(1).name());

        // =========================================================================
        // STAGE 4: Ingest Precedent Judgment (Contamination Guard)
        // =========================================================================
        ExtractedPage judgmentPage1 = new ExtractedPage(
                1,
                """
                SUPREME COURT OF INDIA
                Civil Appeal No. 4501 of 2022
                XYZ Digital Platform Pvt. Ltd. vs. State Consumer Disputes Redressal Commission & Anr.
                Coram: Hon'ble Justices of the Supreme Court of India
                Held: Digital marketplace aggregators exercising pricing, service branding, and centralized customer support
                cannot claim blanket safe harbor immunity under Section 79 of the Information Technology Act, 2000.
                """
        );
        ExtractedPage judgmentPage2 = new ExtractedPage(
                2,
                """
                Ratio Decidendi: When an aggregator induces consumers by promising vetted quality and directly collects payment,
                it is jointly and severally liable as a service provider under Section 2(47) and Section 35 of the Consumer Protection Act, 2019.
                """
        );

        when(pageExtractor.extractPages(eq("sc_aggregator_liability_judgment.txt"), any())).thenReturn(List.of(judgmentPage1, judgmentPage2));
        when(qualityGate.evaluate(any())).thenReturn(new OcrQualityGate.QualityCheckResult(true, null, List.of(judgmentPage1, judgmentPage2)));

        DocumentOkfManifest judgmentManifest = new DocumentOkfManifest(
                "Supreme Court judgment on digital aggregator joint liability under CPA 2019",
                List.of("XYZ Digital Platform Pvt. Ltd.", "State Consumer Commission"),
                List.of("2022-09-15"),
                List.of("INR 5,00,000"),
                Map.of(
                        "intermediary", List.of(1, 2),
                        "section_79", List.of(1),
                        "aggregator_liability", List.of(2)
                )
        );

        IracBrief judgmentIrac = new IracBrief(
                "Aggregator disclaimed liability for defective third party service under IT Act.",
                List.of("Whether online aggregators can disclaim CPA 2019 liability under Section 79 IT Act safe harbor"),
                List.of("CPA 2019 Section 2(47)", "IT Act Section 79"),
                "Aggregators exercising operational control cannot seek Section 79 safe harbor against consumer claims",
                "Appeal dismissed with exemplary costs"
        );

        StructuredDocumentSummary judgmentSummary = new StructuredDocumentSummary(
                DocumentType.JUDGMENT_PRECEDENT,
                "XYZ Digital Platform vs. State Consumer Commission, (2022) 8 SCC 450",
                "Supreme Court ruling on digital aggregator joint liability",
                List.of("2022-09-15: Judgment delivered by Hon'ble Supreme Court"),
                List.of("XYZ Digital Platform Pvt. Ltd.", "State Consumer Commission"),
                List.of("INR 5,00,000"),
                judgmentIrac
        );

        // Precedent facts that MUST NEVER contaminate the workspace case ledger
        ExtractedFacts contaminatedPrecedentFacts = new ExtractedFacts(
                LegalDomain.CONSUMER,
                new PartyEntity("Contaminated Stranger", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE),
                null,
                List.of(new PartyEntity("Contaminated Platform Inc.", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE)),
                List.of(),
                5000000.0
        );

        ExtractedFactsDTO contaminatedPrecedentRuleFacts = new ExtractedFactsDTO(
                com.app.refirm.rules.model.LegalDomain.CONSUMER,
                5000000L,
                "New Delhi",
                "Contaminated Platform Inc.",
                null
        );

        when(okfIndexer.indexDocument(eq("sc_aggregator_liability_judgment.txt"), eq(DocumentType.JUDGMENT_PRECEDENT), any()))
                .thenReturn(new DocumentOkfIndexer.IndexingOutput(judgmentManifest, judgmentSummary, contaminatedPrecedentFacts, contaminatedPrecedentRuleFacts));

        WorkspaceDocument judgmentDoc = workspaceOrchestratorService.ingestDocument(
                workspaceId,
                clerkUserId,
                "sc_aggregator_liability_judgment.txt",
                DocumentType.JUDGMENT_PRECEDENT,
                "dummy judgment pdf bytes".getBytes()
        );

        assertNotNull(judgmentDoc);
        assertEquals(IngestionStatus.READY, judgmentDoc.getIngestionStatus());

        // Contamination Guard assertions:
        assertEquals(3, triagedWorkspace.getFactLedger().version(), "Ledger version must NOT advance when ingesting JUDGMENT_PRECEDENT");
        assertEquals(36776L, triagedWorkspace.getFactLedger().ruleEvaluationFacts().disputeAmount(), "Case dispute amount must remain uncontaminated");
        assertEquals("GoMechanic (SmartAutoWeb Pvt. Ltd.)", triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().get(0).name());
        assertFalse(triagedWorkspace.getFactLedger().extractedFacts().oppositeParties().stream().anyMatch(p -> p.name().contains("Contaminated")));

        // =========================================================================
        // STAGE 5: Lawyer Scoped Q&A (Page-OKF Grounded Retrieval)
        // =========================================================================
        when(assistantChatClient.prompt().user(anyString()).call().content())
                .thenReturn("According to the Motorades tax invoice [motorades_invoice_and_report.txt, Page 1], the total invoice amount is INR 36,776 dated 12th January 2024.");

        WorkspaceChatResponse qaResponse = workspaceAssistantService.askWorkspaceAssistant(
                workspaceId,
                clerkUserId,
                "What is the invoice amount and date from Motorades?"
        );

        assertNotNull(qaResponse);
        assertTrue(qaResponse.answerMarkdown().contains("36,776"));
        assertFalse(qaResponse.citations().isEmpty(), "Assistant response must include pinned document citations");

        PinnedPageCitation firstCitation = qaResponse.citations().get(0);
        assertEquals("motorades_invoice_and_report.txt", firstCitation.fileName());
        assertEquals(1, firstCitation.pageNumber());

        // =========================================================================
        // STAGE 6: Promote Workspace to Formal Case Project
        // =========================================================================
        LegalWorkspace promotedWorkspace = workspaceOrchestratorService.promoteToCaseProject(
                workspaceId,
                clerkUserId,
                "Car Owner vs. GoMechanic & Motorades",
                "Car Owner",
                PartyPerspective.CLAIMANT_PETITIONER,
                null
        );

        assertEquals(WorkspaceMode.CASE_PROJECT, promotedWorkspace.getMode());
        assertEquals("Car Owner vs. GoMechanic & Motorades", promotedWorkspace.getTitle());
        assertEquals("Car Owner", promotedWorkspace.getClientName());
        assertNotNull(promotedWorkspace.getPromotedAt());

        // =========================================================================
        // STAGE 7: Procedural Template Recommendation
        // =========================================================================
        List<TemplateRecommendation> recommendations = templateRecommendationService.recommendForWorkspace(promotedWorkspace);
        assertNotNull(recommendations);
        assertFalse(recommendations.isEmpty(), "Recommendations must not be empty");

        List<String> topTemplateIds = recommendations.stream().map(r -> r.template().templateId()).limit(3).toList();
        assertTrue(topTemplateIds.contains("consumer_complaint_sec35_edaakhil") || topTemplateIds.contains("consumer_legal_notice_deficiency"),
                "Top recommendations must include Section 35 Complaint or Legal Notice for Consumer Claimant");

        // =========================================================================
        // STAGE 8: 0-Token Auto-Fill v1 (consumer_complaint_sec35_edaakhil)
        // =========================================================================
        Map<String, String> overrides = Map.of(
                "opposite_party_name", "1. GoMechanic (SmartAutoWeb Pvt. Ltd.) & 2. Motorades Gurgaon",
                "opposite_party_address", "Plot 12, Udyog Vihar Phase IV, Gurugram, Haryana"
        );

        WorkspaceDraft draftV1 = hybridTemplateDraftService.initializeDraft(
                workspaceId,
                clerkUserId,
                "consumer_complaint_sec35_edaakhil",
                overrides
        );

        assertNotNull(draftV1);
        assertEquals(1, draftV1.getVersion());
        String renderedV1 = draftV1.getRenderedMarkdown();

        assertTrue(renderedV1.contains("District Consumer Disputes Redressal Commission"), "Must contain resolved court forum");
        assertTrue(renderedV1.contains("Car Owner"), "Must contain complainant name");
        assertTrue(renderedV1.contains("GoMechanic (SmartAutoWeb Pvt. Ltd.)"), "Must contain Opposite Party 1");
        assertTrue(renderedV1.contains("Motorades Gurgaon"), "Must contain Opposite Party 2");
        assertTrue(renderedV1.contains("36776"), "Must contain dispute amount");
        assertFalse(renderedV1.contains("{{FACT:"), "Must not contain unresolved {{FACT: tokens");
        assertFalse(renderedV1.contains("{{AI_BLOCK:"), "Must not contain unresolved {{AI_BLOCK: tokens");

        // =========================================================================
        // STAGE 9: AI Block Fill (v2) -> Lock Block -> Update Particulars (v3)
        // =========================================================================
        when(draftingChatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        1. The Complainant booked vehicle maintenance through Opposite Party No. 1's mobile platform for ₹36,776.
                        2. Opposite Party No. 2 executed the job with stripped drain plug and adulterated engine oil, causing total engine seizure.
                        3. Opposite Party No. 1 wrongfully refused liability citing Section 79 intermediary safe harbor.
                        """);

        WorkspaceDraft draftV2 = hybridTemplateDraftService.populateAiNarrativeBlocks(
                workspaceId,
                draftV1.getDraftId(),
                clerkUserId,
                "Emphasize catastrophic engine seizure and gross deficiency under CPA Section 2(11)"
        );

        assertEquals(2, draftV2.getVersion());
        assertFalse(draftV2.getGeneratedAiBlocks().isEmpty());
        String generatedFactsText = draftV2.getGeneratedAiBlocks().get("facts_of_complaint");
        assertNotNull(generatedFactsText);

        // Lawyer locks "facts_of_complaint" block
        draftV2.getLockedAiBlockKeys().add("facts_of_complaint");

        // Lawyer updates particulars in v3 (0-token particular update)
        WorkspaceDraft draftV3 = hybridTemplateDraftService.updateParticulars(
                workspaceId,
                draftV2.getDraftId(),
                clerkUserId,
                Map.of("complainant_address", "House No. 402, Sector 45, Gurugram, Haryana - 122003")
        );

        assertEquals(3, draftV3.getVersion());
        assertTrue(draftV3.isAiBlockLocked("facts_of_complaint"));
        // Assert locked block content was preserved
        assertEquals(generatedFactsText, draftV3.getGeneratedAiBlocks().get("facts_of_complaint"));

        // Assert statutory verification clause is intact
        String renderedV3 = draftV3.getRenderedMarkdown();
        assertTrue(renderedV3.contains("AFFIDAVIT OF VERIFICATION"));
        assertTrue(renderedV3.contains("Sector 45, Gurugram, Haryana - 122003"));

        // =========================================================================
        // STAGE 10: Courtroom Argument Prep & Authority Firewall (v1)
        // =========================================================================
        // Mock argumentChatClient to produce raw dossier with real uploaded judgment + hallucinated SCC citation
        ArgumentSubmission sub1 = new ArgumentSubmission(
                "SUB_1",
                "Aggregator Joint Liability & Estoppel under CPA 2019",
                "Opposite Party No. 1 exercises end-to-end pricing and branding control, precluding Section 79 intermediary safe harbor.",
                List.of("Consumer Protection Act, 2019 Section 2(47)", "Consumer Protection Act, 2019 Section 35"),
                List.of(
                        "XYZ Digital Platform vs. State Consumer Commission, (2022) 8 SCC 450",
                        "State of Haryana v. Motor Garage Federation, (2021) 4 SCC 123" // HALLUCINATED!
                ),
                "motorades_invoice_and_report.txt",
                null
        );

        FramedLegalIssue issue1 = new FramedLegalIssue(
                "ISSUE_1",
                "Whether an online automotive service aggregator is jointly liable with its partner garage for defective repairs",
                "On Complainant to establish contractual nexus and payment of consideration"
        );

        ArgumentDossier rawDossier = new ArgumentDossier(
                "May it please the Hon'ble Commission, this is an open-and-shut case of gross deficiency where GoMechanic and Motorades caused catastrophic engine damage.",
                List.of(issue1),
                List.of(sub1),
                List.of(),
                List.of(new BenchQuestionItem(
                        "Can GoMechanic disclaim liability under Section 79 of IT Act?",
                        "No, as settled by the Supreme Court in (2022) 8 SCC 450, commercial aggregators exercising pricing control cannot claim intermediary immunity.",
                        "XYZ Digital Platform vs. State Consumer Commission, (2022) 8 SCC 450"
                )),
                List.of("Tax invoice #MOT-2024-889 dated 12 Jan 2024", "Expressway breakdown towing receipt")
        );

        when(argumentChatClient.prompt().system(anyString()).user(anyString()).call().entity(ArgumentDossier.class))
                .thenReturn(rawDossier);

        WorkspaceArgumentSheet sheetV1 = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                PartyPerspective.CLAIMANT_PETITIONER,
                "Focus on aggregator joint liability and breach of statutory warranty"
        );

        assertNotNull(sheetV1);
        assertEquals(1, sheetV1.getVersion());
        assertEquals(PartyPerspective.CLAIMANT_PETITIONER, sheetV1.getPartyPerspective());

        // Verify Authority Firewall Sanitizer:
        ArgumentSubmission sanitizedSub = sheetV1.getArgumentDossier().coreSubmissions().get(0);
        // Real judgment must be retained
        assertTrue(sanitizedSub.pinnedPrecedents().stream().anyMatch(p -> p.contains("XYZ Digital Platform")),
                "Real uploaded precedent authority must be retained by the firewall");
        // Hallucinated citation must be strictly stripped
        assertFalse(sanitizedSub.pinnedPrecedents().stream().anyMatch(p -> p.contains("State of Haryana")),
                "Hallucinated citation (2021) 4 SCC 123 must be completely stripped by Authority Firewall");

        // Evidence anchor verified
        assertEquals("motorades_invoice_and_report.txt", sanitizedSub.verifiedEvidenceAnchor());

        // =========================================================================
        // STAGE 11: Incremental Rebuttal Injection (v2)
        // =========================================================================
        RebuttalMatrixItem rebuttalItem = new RebuttalMatrixItem(
                "REB_1",
                "GoMechanic claims Section 79 IT Act safe harbor as an intermediary",
                "Section 79 intermediary safe harbor does not apply to aggregators that collect consideration, brand services, and guarantee quality under CPA 2019 Section 2(47)",
                "XYZ Digital Platform vs. State Consumer Commission, (2022) 8 SCC 450"
        );

        when(argumentChatClient.prompt().system(anyString()).user(anyString()).call().entity(RebuttalMatrixItem.class))
                .thenReturn(rebuttalItem);

        WorkspaceArgumentSheet sheetV2 = argumentPrepService.appendTargetedRebuttal(
                workspaceId,
                sheetV1.getSheetId(),
                clerkUserId,
                "GoMechanic claims Section 79 IT Act safe harbor as an intermediary"
        );

        assertNotNull(sheetV2);
        assertEquals(2, sheetV2.getVersion(), "Argument sheet version must increment to v2 upon rebuttal injection");
        assertFalse(sheetV2.getArgumentDossier().rebuttalMatrix().isEmpty());

        String renderedArgsV2 = sheetV2.getRenderedMarkdown();
        assertTrue(renderedArgsV2.contains("Section 79"), "Rendered argument sheet must include the new targeted rebuttal");
        assertTrue(renderedArgsV2.contains("Aggregator Joint Liability & Estoppel"), "Original core submissions must be fully preserved");

        // =========================================================================
        // STAGE 12: Staleness & Perspective Flip (to OPPOSITE_PARTY_RESPONDENT)
        // =========================================================================
        promotedWorkspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT);
        workspaceRepository.save(promotedWorkspace);

        // Verify Staleness check
        assertTrue(sheetV2.isStaleComparedTo(promotedWorkspace),
                "Argument sheet generated for CLAIMANT_PETITIONER must flag as stale when workspace flips to OPPOSITE_PARTY_RESPONDENT");

        // Verify Procedural Recommender adapts dynamically to OPPOSITE_PARTY_RESPONDENT
        List<TemplateRecommendation> respondentRecs = templateRecommendationService.recommendForWorkspace(promotedWorkspace);
        assertNotNull(respondentRecs);
        assertFalse(respondentRecs.isEmpty());
        assertEquals("written_statement_opposite_party", respondentRecs.get(0).template().templateId(),
                "Top recommendation for OPPOSITE_PARTY_RESPONDENT in CONSUMER domain must be written_statement_opposite_party");
    }
}
