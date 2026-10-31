package com.app.refirm.inspector.dto;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Unified Right-Pane Inspector (Wireframe 2B: Particulars | Cited | Statutes | Relevant).
 */
public final class InspectorDTOs {

    private InspectorDTOs() {}

    public record CountsHeader(
            int citedAuthoritiesCount,
            int statutesCount,
            int missingParticularsCount
    ) {}

    public record ParticularFieldInspectorItem(
            String key,
            String label,
            String sectionGroup, // 'LOCKED_LEDGER' | 'BODY' | 'VERIFICATION'
            String value,
            boolean isLockedInLedger,
            boolean required
    ) {}

    public record JudgmentAuthorityCard(
            String authorityId,
            String caseTitle,
            String courtName,
            String judgmentDate,
            String primaryCitation,
            List<String> equivalentCitations,
            String sourceType, // 'WORKSPACE_UPLOADED_JUDGMENT' | 'VERIFIED_CORPUS'
            UUID sourceDocumentId,
            String snippetInDraft,
            Integer paragraphNumberInDraft,
            String summary,
            List<String> keyPropositions,
            boolean savedToMatter
    ) {
        public JudgmentAuthorityCard {
            equivalentCitations = equivalentCitations != null ? equivalentCitations : Collections.emptyList();
            keyPropositions = keyPropositions != null ? keyPropositions : Collections.emptyList();
        }
    }

    public record StatuteProvisionInspectorItem(
            String statutePinId,
            String sectionHeader,
            String parentActTitle,
            String cleanBareActText
    ) {}

    public record ActSummaryItem(
            String actTitle,
            List<String> sectionsUsed
    ) {
        public ActSummaryItem {
            sectionsUsed = sectionsUsed != null ? sectionsUsed : Collections.emptyList();
        }
    }

    public record StatutesTabDTO(
            List<StatuteProvisionInspectorItem> provisions,
            List<ActSummaryItem> actsSummary
    ) {
        public StatutesTabDTO {
            provisions = provisions != null ? provisions : Collections.emptyList();
            actsSummary = actsSummary != null ? actsSummary : Collections.emptyList();
        }
    }

    public record RightPaneInspectorResponse(
            CountsHeader countsHeader,
            List<ParticularFieldInspectorItem> particularsTab,
            List<JudgmentAuthorityCard> citedTab,
            StatutesTabDTO statutesTab,
            List<JudgmentAuthorityCard> relevantTab
    ) {
        public RightPaneInspectorResponse {
            particularsTab = particularsTab != null ? particularsTab : Collections.emptyList();
            citedTab = citedTab != null ? citedTab : Collections.emptyList();
            relevantTab = relevantTab != null ? relevantTab : Collections.emptyList();
        }
    }

    public record DrawerPage(
            int pageNumber,
            String pageMarkdown,
            List<String> highlightedRatioPassages
    ) {
        public DrawerPage {
            highlightedRatioPassages = highlightedRatioPassages != null ? highlightedRatioPassages : Collections.emptyList();
        }
    }

    public record InAppJudgmentDrawerResponse(
            String authorityId,
            String caseTitle,
            String primaryCitation,
            List<String> equivalentCitations,
            com.app.refirm.workspace.domain.IracBrief iracBrief,
            List<DrawerPage> fullTextPages
    ) {
        public InAppJudgmentDrawerResponse {
            equivalentCitations = equivalentCitations != null ? equivalentCitations : Collections.emptyList();
            fullTextPages = fullTextPages != null ? fullTextPages : Collections.emptyList();
        }
    }

    public record MutateDraftCitationRequest(
            String action, // 'USE_IN_DRAFT' | 'REPLACE' | 'REMOVE'
            String targetAuthorityId,
            String replacementAuthorityId,
            String targetBlockKey
    ) {}
}
