package com.app.refirm.arguments.controller;

import com.app.refirm.arguments.controller.dto.AppendRebuttalRequest;
import com.app.refirm.arguments.controller.dto.ArgumentSheetResponse;
import com.app.refirm.arguments.controller.dto.GenerateArgumentSheetRequest;
import com.app.refirm.arguments.controller.dto.UpdateSubmissionRequest;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.arguments.service.ArgumentPrepService;
import com.app.refirm.workspace.domain.PartyPerspective;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Courtroom Argument Sheet generation, adversarial Rebuttal Matrix expansion,
 * Bench Grill Simulation, and manual submission locking.
 */
@RestController
public class ArgumentPrepController {

    private static final Logger log = LoggerFactory.getLogger(ArgumentPrepController.class);

    private final ArgumentPrepService argumentPrepService;
    private final WorkspaceArgumentSheetRepository argumentSheetRepository;
    private final LegalWorkspaceRepository workspaceRepository;
    private final com.app.refirm.arguments.service.ArgumentMarkdownRenderer markdownRenderer;
    private final com.app.refirm.inspector.service.InspectorService inspectorService;

    @Autowired
    public ArgumentPrepController(
            ArgumentPrepService argumentPrepService,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            LegalWorkspaceRepository workspaceRepository,
            com.app.refirm.arguments.service.ArgumentMarkdownRenderer markdownRenderer,
            @Autowired(required = false) com.app.refirm.inspector.service.InspectorService inspectorService
    ) {
        this.argumentPrepService = argumentPrepService;
        this.argumentSheetRepository = argumentSheetRepository;
        this.workspaceRepository = workspaceRepository;
        this.markdownRenderer = markdownRenderer;
        this.inspectorService = inspectorService;
    }

    @PostMapping({"/workspaces/{workspaceId}/arguments", "/api/v1.0/workspaces/{workspaceId}/arguments"})
    public ResponseEntity<ArgumentSheetResponse> generateArgumentSheet(
            @PathVariable UUID workspaceId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody(required = false) GenerateArgumentSheetRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        PartyPerspective perspective = request != null ? request.partyPerspective() : null;
        String customFocus = request != null ? request.customFocusInstruction() : null;

        WorkspaceArgumentSheet sheet = argumentPrepService.generateArgumentSheet(
                workspaceId,
                clerkUserId,
                perspective,
                customFocus
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ArgumentSheetResponse.from(sheet, workspace));
    }

    @GetMapping({"/workspaces/{workspaceId}/arguments", "/api/v1.0/workspaces/{workspaceId}/arguments"})
    public ResponseEntity<List<ArgumentSheetResponse>> listArgumentSheets(
            @PathVariable UUID workspaceId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        List<WorkspaceArgumentSheet> sheets = argumentSheetRepository
                .findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(workspaceId, clerkUserId);

        List<ArgumentSheetResponse> responses = sheets.stream()
                .map(s -> {
                    ensureFiveArgRendering(s, workspace);
                    return ArgumentSheetResponse.from(s, workspace);
                })
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping({"/workspaces/{workspaceId}/arguments/{sheetId}", "/api/v1.0/workspaces/{workspaceId}/arguments/{sheetId}"})
    public ResponseEntity<ArgumentSheetResponse> getArgumentSheet(
            @PathVariable UUID workspaceId,
            @PathVariable UUID sheetId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        WorkspaceArgumentSheet sheet = argumentSheetRepository
                .findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Argument sheet not found: " + sheetId));

        ensureFiveArgRendering(sheet, workspace);
        return ResponseEntity.ok(ArgumentSheetResponse.from(sheet, workspace));
    }

    private void ensureFiveArgRendering(WorkspaceArgumentSheet sheet, LegalWorkspace workspace) {
        if (sheet == null || sheet.getArgumentDossier() == null) return;
        String cur = sheet.getRenderedMarkdown();
        boolean needsReRender = cur == null
                || !cur.contains("### 2B. APPLICABLE STATUTORY FRAMEWORK")
                || !cur.contains("| Opponent's Likely Point |")
                || com.app.refirm.arguments.service.ArgumentMarkdownRenderer.containsLegacyVaultNoise(cur);
        if (needsReRender) {
            List<com.app.refirm.triage.domain.StatutePin> statutePack = (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null)
                    ? workspace.getLatestDraftingContext().statutePack()
                    : List.of();
            boolean isStale = sheet.isStaleComparedTo(workspace);
            String fresh = markdownRenderer.renderToMarkdown(
                    sheet.getTitle(),
                    sheet.getPartyPerspective(),
                    sheet.getArgumentDossier(),
                    isStale,
                    statutePack
            );
            sheet.setRenderedMarkdown(fresh);
            try {
                argumentSheetRepository.save(sheet);
            } catch (Exception e) {
                log.warn("Failed to persist re-rendered argument sheet markdown: {}", e.getMessage());
            }
        }
    }

    @PostMapping({"/workspaces/{workspaceId}/arguments/{sheetId}/rebuttals", "/api/v1.0/workspaces/{workspaceId}/arguments/{sheetId}/rebuttals"})
    public ResponseEntity<ArgumentSheetResponse> appendTargetedRebuttal(
            @PathVariable UUID workspaceId,
            @PathVariable UUID sheetId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody AppendRebuttalRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        if (request == null || request.oppositePartyContention() == null || request.oppositePartyContention().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "oppositePartyContention must not be blank");
        }

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        WorkspaceArgumentSheet updatedSheet = argumentPrepService.appendTargetedRebuttal(
                workspaceId,
                sheetId,
                clerkUserId,
                request.oppositePartyContention().trim()
        );

        return ResponseEntity.ok(ArgumentSheetResponse.from(updatedSheet, workspace));
    }

    @PatchMapping({"/workspaces/{workspaceId}/arguments/{sheetId}/submissions/{submissionId}", "/api/v1.0/workspaces/{workspaceId}/arguments/{sheetId}/submissions/{submissionId}"})
    public ResponseEntity<ArgumentSheetResponse> updateSubmission(
            @PathVariable UUID workspaceId,
            @PathVariable UUID sheetId,
            @PathVariable String submissionId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody UpdateSubmissionRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        if (request == null || request.submission() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Updated submission payload must not be null");
        }

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found: " + workspaceId));

        WorkspaceArgumentSheet updatedSheet = argumentPrepService.lockOrUpdateSubmissionManually(
                workspaceId,
                sheetId,
                clerkUserId,
                submissionId,
                request.submission(),
                request.lockSubmission()
        );

        return ResponseEntity.ok(ArgumentSheetResponse.from(updatedSheet, workspace));
    }

    @PostMapping({"/workspaces/{workspaceId}/arguments/{sheetId}/citations/mutate", "/api/v1.0/workspaces/{workspaceId}/arguments/{sheetId}/citations/mutate"})
    public ResponseEntity<Map<String, Object>> mutateArgumentCitation(
            @PathVariable UUID workspaceId,
            @PathVariable UUID sheetId,
            @RequestHeader(value = "X-Clerk-User-Id", required = false) String headerUserId,
            @RequestBody com.app.refirm.inspector.dto.InspectorDTOs.MutateDraftCitationRequest request
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        if (inspectorService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "InspectorService not available");
        }
        Map<String, Object> result = inspectorService.mutateArgumentCitation(workspaceId, sheetId, clerkUserId, request);
        return ResponseEntity.ok(result);
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
