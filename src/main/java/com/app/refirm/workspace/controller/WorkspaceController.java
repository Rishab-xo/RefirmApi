package com.app.refirm.workspace.controller;

import com.app.refirm.workspace.controller.dto.*;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.WorkspaceMode;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.WorkspaceChatResponse;
import com.app.refirm.workspace.service.FactReconcilerService;
import com.app.refirm.workspace.service.WorkspaceAssistantService;
import com.app.refirm.workspace.service.WorkspaceOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * REST controller managing Refirm LegalWorkspaces, mode promotion, and workspace-scoped triage.
 * Accessible under {@code /api/v1.0/workspaces}.
 */
@RestController
@RequestMapping({"/workspaces", "/api/v1.0/workspaces"})
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceOrchestratorService orchestratorService;
    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final FactReconcilerService factReconcilerService;
    private final WorkspaceAssistantService assistantService;
    private final com.app.refirm.workspace.service.WorkspacePromptRouterService promptRouterService;
    private final com.app.refirm.dashboard.service.MatterDashboardService dashboardService;
    private final com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository argumentSheetRepository;

    @Autowired
    public WorkspaceController(
            WorkspaceOrchestratorService orchestratorService,
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            FactReconcilerService factReconcilerService,
            @Autowired(required = false) WorkspaceAssistantService assistantService,
            @Autowired(required = false) com.app.refirm.workspace.service.WorkspacePromptRouterService promptRouterService,
            @Autowired(required = false) com.app.refirm.dashboard.service.MatterDashboardService dashboardService,
            @Autowired(required = false) com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository argumentSheetRepository
    ) {
        this.orchestratorService = orchestratorService;
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.factReconcilerService = factReconcilerService;
        this.assistantService = assistantService;
        this.promptRouterService = promptRouterService;
        this.dashboardService = dashboardService;
        this.argumentSheetRepository = argumentSheetRepository;
    }

    public WorkspaceController(
            WorkspaceOrchestratorService orchestratorService,
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            FactReconcilerService factReconcilerService
    ) {
        this(orchestratorService, workspaceRepository, documentRepository, factReconcilerService, null, null, null, null);
    }

    /**
     * POST /api/v1.0/workspaces/quick-consult
     * Creates a fast Quick Consult workspace ("Normal Usage").
     */
    @PostMapping("/quick-consult")
    public ResponseEntity<LegalWorkspace> createQuickConsult(
            @RequestBody(required = false) CreateQuickConsultRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();
        PartyPerspective perspective = request != null && request.perspective() != null
                ? request.perspective()
                : PartyPerspective.CLAIMANT_PETITIONER;

        LegalWorkspace workspace = orchestratorService.createQuickConsult(clerkUserId, perspective);
        return ResponseEntity.status(HttpStatus.CREATED).body(workspace);
    }

    /**
     * POST /api/v1.0/workspaces/case-projects
     * Directly creates a formal Case Project workspace ("Case Related").
     */
    @PostMapping("/case-projects")
    public ResponseEntity<LegalWorkspace> createCaseProject(
            @RequestBody CreateCaseProjectRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();
        String title = request != null ? request.caseTitle() : "Untitled Case Matter";
        String client = request != null ? request.clientName() : null;
        PartyPerspective perspective = request != null && request.perspective() != null
                ? request.perspective()
                : PartyPerspective.CLAIMANT_PETITIONER;

        LegalWorkspace workspace = orchestratorService.createCaseProject(clerkUserId, title, client, perspective);
        return ResponseEntity.status(HttpStatus.CREATED).body(workspace);
    }

    /**
     * GET /api/v1.0/workspaces?mode=QUICK_CONSULT | CASE_PROJECT
     * Lists workspaces for the advocate's sidebar ("Chat list" vs "Case list").
     */
    @GetMapping
    public ResponseEntity<List<WorkspaceSummaryItemDTO>> listWorkspaces(
            @RequestParam(required = false) WorkspaceMode mode
    ) {
        String clerkUserId = getAuthenticatedUserId();
        List<LegalWorkspace> workspaces = (mode != null)
                ? workspaceRepository.findByClerkUserIdAndModeAndTemporaryFalseOrderByUpdatedAtDesc(clerkUserId, mode)
                : workspaceRepository.findByClerkUserIdAndTemporaryFalseOrderByUpdatedAtDesc(clerkUserId);

        List<WorkspaceSummaryItemDTO> summaryItems = workspaces.stream()
                .map(ws -> {
                    String linkedTitle = null;
                    if (ws.getMode() == WorkspaceMode.QUICK_CONSULT && ws.getClientName() != null) {
                        linkedTitle = ws.getClientName();
                    }
                    boolean hasArgs = argumentSheetRepository != null && argumentSheetRepository.existsByWorkspaceId(ws.getWorkspaceId());
                    return WorkspaceSummaryItemDTO.from(ws, linkedTitle, hasArgs);
                })
                .toList();

        return ResponseEntity.ok(summaryItems);
    }


    /**
     * GET /api/v1.0/workspaces/{workspaceId}
     * Returns workspace details, factLedger, latestBriefMarkdown, and lightweight document catalog.
     */
    @GetMapping("/{workspaceId}")
    public ResponseEntity<WorkspaceDetailResponse> getWorkspace(
            @PathVariable UUID workspaceId
    ) {
        String clerkUserId = getAuthenticatedUserId();
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        List<WorkspaceDocument> docs = documentRepository
                .findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(workspaceId, clerkUserId);

        List<WorkspaceDocumentSummary> docSummaries = docs.stream()
                .map(WorkspaceDocumentSummary::fromEntity)
                .toList();

        return ResponseEntity.ok(new WorkspaceDetailResponse(workspace, docSummaries));
    }

    /**
     * DELETE /api/v1.0/workspaces/{workspaceId}
     * Permanently deletes a workspace and all cascading child records from the database.
     */
    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable UUID workspaceId
    ) {
        String clerkUserId = getAuthenticatedUserId();
        orchestratorService.deleteWorkspace(workspaceId, clerkUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1.0/workspaces/{workspaceId}/promote
     * Promotes a QUICK_CONSULT workspace to a CASE_PROJECT.
     */
    @PostMapping("/{workspaceId}/promote")
    public ResponseEntity<?> promoteWorkspace(
            @PathVariable UUID workspaceId,
            @RequestBody PromoteWorkspaceRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Promote request payload is required");
        }

        try {
            if (dashboardService != null) {
                com.app.refirm.dashboard.dto.MatterDashboardDTOs.PromoteToMatterRequest dtoReq =
                        new com.app.refirm.dashboard.dto.MatterDashboardDTOs.PromoteToMatterRequest(
                                request.caseTitle(),
                                request.clientName(),
                                request.perspective(),
                                request.excludedDocumentIds()
                        );
                return ResponseEntity.ok(dashboardService.promoteToMatter(workspaceId, clerkUserId, dtoReq));
            }

            orchestratorService.promoteToCaseProject(
                    workspaceId,
                    clerkUserId,
                    request.caseTitle(),
                    request.clientName(),
                    request.perspective(),
                    request.excludedDocumentIds()
            );
            return ResponseEntity.ok(null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * POST /api/v1.0/workspaces/{targetMatterWorkspaceId}/attach-chat/{sourceQuickConsultWorkspaceId}
     * Merges chat history, reconciles facts, moves documents and drafts into the target matter.
     */
    @PostMapping("/{targetMatterWorkspaceId}/attach-chat/{sourceQuickConsultWorkspaceId}")
    public ResponseEntity<com.app.refirm.dashboard.dto.MatterDashboardDTOs.MatterDashboardResponse> attachChatToMatter(
            @PathVariable UUID targetMatterWorkspaceId,
            @PathVariable UUID sourceQuickConsultWorkspaceId
    ) {
        String clerkUserId = getAuthenticatedUserId();
        if (dashboardService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dashboard service not available");
        }
        try {
            return ResponseEntity.ok(dashboardService.attachChatToMatter(targetMatterWorkspaceId, sourceQuickConsultWorkspaceId, clerkUserId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * GET /api/v1.0/workspaces/{workspaceId}/dashboard
     * Returns full Matter Dashboard War-Room payload (Wireframe 3).
     */
    @GetMapping("/{workspaceId}/dashboard")
    public ResponseEntity<com.app.refirm.dashboard.dto.MatterDashboardDTOs.MatterDashboardResponse> getMatterDashboard(
            @PathVariable UUID workspaceId
    ) {
        String clerkUserId = getAuthenticatedUserId();
        if (dashboardService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dashboard service not available");
        }
        try {
            return ResponseEntity.ok(dashboardService.getDashboard(workspaceId, clerkUserId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * POST /api/v1.0/workspaces/{workspaceId}/triage
     * Executes the strategic two-pass pipeline inside a workspace and updates the fact ledger.
     */
    @PostMapping("/{workspaceId}/triage")
    public ResponseEntity<LegalWorkspace> runTriage(
            @PathVariable UUID workspaceId,
            @RequestBody TriageWorkspaceRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();
        if (request == null || request.userNarrative() == null || request.userNarrative().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dispute narrative cannot be blank");
        }

        try {
            LegalWorkspace updated = orchestratorService.runStrategicTriageInWorkspace(
                    workspaceId,
                    clerkUserId,
                    request.userNarrative().trim(),
                    request.userLocation() != null ? request.userLocation().trim() : ""
            );
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * POST /api/v1.0/workspaces/{workspaceId}/prompt
     * Submits a user prompt from Home Screen or Active Chat with safe execution routing (View 1B).
     */
    @PostMapping({"/{workspaceIdStr}/prompt", "/prompt"})
    public ResponseEntity<WorkspacePromptResponse> submitPrompt(
            @PathVariable(value = "workspaceIdStr", required = false) String workspaceIdStr,
            @RequestBody WorkspacePromptRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = headerUserId != null && !headerUserId.isBlank()
                ? headerUserId
                : getAuthenticatedUserId();

        UUID workspaceId = null;
        if (workspaceIdStr != null && !workspaceIdStr.isBlank() && !"new".equalsIgnoreCase(workspaceIdStr)) {
            try {
                workspaceId = UUID.fromString(workspaceIdStr);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workspaceId: " + workspaceIdStr);
            }
        }

        if (promptRouterService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Prompt router service not initialized");
        }

        WorkspacePromptResponse response = promptRouterService.handlePrompt(workspaceId, clerkUserId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/v1.0/workspaces/{workspaceId}/facts
     * Allows advocate to manually edit and lock specific fields in CaseFactLedger.
     */
    @PatchMapping("/{workspaceId}/facts")
    public ResponseEntity<CaseFactLedger> updateFactLedger(
            @PathVariable UUID workspaceId,
            @RequestBody UpdateWorkspaceFactsRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId();
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        if (request == null) {
            return ResponseEntity.ok(workspace.getFactLedger());
        }

        if (request.partyPerspective() != null) {
            workspace.setPartyPerspective(request.partyPerspective());
        }

        CaseFactLedger updatedLedger = factReconcilerService.applyLawyerOverride(
                workspace.getFactLedger(),
                request.updatedFacts(),
                request.updatedRuleFacts(),
                request.fieldsToLock()
        );

        workspace.setFactLedger(updatedLedger);
        workspaceRepository.save(workspace);
        return ResponseEntity.ok(updatedLedger);
    }

    /**
     * POST /api/v1.0/workspaces/{workspaceId}/documents
     * Ingests a case document (PDF, TXT, MD) into the workspace vault.
     */
    @PostMapping(value = "/{workspaceId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkspaceDocumentDTO> uploadDocument(
            @PathVariable UUID workspaceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", defaultValue = "EVIDENCE_OTHER") DocumentType documentType,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = headerUserId != null && !headerUserId.isBlank()
                ? headerUserId
                : getAuthenticatedUserId();

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must not be empty");
        }

        try {
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
            WorkspaceDocument doc = orchestratorService.ingestDocument(
                    workspaceId,
                    clerkUserId,
                    fileName,
                    documentType,
                    bytes
            );
            LegalWorkspace ws = workspaceRepository.findById(workspaceId).orElse(null);
            int ledgerVersion = ws != null && ws.getFactLedger() != null ? ws.getFactLedger().version() : 1;
            return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceDocumentDTO.from(doc, ledgerVersion));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to ingest document for workspace {}: {}", workspaceId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to ingest document: " + e.getMessage());
        }
    }

    /**
     * GET /api/v1.0/workspaces/{workspaceId}/documents/{documentId}/summary
     * Returns the StructuredDocumentSummary and DocumentOkfManifest for a specific document.
     */
    @GetMapping("/{workspaceId}/documents/{documentId}/summary")
    public ResponseEntity<DocumentDetailSummaryResponse> getDocumentSummary(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = headerUserId != null && !headerUserId.isBlank()
                ? headerUserId
                : getAuthenticatedUserId();

        WorkspaceDocument doc = documentRepository
                .findByDocumentIdAndWorkspaceIdAndClerkUserId(documentId, workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId));

        return ResponseEntity.ok(new DocumentDetailSummaryResponse(
                doc.getDocumentId(),
                doc.getFileName(),
                doc.getOkfManifest(),
                doc.getStructuredSummary()
        ));
    }

    /**
     * POST /api/v1.0/workspaces/{workspaceId}/chat
     * Queries the workspace legal assistant grounded in CaseFactLedger and Page-OKF pinned pages.
     */
    @PostMapping("/{workspaceId}/chat")
    public ResponseEntity<WorkspaceChatResponse> askAssistant(
            @PathVariable UUID workspaceId,
            @RequestBody WorkspaceChatRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = headerUserId != null && !headerUserId.isBlank()
                ? headerUserId
                : getAuthenticatedUserId();

        if (assistantService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workspace assistant service not initialized");
        }

        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question cannot be blank");
        }

        try {
            WorkspaceChatResponse response = assistantService.askWorkspaceAssistant(
                    workspaceId,
                    clerkUserId,
                    request.question().trim()
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Error asking workspace assistant for workspace {}: {}", workspaceId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Assistant error: " + e.getMessage());
        }
    }

    private String getAuthenticatedUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            return "test_user_dev";
        }
        return authentication.getName();
    }
}
