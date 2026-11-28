package com.app.refirm.inspector.dto;

import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;

import java.util.Collections;
import java.util.List;

/**
 * DTOs for the Standalone Judgments Library (Wireframe / UI: Topics, NARROW BY ISSUE pills,
 * live counts, Pari Materia statutory banners, and smart hybrid search).
 */
public final class JudgmentLibraryDTOs {

    private JudgmentLibraryDTOs() {}

    public record TopicSummaryItem(
            String topicSlug,
            String title,
            String subtitle,
            String primaryDomain,
            long totalJudgments,
            long supremeCourtJudgments
    ) {}

    public record IssuePillCount(
            String issueSlug,
            String label,
            long count
    ) {}

    public record TopicDetailResponse(
            String topicSlug,
            String title,
            String subtitle,
            String primaryDomain,
            String statutoryBannerTitle,
            String statutoryBannerNote,
            List<IssuePillCount> issuePills,
            List<JudgmentAuthorityCard> judgments
    ) {
        public TopicDetailResponse {
            issuePills = issuePills != null ? issuePills : Collections.emptyList();
            judgments = judgments != null ? judgments : Collections.emptyList();
        }
    }

    public record JudgmentSearchResponse(
            String query,
            int totalResults,
            List<JudgmentAuthorityCard> results
    ) {
        public JudgmentSearchResponse {
            results = results != null ? results : Collections.emptyList();
        }
    }

    public record RelatedJudgmentItem(
            String authorityId,
            String caseTitle,
            Integer decisionYear,
            String courtCode,
            String primaryCitation,
            Integer benchCount,
            boolean isConstitutionBench,
            int citedByCount,
            String goodLawStatus,
            String overruledByAuthorityId,
            String treatmentType,
            String treatmentSnippet,
            String substantiveReason,
            String paraRef,
            String anchorQuote,
            String twoLineRatio,
            List<String> sharedTuples,
            Double similarityScore
    ) {
        public RelatedJudgmentItem {
            sharedTuples = sharedTuples != null ? List.copyOf(sharedTuples) : Collections.emptyList();
        }
    }

    public record RelatedJudgmentsResponse(
            String authorityId,
            int citedByCount,
            int reliesOnCount,
            int negativeTreatmentCount,
            int reliesOnNegativeCount,
            List<RelatedJudgmentItem> citedBy,
            List<RelatedJudgmentItem> reliesOn,
            List<RelatedJudgmentItem> similarCases
    ) {
        public RelatedJudgmentsResponse {
            citedBy = citedBy != null ? List.copyOf(citedBy) : Collections.emptyList();
            reliesOn = reliesOn != null ? List.copyOf(reliesOn) : Collections.emptyList();
            similarCases = similarCases != null ? List.copyOf(similarCases) : Collections.emptyList();
        }
    }

    public record RelatedCitationPageResponse(
            String authorityId,
            String direction,
            String treatment,
            int totalCount,
            int offset,
            int limit,
            boolean hasMore,
            List<RelatedJudgmentItem> items
    ) {
        public RelatedCitationPageResponse {
            items = items != null ? List.copyOf(items) : Collections.emptyList();
        }
    }
}

