package com.app.refirm.inspector.domain;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import java.util.List;

/**
 * Immutable domain record representing raw judgment chunks fetched from remote
 * Tier 2 storage (e.g. Vaquill / Open India Law) and stitched into full text with
 * split drawer pages and final operative holding.
 */
public record HydratedJudgmentDocument(
        String docId,
        String caseTitle,
        String primaryCitation,
        String courtCode,
        int decisionYear,
        List<DrawerPage> pages,
        String finalHoldingSentence,
        String rawFullText
) {
    public HydratedJudgmentDocument {
        docId = docId != null ? docId : "";
        caseTitle = caseTitle != null ? caseTitle : "";
        primaryCitation = primaryCitation != null ? primaryCitation : "";
        courtCode = (courtCode != null && !courtCode.isBlank()) ? courtCode : "SC";
        decisionYear = decisionYear > 0 ? decisionYear : 0;
        pages = pages != null ? List.copyOf(pages) : List.of();
        finalHoldingSentence = finalHoldingSentence != null ? finalHoldingSentence : "";
        rawFullText = rawFullText != null ? rawFullText : "";
    }
}

