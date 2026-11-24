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
}

