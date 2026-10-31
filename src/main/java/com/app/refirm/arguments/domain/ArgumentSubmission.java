package com.app.refirm.arguments.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Core legal submission with statutory pins, precedent authorities, and verified evidentiary anchors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArgumentSubmission(
        String submissionId,
        String title,
        String legalProposition,
        List<String> pinnedStatutes,
        List<String> pinnedPrecedents,
        String verifiedEvidenceAnchor,
        String evidentiaryGapWarning
) {
    public ArgumentSubmission {
        submissionId = submissionId != null ? submissionId : "SUB_1";
        title = title != null ? title : "";
        legalProposition = legalProposition != null ? legalProposition : "";
        pinnedStatutes = pinnedStatutes != null ? List.copyOf(pinnedStatutes) : List.of();
        pinnedPrecedents = pinnedPrecedents != null ? List.copyOf(pinnedPrecedents) : List.of();
        verifiedEvidenceAnchor = verifiedEvidenceAnchor != null ? verifiedEvidenceAnchor : "NONE_UPLOADED";
    }

    public ArgumentSubmission withPinnedPrecedents(List<String> newPrecedents) {
        return new ArgumentSubmission(
                submissionId,
                title,
                legalProposition,
                pinnedStatutes,
                newPrecedents,
                verifiedEvidenceAnchor,
                evidentiaryGapWarning
        );
    }

    public ArgumentSubmission withEvidenceAnchorAndWarning(String newAnchor, String newWarning) {
        return new ArgumentSubmission(
                submissionId,
                title,
                legalProposition,
                pinnedStatutes,
                pinnedPrecedents,
                newAnchor,
                newWarning
        );
    }
}
