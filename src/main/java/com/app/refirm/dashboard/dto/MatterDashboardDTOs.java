package com.app.refirm.dashboard.dto;

import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.controller.dto.WorkspaceDocumentDTO;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Matter War-Room Dashboard (Wireframe 3).
 */
public final class MatterDashboardDTOs {

    private MatterDashboardDTOs() {}

    public record PromoteToMatterRequest(
            String caseTitle,
            String clientName,
            PartyPerspective partyPerspective,
            List<UUID> excludedDocumentIds
    ) {
        public PromoteToMatterRequest {
            excludedDocumentIds = excludedDocumentIds != null ? excludedDocumentIds : Collections.emptyList();
        }
    }

    public record MatterStatsSubtitle(
            int chatsCount,
            int filesCount,
            int draftsCount,
            int authoritiesCount
    ) {}

    public record CourtCaseTrackedDTO(
            String cnrNumber,
            String courtName,
            String nextHearingDate,
            String status
    ) {}

    public record MatterChatThreadDTO(
            UUID chatThreadId,
            String title,
            List<String> subtitleBadges,
            Instant updatedAt
    ) {
        public MatterChatThreadDTO {
            subtitleBadges = subtitleBadges != null ? subtitleBadges : Collections.emptyList();
        }
    }

    public record MatterDraftSummaryDTO(
            UUID draftId,
            String title,
            int version,
            Instant updatedAt
    ) {}

    public record MatterArgumentSheetSummaryDTO(
            UUID sheetId,
            String title,
            int version,
            boolean stale,
            Instant updatedAt
    ) {}

    public record MatterDashboardResponse(
            UUID workspaceId,
            String title,
            String clientName,
            LegalDomain primaryDomain,
            PartyPerspective partyPerspective,
            MatterStatsSubtitle statsSubtitle,
            CaseFactLedger factLedger,
            List<CourtCaseTrackedDTO> courtCasesTracked,
            List<MatterChatThreadDTO> chats,
            List<WorkspaceDocumentDTO> files,
            List<MatterDraftSummaryDTO> drafts,
            List<MatterArgumentSheetSummaryDTO> argumentSheets,
            List<JudgmentAuthorityCard> authorities
    ) {
        public MatterDashboardResponse {
            courtCasesTracked = courtCasesTracked != null ? courtCasesTracked : Collections.emptyList();
            chats = chats != null ? chats : Collections.emptyList();
            files = files != null ? files : Collections.emptyList();
            drafts = drafts != null ? drafts : Collections.emptyList();
            argumentSheets = argumentSheets != null ? argumentSheets : Collections.emptyList();
            authorities = authorities != null ? authorities : Collections.emptyList();
        }
    }
}
