package com.app.refirm.templates.controller;

import com.app.refirm.templates.controller.dto.AiPopulateRequest;
import com.app.refirm.templates.controller.dto.InitializeDraftRequest;
import com.app.refirm.templates.controller.dto.RefineBlockRequest;
import com.app.refirm.templates.controller.dto.UpdateBlockRequest;
import com.app.refirm.templates.controller.dto.WorkspaceDraftDTO;
import com.app.refirm.templates.domain.TemplateCategory;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateRecommendation;
import com.app.refirm.templates.domain.TemplateStage;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.templates.service.HybridTemplateDraftService;
import com.app.refirm.templates.service.TemplateRecommendationService;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for OKF Template Discovery, Procedural Recommendations,
 * 0-Token Draft Initialization, Bounded AI Block Population, and Single-Block Refinement.
 */
@RestController
public class TemplateDraftController {

    private static final Logger log = LoggerFactory.getLogger(TemplateDraftController.class);

    private final TemplateRegistryService registryService;
    private final TemplateRecommendationService recommendationService;
    private final HybridTemplateDraftService draftService;
    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDraftRepository draftRepository;

    @Autowired
    public TemplateDraftController(
            TemplateRegistryService registryService,
            TemplateRecommendationService recommendationService,
            HybridTemplateDraftService draftService,
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDraftRepository draftRepository
    ) {
        this.registryService = registryService;
        this.recommendationService = recommendationService;
        this.draftService = draftService;
        this.workspaceRepository = workspaceRepository;
        this.draftRepository = draftRepository;
    }

    // ─── 1. TEMPLATE REGISTRY DISCOVERY ───

    @GetMapping({"/templates", "/api/v1.0/templates"})
    public ResponseEntity<List<TemplateManifest>> listTemplates(
            @RequestParam(required = false) TemplateCategory category,
            @RequestParam(required = false) LegalDomain domain,
            @RequestParam(required = false) TemplateStage stage
    ) {
        if (domain != null && stage != null) {
            return ResponseEntity.ok(registryService.getByDomainAndStage(domain, stage));
        }
        if (category != null) {
            return ResponseEntity.ok(registryService.getByCategory(category));
        }
        if (domain != null) {
            return ResponseEntity.ok(registryService.getByDomain(domain));
        }
        return ResponseEntity.ok(registryService.getAllTemplates());
    }

