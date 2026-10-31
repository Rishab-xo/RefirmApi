package com.app.refirm.workspace.controller.dto;

import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.WorkspaceMode;
import com.app.refirm.workspace.entity.LegalWorkspace;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight summary DTO for advocate sidebar lists.
 * Distinguishes QUICK_CONSULT (bottom chat list) from CASE_PROJECT (top matters list).
 */
public record WorkspaceSummaryItemDTO(
        UUID workspaceId,
        WorkspaceMode mode,
        String title,
        String linkedMatterTitle,
        String clientName,
        LegalDomain primaryDomain,
        PartyPerspective partyPerspective,
        Instant updatedAt,
        Boolean hasArgumentSheet
) {
    public WorkspaceSummaryItemDTO(
            UUID workspaceId,
            WorkspaceMode mode,
            String title,
            String linkedMatterTitle,
            String clientName,
            LegalDomain primaryDomain,
            PartyPerspective partyPerspective,
            Instant updatedAt
    ) {
        this(workspaceId, mode, title, linkedMatterTitle, clientName, primaryDomain, partyPerspective, updatedAt, false);
    }

    public static WorkspaceSummaryItemDTO from(LegalWorkspace ws, String linkedMatterTitle) {
        return from(ws, linkedMatterTitle, false);
    }

    public static WorkspaceSummaryItemDTO from(LegalWorkspace ws, String linkedMatterTitle, boolean hasArgumentSheet) {
        return new WorkspaceSummaryItemDTO(
                ws.getWorkspaceId(),
                ws.getMode(),
                ws.getTitle(),
                linkedMatterTitle,
                ws.getClientName(),
                ws.getPrimaryDomain(),
                ws.getPartyPerspective(),
                ws.getUpdatedAt(),
                hasArgumentSheet
        );
    }
}

