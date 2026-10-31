package com.app.refirm.arguments.controller.dto;

import com.app.refirm.arguments.domain.ArgumentDossier;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Courtroom argument sheet response DTO (WorkspaceArgumentSheetDTO) matching the API Contract.
 * Includes versionChangeSummary, stale computation flag, and staleReasonBanner.
 */
public record ArgumentSheetResponse(
        UUID sheetId,
        UUID workspaceId,
        String clerkUserId,
        String title,
        PartyPerspective partyPerspective,
        int version,
        String versionChangeSummary,
        int sourceFactLedgerVersion,
        ArgumentDossier argumentDossier,
        Set<String> lockedSubmissionIds,
        String renderedMarkdown,
        boolean stale,
        String staleReasonBanner,
        Instant createdAt,
        Instant updatedAt
) {
    public static ArgumentSheetResponse from(WorkspaceArgumentSheet sheet, LegalWorkspace workspace) {
        boolean isStale = sheet.isStaleComparedTo(workspace);
        String staleReason = null;
        if (isStale) {
            if (workspace != null && sheet.getPartyPerspective() != workspace.getPartyPerspective()) {
                staleReason = "Workspace party perspective flipped to " + workspace.getPartyPerspective() + ". Regenerate sheet?";
            } else {
                staleReason = "Underlying Case Fact Ledger updated. Regenerate sheet to sync latest evidence.";
            }
        }
        String changeSummary = "v" + sheet.getVersion() + " · Courtroom preparation dossier ready";

        return new ArgumentSheetResponse(
                sheet.getSheetId(),
                sheet.getWorkspaceId(),
                sheet.getClerkUserId(),
                sheet.getTitle(),
                sheet.getPartyPerspective(),
                sheet.getVersion(),
                changeSummary,
                sheet.getSourceFactLedgerVersion(),
                sheet.getArgumentDossier(),
                sheet.getLockedSubmissionIds() != null ? sheet.getLockedSubmissionIds() : Collections.emptySet(),
                com.app.refirm.arguments.service.ArgumentMarkdownRenderer.scrubLegacyVaultSentences(sheet.getRenderedMarkdown()),
                isStale,
                staleReason,
                sheet.getCreatedAt(),
                sheet.getUpdatedAt()
        );
    }
}
