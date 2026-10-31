package com.app.refirm.workspace;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.triage.domain.*;
import com.app.refirm.triage.pipeline.TriagePipelineOrchestrator;
import com.app.refirm.workspace.domain.*;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.ingestion.DocumentOkfIndexer;
import com.app.refirm.workspace.ingestion.DocumentPageExtractor;
import com.app.refirm.workspace.ingestion.OcrQualityGate;
import com.app.refirm.workspace.ingestion.OpenAiDocumentOkfIndexer;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.service.FactReconcilerService;
import com.app.refirm.workspace.service.WorkspaceOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionSafetyTest {

    @Mock
    private LegalWorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceDocumentRepository documentRepository;

    @Mock
    private WorkspaceDocumentPageRepository pageRepository;

    @Mock
    private FactReconcilerService factReconcilerService;

    @Mock
    private TriagePipelineOrchestrator triagePipelineOrchestrator;

    @Mock
    private DocumentPageExtractor pageExtractor;

    @Mock
    private OcrQualityGate qualityGate;

    @Mock
    private DocumentOkfIndexer okfIndexer;

    private WorkspaceOrchestratorService orchestratorService;

    private final UUID workspaceId = UUID.randomUUID();
    private final String clerkUserId = "lawyer_clerk_101";

    @BeforeEach
    void setUp() {
        orchestratorService = new WorkspaceOrchestratorService(
                workspaceRepository,
                documentRepository,
                pageRepository,
                factReconcilerService,
                triagePipelineOrchestrator,
                pageExtractor,
                qualityGate,
                okfIndexer
        );

        lenient().when(workspaceRepository.save(any(LegalWorkspace.class))).thenAnswer(returnsFirstArg());
        lenient().when(documentRepository.save(any(WorkspaceDocument.class))).thenAnswer(returnsFirstArg());
    }

    @Test
    @DisplayName("sanitizeManifestBounds strictly filters page numbers < 1 or > totalPages and handles empty pins")
    void sanitizeManifestBoundsStripsOutOfRangePages() {
        DocumentOkfManifest raw = new DocumentOkfManifest(
                "Executive gist",
                List.of("Party A"),
                List.of("2023-01-01"),
                List.of("INR 5,000"),
                Map.of(
                        "pos_receipt", List.of(-1, 0, 1, 2, 99),
                        "arbitration_clause", List.of(3, 50)
                )
        );

        // Document has only 3 pages
        DocumentOkfManifest sanitized = OpenAiDocumentOkfIndexer.sanitizeManifestBounds(raw, 3);

        assertThat(sanitized.topicPagePins()).containsKey("pos_receipt");
        assertThat(sanitized.topicPagePins().get("pos_receipt")).containsExactly(1, 2);

        assertThat(sanitized.topicPagePins()).containsKey("arbitration_clause");
        assertThat(sanitized.topicPagePins().get("arbitration_clause")).containsExactly(3);

        // Test with all pins out of bounds
        DocumentOkfManifest allInvalid = new DocumentOkfManifest(
                "Gist", List.of(), List.of(), List.of(),
                Map.of("invalid_topic", List.of(100, 200))
        );
        DocumentOkfManifest sanitizedFallback = OpenAiDocumentOkfIndexer.sanitizeManifestBounds(allInvalid, 2);
        assertThat(sanitizedFallback.topicPagePins()).containsKey("general_substance");
        assertThat(sanitizedFallback.topicPagePins().get("general_substance")).containsExactly(1);
    }

    @Test
    @DisplayName("Ingesting INVOICE_RECEIPT reconciles unlocked facts in CaseFactLedger while respecting lawyer locks")
    void ingestingInvoiceUpdatesUnlockedLedgerFieldsWhileRespectingLawyerLocks() {
        PartyEntity initialClaimant = new PartyEntity("Client", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        // Initial ledger: lawyer locked 'estimatedFinancialLoss' to 15,000.0, but 'counterparty' is unlocked
        ExtractedFacts initialFacts = new ExtractedFacts(
                com.app.refirm.triage.domain.LegalDomain.CONSUMER_RIGHTS,
                initialClaimant,
                null,
                List.of(),
                List.of(),
                15000.0
        );
        ExtractedFactsDTO initialRuleFacts = new ExtractedFactsDTO(
                LegalDomain.CONSUMER,
                15000L,
                "New Delhi",
                null,
                null
        );
        CaseFactLedger initialLedger = new CaseFactLedger(
                1,
                initialFacts,
                initialRuleFacts,
                Set.of("estimatedFinancialLoss"), // Lawyer locked
                Instant.now()
        );

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setFactLedger(initialLedger);

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        List<ExtractedPage> extractedPages = List.of(
                new ExtractedPage(1, "TAX INVOICE NO 404\nAmount: INR 45,000\nDate: 2023-04-10")
        );
        byte[] fileBytes = "dummy invoice bytes".getBytes();
        when(pageExtractor.extractPages("invoice.pdf", fileBytes)).thenReturn(extractedPages);
        when(qualityGate.evaluate(extractedPages))
                .thenReturn(new OcrQualityGate.QualityCheckResult(true, null, extractedPages));

        // Indexer output with updated loss (which should be ignored due to lock) and new opposite party
        PartyEntity merchant = new PartyEntity("Retail Merchant", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);
        ExtractedFacts indexerFacts = new ExtractedFacts(
                com.app.refirm.triage.domain.LegalDomain.CONSUMER_RIGHTS,
                initialClaimant,
                null,
                List.of(merchant),
                List.of("Defective invoice charge"),
                45000.0
        );
        ExtractedFactsDTO ruleFacts = new ExtractedFactsDTO(LegalDomain.CONSUMER, 45000L, "New Delhi", "Retail Merchant", null);
        DocumentOkfManifest manifest = new DocumentOkfManifest("Tax invoice for purchase", List.of("Retail Merchant"), List.of("2023-04-10"), List.of("INR 45,000"), Map.of("invoice", List.of(1)));
        StructuredDocumentSummary summary = StructuredDocumentSummary.empty(DocumentType.INVOICE_RECEIPT, "invoice.pdf");

        when(okfIndexer.indexDocument("invoice.pdf", DocumentType.INVOICE_RECEIPT, extractedPages))
                .thenReturn(new DocumentOkfIndexer.IndexingOutput(manifest, summary, indexerFacts, ruleFacts));

        // Simulate FactReconcilerService preserving the locked loss (15,000.0) but updating merchant
        ExtractedFacts reconciledFacts = new ExtractedFacts(
                com.app.refirm.triage.domain.LegalDomain.CONSUMER_RIGHTS,
                initialClaimant,
                null,
                List.of(merchant),
                List.of("Defective invoice charge"),
                15000.0 // preserved lock
        );
        ExtractedFactsDTO reconciledRuleFacts = new ExtractedFactsDTO(
                LegalDomain.CONSUMER,
                15000L, // preserved lock
                "New Delhi",
                "Retail Merchant",
                null
        );
        CaseFactLedger reconciledLedger = new CaseFactLedger(
                2,
                reconciledFacts,
                reconciledRuleFacts,
                Set.of("estimatedFinancialLoss"),
                Instant.now()
        );
        when(factReconcilerService.reconcile(eq(initialLedger), eq(indexerFacts), eq(ruleFacts)))
                .thenReturn(reconciledLedger);

        WorkspaceDocument savedDoc = orchestratorService.ingestDocument(
                workspaceId,
                clerkUserId,
                "invoice.pdf",
                DocumentType.INVOICE_RECEIPT,
                fileBytes
        );

        assertThat(savedDoc).isNotNull();
        assertThat(savedDoc.getIngestionStatus()).isEqualTo(IngestionStatus.READY);
        assertThat(savedDoc.getTotalPages()).isEqualTo(1);

        // Verify FactReconcilerService was invoked and ledger updated
        verify(factReconcilerService, times(1)).reconcile(eq(initialLedger), eq(indexerFacts), eq(ruleFacts));
        assertThat(workspace.getFactLedger().extractedFacts().estimatedFinancialLoss()).isEqualTo(15000.0);
        assertThat(workspace.getFactLedger().extractedFacts().oppositeParties().get(0).name()).isEqualTo("Retail Merchant");
        assertThat(workspace.getFactLedger().ruleEvaluationFacts().counterpartyType()).isEqualTo("Retail Merchant");
    }

    @Test
    @DisplayName("CRITICAL: Ingesting JUDGMENT_PRECEDENT stores IracBrief summary but NEVER modifies CaseFactLedger")
    void ingestingJudgmentPrecedentStoresIracBriefSummaryButNeverModifiesFactLedger() {
        PartyEntity client = new PartyEntity("Client", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        PartyEntity originalDefendant = new PartyEntity("Original Defendant", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);

        ExtractedFacts originalFacts = new ExtractedFacts(
                com.app.refirm.triage.domain.LegalDomain.CONSUMER_RIGHTS,
                client,
                null,
                List.of(originalDefendant),
                List.of(),
                25000.0
        );
        ExtractedFactsDTO originalRuleFacts = new ExtractedFactsDTO(
                LegalDomain.CONSUMER,
                25000L,
                "Mumbai",
                "Original Defendant",
                null
        );
        CaseFactLedger originalLedger = new CaseFactLedger(
                1,
                originalFacts,
                originalRuleFacts,
                Set.of(),
                Instant.now()
        );

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setFactLedger(originalLedger);

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        List<ExtractedPage> judgmentPages = List.of(
                new ExtractedPage(1, "SUPREME COURT OF INDIA\nState of Punjab v. ABC Ltd\nHELD: The respondent is liable for damages.")
        );
        byte[] fileBytes = "precedent judgment pdf".getBytes();
        when(pageExtractor.extractPages("precedent.pdf", fileBytes)).thenReturn(judgmentPages);
        when(qualityGate.evaluate(judgmentPages))
                .thenReturn(new OcrQualityGate.QualityCheckResult(true, null, judgmentPages));

        IracBrief iracBrief = new IracBrief(
                "Facts of dispute between State of Punjab and ABC Ltd",
                List.of("Whether State is liable for breach of contract"),
                List.of("Article 299", "Section 73 Indian Contract Act"),
                "The State is bound by ordinary contract law principles",
                "Appeal dismissed with costs"
        );
        StructuredDocumentSummary summary = new StructuredDocumentSummary(
                DocumentType.JUDGMENT_PRECEDENT,
                "State of Punjab v. ABC Ltd",
                "Supreme Court ruling on state contract liability",
                List.of("1998-05-12: Tender awarded", "2001-03-10: High Court verdict"),
                List.of("State of Punjab (Appellant)", "ABC Ltd (Respondent)"),
                List.of("INR 1,20,00,000 claimed"),
                iracBrief
        );
        DocumentOkfManifest manifest = new DocumentOkfManifest(
                "Precedent judgment",
                List.of("State of Punjab", "ABC Ltd"),
                List.of("2001-03-10"),
                List.of("INR 1,20,00,000"),
                Map.of("ratio", List.of(1))
        );

        // Precedent contamination guardrail: indexer output returns null facts for JUDGMENT_PRECEDENT
        when(okfIndexer.indexDocument("precedent.pdf", DocumentType.JUDGMENT_PRECEDENT, judgmentPages))
                .thenReturn(new DocumentOkfIndexer.IndexingOutput(manifest, summary, null, null));

        WorkspaceDocument savedDoc = orchestratorService.ingestDocument(
                workspaceId,
                clerkUserId,
                "precedent.pdf",
                DocumentType.JUDGMENT_PRECEDENT,
                fileBytes
        );

        assertThat(savedDoc).isNotNull();
        assertThat(savedDoc.getIngestionStatus()).isEqualTo(IngestionStatus.READY);
        assertThat(savedDoc.getStructuredSummary()).isNotNull();
        assertThat(savedDoc.getStructuredSummary().iracBrief()).isNotNull();
        assertThat(savedDoc.getStructuredSummary().iracBrief().ratioDecidendi())
                .isEqualTo("The State is bound by ordinary contract law principles");

        // CRITICAL CHECK: Fact reconciler MUST NEVER be called
        verify(factReconcilerService, never()).reconcile(any(), any(), any());

        // CRITICAL CHECK: CaseFactLedger must be completely untouched
        assertThat(workspace.getFactLedger()).isSameAs(originalLedger);
        assertThat(workspace.getFactLedger().extractedFacts().oppositeParties().get(0).name()).isEqualTo("Original Defendant");
        assertThat(workspace.getFactLedger().extractedFacts().estimatedFinancialLoss()).isEqualTo(25000.0);
    }
}
