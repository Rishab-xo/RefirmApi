package com.app.refirm.inspector.controller;

import com.app.refirm.inspector.dto.InspectorDTOs.InAppJudgmentDrawerResponse;
import com.app.refirm.inspector.dto.InspectorDTOs.MutateDraftCitationRequest;
import com.app.refirm.inspector.dto.InspectorDTOs.RightPaneInspectorResponse;
import com.app.refirm.inspector.service.AuthorityDrawerService;
import com.app.refirm.inspector.service.InspectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for View 2B: 4 Right-Hand Inspector Pills, In-App Authority Drawer,
 * and 6-Item Citation Context Menu mutations.
 */
@RestController
public class InspectorController {

    private static final Logger log = LoggerFactory.getLogger(InspectorController.class);

    private final InspectorService inspectorService;
    private final AuthorityDrawerService drawerService;

    @Autowired
    public InspectorController(InspectorService inspectorService, AuthorityDrawerService drawerService) {
        this.inspectorService = inspectorService;
        this.drawerService = drawerService;
    }

    /**
     * Unified Right-Pane Inspector Endpoint (Particulars | Cited | Statutes | Relevant).
     * GET /api/v1.0/workspaces/{workspaceId}/inspector?targetType=DRAFT&targetId={draftId}
     */
    @GetMapping({"/workspaces/{workspaceId}/inspector", "/api/v1.0/workspaces/{workspaceId}/inspector"})
    public ResponseEntity<RightPaneInspectorResponse> getInspector(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "DRAFT") String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        RightPaneInspectorResponse response = inspectorService.getInspector(workspaceId, clerkUserId, targetType, targetId);
        return ResponseEntity.ok(response);
    }

    /**
     * Menu Item 1: Open full judgment — In-App IRAC + Full-Text Slide-Over Drawer.
     * GET /api/v1.0/workspaces/{workspaceId}/authorities/{authorityId}/drawer
     */
    @GetMapping({"/workspaces/{workspaceId}/authorities/{authorityId}/drawer", "/api/v1.0/workspaces/{workspaceId}/authorities/{authorityId}/drawer"})
    public ResponseEntity<InAppJudgmentDrawerResponse> getAuthorityDrawer(
            @PathVariable UUID workspaceId,
            @PathVariable String authorityId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        InAppJudgmentDrawerResponse response = drawerService.getDrawer(workspaceId, clerkUserId, authorityId);
        return ResponseEntity.ok(response);
    }

    /**
     * Mutate Draft Citation (USE_IN_DRAFT, REPLACE, REMOVE).
     * POST /api/v1.0/workspaces/{workspaceId}/drafts/{draftId}/citations/mutate
     */
    @PostMapping({"/workspaces/{workspaceId}/drafts/{draftId}/citations/mutate", "/api/v1.0/workspaces/{workspaceId}/drafts/{draftId}/citations/mutate"})
    public ResponseEntity<Map<String, Object>> mutateCitation(
            @PathVariable UUID workspaceId,
            @PathVariable UUID draftId,
            @RequestBody MutateDraftCitationRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        Map<String, Object> result = inspectorService.mutateDraftCitation(workspaceId, draftId, clerkUserId, request);
        return ResponseEntity.ok(result);
    }

    /**
     * Menu Item 6: Save to Matter.
     * POST /api/v1.0/workspaces/{workspaceId}/authorities/{authorityId}/save
     */
    @PostMapping({"/workspaces/{workspaceId}/authorities/{authorityId}/save", "/api/v1.0/workspaces/{workspaceId}/authorities/{authorityId}/save"})
    public ResponseEntity<Map<String, Object>> saveAuthorityToMatter(
            @PathVariable UUID workspaceId,
            @PathVariable String authorityId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        String clerkUserId = getAuthenticatedUserId(headerUserId);
        Map<String, Object> result = drawerService.saveAuthority(workspaceId, clerkUserId, authorityId);
        return ResponseEntity.ok(result);
    }

    private String getAuthenticatedUserId(String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return headerUserId.trim();
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return "test_user_dev";
        }
        return auth.getName();
    }
}