    @GetMapping({"/templates/{templateId}", "/api/v1.0/templates/{templateId}"})
    public ResponseEntity<TemplateManifest> getTemplate(@PathVariable String templateId) {
        try {
            return ResponseEntity.ok(registryService.getTemplateOrThrow(templateId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ─── 2. SMART PROCEDURAL RECOMMENDATIONS ───

    @GetMapping({"/workspaces/{workspaceId}/templates/recommendations", "/api/v1.0/workspaces/{workspaceId}/templates/recommendations"})
    public ResponseEntity<List<TemplateRecommendation>> getRecommendations(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) TemplateStage stage,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        return ResponseEntity.ok(recommendationService.recommendForWorkspace(workspace, stage));
    }

    // ─── 3. VERSIONED WORKSPACE DRAFTS ───

    @PostMapping({"/workspaces/{workspaceId}/drafts", "/api/v1.0/workspaces/{workspaceId}/drafts"})
    public ResponseEntity<WorkspaceDraftDTO> initializeDraft(
            @PathVariable UUID workspaceId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody InitializeDraftRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        if (request == null || request.templateId() == null || request.templateId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateId is required");
        }

        try {
            WorkspaceDraft draft = draftService.initializeDraft(
                    workspaceId,
                    clerkUserId,
                    request.templateId(),
                    request.userProvidedOverrides()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceDraftDTO.from(draft, "v1 · Initialized at 0 tokens", Collections.emptyList()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping({"/workspaces/{workspaceId}/drafts", "/api/v1.0/workspaces/{workspaceId}/drafts"})
    public ResponseEntity<List<WorkspaceDraftDTO>> listDrafts(
            @PathVariable UUID workspaceId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        List<WorkspaceDraft> drafts = draftRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(workspaceId, clerkUserId);
        List<WorkspaceDraftDTO> dtos = drafts.stream()
                .map(d -> WorkspaceDraftDTO.from(d, "v" + d.getVersion() + " draft", Collections.emptyList()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping({"/workspaces/{workspaceId}/drafts/{draftId}", "/api/v1.0/workspaces/{workspaceId}/drafts/{draftId}"})
    public ResponseEntity<WorkspaceDraftDTO> getDraft(
            @PathVariable UUID workspaceId,
            @PathVariable UUID draftId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftId, workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: " + draftId));
        return ResponseEntity.ok(WorkspaceDraftDTO.from(draft, "v" + draft.getVersion() + " draft", Collections.emptyList()));
    }

    @PatchMapping({"/workspaces/{workspaceId}/drafts/{draftId}/particulars", "/api/v1.0/workspaces/{workspaceId}/drafts/{draftId}/particulars"})
    public ResponseEntity<WorkspaceDraftDTO> updateParticulars(
            @PathVariable UUID workspaceId,
            @PathVariable UUID draftId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody Map<String, String> updatedParticulars
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        try {
            WorkspaceDraft draft = draftService.updateParticulars(
                    workspaceId,
                    draftId,
                    clerkUserId,
                    updatedParticulars
            );
            List<String> modified = updatedParticulars != null ? new ArrayList<>(updatedParticulars.keySet()) : Collections.emptyList();
            return ResponseEntity.ok(WorkspaceDraftDTO.from(draft, "v" + draft.getVersion() + " · Updated particulars; locked blocks intact", modified));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping({"/workspaces/{workspaceId}/drafts/{draftId}/ai-populate", "/api/v1.0/workspaces/{workspaceId}/drafts/{draftId}/ai-populate"})
    public ResponseEntity<WorkspaceDraftDTO> populateAiNarrativeBlocks(
            @PathVariable UUID workspaceId,
            @PathVariable UUID draftId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody(required = false) AiPopulateRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        String customInstruction = request != null ? request.customLawyerInstruction() : null;

        try {
            WorkspaceDraft draft = draftService.populateAiNarrativeBlocks(
                    workspaceId,
                    draftId,
                    clerkUserId,
                    customInstruction
            );
            List<String> modified = draft.getGeneratedAiBlocks() != null ? new ArrayList<>(draft.getGeneratedAiBlocks().keySet()) : Collections.emptyList();
            return ResponseEntity.ok(WorkspaceDraftDTO.from(draft, "v" + draft.getVersion() + " · Synthesized AI narrative blocks", modified));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("AI narrative synthesis failed for draft {}: {}", draftId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping({"/workspaces/{workspaceId}/drafts/{draftId}/blocks/{blockKey}/refine", "/api/v1.0/workspaces/{workspaceId}/drafts/{draftId}/blocks/{blockKey}/refine"})
    public ResponseEntity<WorkspaceDraftDTO> refineSingleAiBlock(
            @PathVariable UUID workspaceId,
            @PathVariable UUID draftId,
            @PathVariable String blockKey,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody(required = false) RefineBlockRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        String instruction = request != null ? request.lawyerInstruction() : "";
        boolean lock = request != null && request.lockAfterRefine() != null ? request.lockAfterRefine() : true;

        try {
            WorkspaceDraft draft = draftService.refineSingleAiBlock(
                    workspaceId,
                    draftId,
                    clerkUserId,
                    blockKey,
                    instruction,
                    lock
            );
            return ResponseEntity.ok(WorkspaceDraftDTO.from(draft, "v" + draft.getVersion() + " · Refined block '" + blockKey + "'", List.of(blockKey)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("AI refinement failed for block {} in draft {}: {}", blockKey, draftId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PatchMapping({"/workspaces/{workspaceId}/drafts/{draftId}/blocks/{blockKey}", "/api/v1.0/workspaces/{workspaceId}/drafts/{draftId}/blocks/{blockKey}"})
    public ResponseEntity<WorkspaceDraftDTO> updateAiBlockManually(
            @PathVariable UUID workspaceId,
            @PathVariable UUID draftId,
            @PathVariable String blockKey,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody UpdateBlockRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        String manualContent = request != null && request.manualMarkdownContent() != null ? request.manualMarkdownContent() : "";
        boolean lock = request != null && request.lockBlock() != null ? request.lockBlock() : false;

        try {
            WorkspaceDraft draft = draftService.updateAiBlockManually(
                    workspaceId,
                    draftId,
                    clerkUserId,
                    blockKey,
                    manualContent,
                    lock
            );
            return ResponseEntity.ok(WorkspaceDraftDTO.from(draft, "v" + draft.getVersion() + " · Manually updated '" + blockKey + "'", List.of(blockKey)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private String getAuthenticatedUserId(String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return headerUserId.trim();
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            return "test_user_dev";
        }
        return authentication.getName();
    }
}
