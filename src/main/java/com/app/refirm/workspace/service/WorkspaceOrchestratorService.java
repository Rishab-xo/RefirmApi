package com.app.refirm.workspace.service;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.TriagePipelineResult;
import com.app.refirm.triage.entities.ConversationTurn;
import com.app.refirm.triage.pipeline.TriagePipelineOrchestrator;
import com.app.refirm.workspace.domain.CaseFactLedger;
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
import com.app.refirm.workspace.repository.WorkspaceSavedAuthorityRepository;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core orchestrator managing the lifecycle of Refirm LegalWorkspaces.
 * Coordinates mode promotion, strategic triage runs, document ingestion, and fact ledger reconciliation.
 */
@Service
public class WorkspaceOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceOrchestratorService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final WorkspaceDocumentPageRepository pageRepository;
    private final FactReconcilerService factReconcilerService;
    private final TriagePipelineOrchestrator triagePipelineOrchestrator;
    private final DocumentPageExtractor pageExtractor;
    private final OcrQualityGate qualityGate;
    private final DocumentOkfIndexer okfIndexer;
    private final WorkspaceSavedAuthorityRepository savedAuthorityRepository;
    private final WorkspaceDraftRepository draftRepository;
    private final WorkspaceArgumentSheetRepository argumentSheetRepository;

    @Autowired
    public WorkspaceOrchestratorService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            @Autowired(required = false) WorkspaceDocumentPageRepository pageRepository,
            FactReconcilerService factReconcilerService,
            TriagePipelineOrchestrator triagePipelineOrchestrator,
            @Autowired(required = false) DocumentPageExtractor pageExtractor,
            @Autowired(required = false) OcrQualityGate qualityGate,
            @Autowired(required = false) DocumentOkfIndexer okfIndexer,
            @Autowired(required = false) WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            @Autowired(required = false) WorkspaceDraftRepository draftRepository,
            @Autowired(required = false) WorkspaceArgumentSheetRepository argumentSheetRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.factReconcilerService = factReconcilerService;
        this.triagePipelineOrchestrator = triagePipelineOrchestrator;
        this.pageExtractor = pageExtractor;
        this.qualityGate = qualityGate;
        this.okfIndexer = okfIndexer;
        this.savedAuthorityRepository = savedAuthorityRepository;
        this.draftRepository = draftRepository;
        this.argumentSheetRepository = argumentSheetRepository;
    }

    public WorkspaceOrchestratorService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceDocumentPageRepository pageRepository,
            FactReconcilerService factReconcilerService,
            TriagePipelineOrchestrator triagePipelineOrchestrator,
            DocumentPageExtractor pageExtractor,
            OcrQualityGate qualityGate,
            DocumentOkfIndexer okfIndexer
    ) {
        this(workspaceRepository, documentRepository, pageRepository, factReconcilerService,
                triagePipelineOrchestrator, pageExtractor, qualityGate, okfIndexer, null, null, null);
    }

    public WorkspaceOrchestratorService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            FactReconcilerService factReconcilerService,
            TriagePipelineOrchestrator triagePipelineOrchestrator
    ) {
        this(workspaceRepository, documentRepository, null, factReconcilerService,
                triagePipelineOrchestrator, null, null, null, null, null, null);
    }

    /**
     * Cleanly and permanently deletes a workspace and all cascading child records from the database.
     * Enforces user authorization (clerkUserId check).
     */
    @Transactional
    public void deleteWorkspace(UUID workspaceId, String clerkUserId) {
        log.info("Permanently deleting workspace {} for clerk user {}", workspaceId, clerkUserId);

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found or access denied: " + workspaceId));

        // 1. Delete granular document pages
        if (pageRepository != null) {
            pageRepository.deleteByWorkspaceId(workspaceId);
        }

        // 2. Delete workspace documents
        if (documentRepository != null) {
            documentRepository.deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        }

        // 3. Delete saved authorities
        if (savedAuthorityRepository != null) {
            savedAuthorityRepository.deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        }

        // 4. Delete drafts
        if (draftRepository != null) {
            draftRepository.deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        }

        // 5. Delete courtroom argument sheets
        if (argumentSheetRepository != null) {
            argumentSheetRepository.deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        }

        // 6. Delete parent workspace
        workspaceRepository.delete(workspace);
        log.info("Workspace {} and all associated records successfully purged from DB", workspaceId);
    }

    /**
     * Initializes a fast Quick Consult workspace ("Normal Usage").
     */
    @Transactional
    public LegalWorkspace createQuickConsult(String clerkUserId, PartyPerspective perspective) {
        log.info("Creating Quick Consult workspace for user: {}", clerkUserId);

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID());
        workspace.setClerkUserId(clerkUserId);
        workspace.setMode(WorkspaceMode.QUICK_CONSULT);
        workspace.setPartyPerspective(perspective != null ? perspective : PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setTitle("Quick Consult");
        workspace.setFactLedger(CaseFactLedger.empty());
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());

        return workspaceRepository.save(workspace);
    }

    /**
     * Directly creates a formal Case Project workspace ("Case Related").
     */
    @Transactional
    public LegalWorkspace createCaseProject(String clerkUserId, String caseTitle, String clientName, PartyPerspective perspective) {
        log.info("Creating Case Project workspace '{}' for client '{}'", caseTitle, clientName);

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID());
        workspace.setClerkUserId(clerkUserId);
        workspace.setMode(WorkspaceMode.CASE_PROJECT);
        workspace.setTitle(caseTitle != null && !caseTitle.isBlank() ? caseTitle : "Untitled Case Matter");
        workspace.setClientName(clientName);
        workspace.setPartyPerspective(perspective != null ? perspective : PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setFactLedger(CaseFactLedger.empty());
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace.setPromotedAt(Instant.now());

        return workspaceRepository.save(workspace);
    }

    /**
     * Promotes an existing Quick Consult workspace to a formal Case Project.
     * Atomically locks the case parameters and sets inclusion flags for uploaded evidence.
     */
    @Transactional
    public LegalWorkspace promoteToCaseProject(
            UUID workspaceId,
            String clerkUserId,
            String caseTitle,
            String clientName,
            PartyPerspective perspective,
            List<UUID> excludedDocumentIds
    ) {
        log.info("Promoting workspace {} to CASE_PROJECT. Title: '{}', Client: '{}'", workspaceId, caseTitle, clientName);

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found or access denied: " + workspaceId));

        workspace.setMode(WorkspaceMode.CASE_PROJECT);
        workspace.setTemporary(false);
        if (caseTitle != null && !caseTitle.isBlank()) {
            workspace.setTitle(caseTitle);
        }
        if (clientName != null) {
            workspace.setClientName(clientName);
        }
        if (perspective != null) {
            workspace.setPartyPerspective(perspective);
        }
        workspace.setPromotedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());

        // Process excluded documents if specified
        if (excludedDocumentIds != null && !excludedDocumentIds.isEmpty()) {
            List<WorkspaceDocument> docs = documentRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(workspaceId, clerkUserId);
            for (WorkspaceDocument doc : docs) {
                if (excludedDocumentIds.contains(doc.getDocumentId())) {
                    doc.setIncludedInCaseContext(false);
                    documentRepository.save(doc);
                    log.info("Document {} excluded from case context in workspace {}", doc.getDocumentId(), workspaceId);
                }
            }
        }

        return workspaceRepository.save(workspace);
    }

    /**
     * Runs the strategic two-pass triage pipeline inside an existing workspace.
     * Reconciles extracted facts against the workspace fact ledger, strictly respecting lawyer locks.
     */
    @Transactional
    public LegalWorkspace runStrategicTriageInWorkspace(
            UUID workspaceId,
            String clerkUserId,
            String userNarrative,
            String userLocation
    ) {
        log.info("Running strategic triage in workspace {} for narrative length {}",
                workspaceId, userNarrative != null ? userNarrative.length() : 0);

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found or access denied: " + workspaceId));

        // 1. Execute deterministic pipeline with context retention
        TriagePipelineResult result = triagePipelineOrchestrator.executeWithContext(userNarrative, userLocation);

        ExtractedFacts extractedFacts = result.draftingContext() != null ? result.draftingContext().facts() : null;

        // 2. Synthesize lightweight rule facts from extracted facts
        ExtractedFactsDTO ruleFacts = synthesizeRuleFacts(extractedFacts, userLocation);

        // 3. Reconcile against canonical CaseFactLedger preserving lawyer locks
        CaseFactLedger reconciledLedger = factReconcilerService.reconcile(
                workspace.getFactLedger(),
                extractedFacts,
                ruleFacts
        );

        workspace.setFactLedger(reconciledLedger);
        boolean isDomainLocked = reconciledLedger != null
                && reconciledLedger.lawyerLockedFields() != null
                && reconciledLedger.lawyerLockedFields().contains("domain");

        if (!isDomainLocked && extractedFacts != null && extractedFacts.domain() != null) {
            workspace.setPrimaryDomain(extractedFacts.domain());
        }
        workspace.setLatestDraftingContext(result.draftingContext());
        workspace.setLatestBriefMarkdown(result.briefMarkdown());
        workspace.setUpdatedAt(Instant.now());

        // Append to chat history audit trail
        workspace.getChatHistory().add(new ConversationTurn("USER", userNarrative, Instant.now()));
        workspace.getChatHistory().add(new ConversationTurn("AI", result.briefMarkdown(), Instant.now()));

        return workspaceRepository.save(workspace);
    }

    /**
     * Ingests a raw file (PDF, TXT, MD) into a workspace document vault.
     * Evaluates OCR quality, indexes Page-OKF manifest + rich summary, stores markdown pages,
     * and performs safe fact reconciliation (strictly avoiding Precedent Contamination).
     */
    @Transactional
    public WorkspaceDocument ingestDocument(
            UUID workspaceId,
            String clerkUserId,
            String fileName,
            DocumentType documentType,
            byte[] fileBytes
    ) {
        log.info("Ingesting document '{}' (type={}) for workspace {}", fileName, documentType, workspaceId);

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found or access denied: " + workspaceId));

        if (pageExtractor == null || qualityGate == null || okfIndexer == null) {
            throw new IllegalStateException("Document ingestion pipeline components are not configured");
        }

        // 1. Extract raw text page-by-page
        List<ExtractedPage> pages = pageExtractor.extractPages(fileName, fileBytes);

        // 2. Evaluate OCR & document legibility
        OcrQualityGate.QualityCheckResult quality = qualityGate.evaluate(pages);

        if (!quality.passed()) {
            log.warn("Document '{}' failed OCR quality gate: {}. Total pages: {}",
                    fileName, quality.failureReason(), pages.size());

            WorkspaceDocument failedDoc = new WorkspaceDocument();
            failedDoc.setWorkspaceId(workspaceId);
            failedDoc.setClerkUserId(clerkUserId);
            failedDoc.setFileName(fileName);
            failedDoc.setDocumentType(documentType != null ? documentType : DocumentType.EVIDENCE_OTHER);
            failedDoc.setTotalPages(pages.size());
            failedDoc.setIngestionStatus(IngestionStatus.FAILED_LOW_QUALITY);
            failedDoc.setIncludedInCaseContext(false);
            failedDoc.setOkfManifest(DocumentOkfManifest.empty());
            failedDoc.setCreatedAt(Instant.now());

            return documentRepository.save(failedDoc);
        }

        // 3. Index Page-OKF manifest, rich summary, and facts via LLM (or fallback)
        DocumentOkfIndexer.IndexingOutput indexingOutput = okfIndexer.indexDocument(
                fileName,
                documentType,
                quality.sanitizedPages()
        );

        // 4. Save WorkspaceDocument metadata
        WorkspaceDocument doc = new WorkspaceDocument();
        doc.setWorkspaceId(workspaceId);
        doc.setClerkUserId(clerkUserId);
        doc.setFileName(fileName);
        doc.setDocumentType(documentType != null ? documentType : DocumentType.EVIDENCE_OTHER);
        doc.setTotalPages(quality.sanitizedPages().size());
        doc.setIngestionStatus(IngestionStatus.READY);
        doc.setIncludedInCaseContext(true);
        doc.setOkfManifest(indexingOutput != null && indexingOutput.manifest() != null ? indexingOutput.manifest() : DocumentOkfManifest.empty());
        doc.setStructuredSummary(indexingOutput != null ? indexingOutput.summary() : null);
        doc.setCreatedAt(Instant.now());

        WorkspaceDocument savedDoc = documentRepository.save(doc);

        // 5. Save granular page rows with standard markdown headers
        if (pageRepository != null && quality.sanitizedPages() != null) {
            List<WorkspaceDocumentPage> pageEntities = new ArrayList<>();
            for (ExtractedPage p : quality.sanitizedPages()) {
                WorkspaceDocumentPage pageEntity = new WorkspaceDocumentPage();
                pageEntity.setDocumentId(savedDoc.getDocumentId());
                pageEntity.setWorkspaceId(workspaceId);
                pageEntity.setPageNumber(p.pageNumber());
                pageEntity.setPageMarkdown("# " + fileName + " — Page " + p.pageNumber() + "\n\n" + (p.rawText() != null ? p.rawText() : ""));
                pageEntities.add(pageEntity);
            }
            pageRepository.saveAll(pageEntities);
        }

        // 6. Safe Fact Reconciliation (strictly blocked for JUDGMENT_PRECEDENT to prevent contamination)
        if (documentType != DocumentType.JUDGMENT_PRECEDENT && indexingOutput != null &&
                (indexingOutput.extractedFacts() != null || indexingOutput.ruleFacts() != null)) {
            CaseFactLedger reconciledLedger = factReconcilerService.reconcile(
                    workspace.getFactLedger(),
                    indexingOutput.extractedFacts(),
                    indexingOutput.ruleFacts()
            );
            workspace.setFactLedger(reconciledLedger);
            workspace.setUpdatedAt(Instant.now());
            workspaceRepository.save(workspace);
        }

        return savedDoc;
    }

    private ExtractedFactsDTO synthesizeRuleFacts(ExtractedFacts facts, String location) {
        if (facts == null) {
            return new ExtractedFactsDTO(null, null, location, null, null);
        }

        com.app.refirm.rules.model.LegalDomain ruleDomain = facts.domain() != null
                ? com.app.refirm.rules.model.LegalDomain.fromStringSafe(facts.domain().name())
                : null;

        Long amount = facts.estimatedFinancialLoss() != null && facts.estimatedFinancialLoss() > 0.0
                ? Math.round(facts.estimatedFinancialLoss())
                : null;

        String counterparty = facts.oppositeParties() != null && !facts.oppositeParties().isEmpty()
                ? facts.oppositeParties().get(0).name()
                : null;

        return new ExtractedFactsDTO(ruleDomain, amount, location, counterparty, null);
    }
}
