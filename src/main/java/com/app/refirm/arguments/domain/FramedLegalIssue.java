package com.app.refirm.arguments.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Procedural or substantive legal issue framed for judicial determination.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FramedLegalIssue(
        String issueId,
        String questionFramed,
        String burdenOfProof
) {
    public FramedLegalIssue {
        issueId = issueId != null ? issueId : "ISSUE_1";
        questionFramed = questionFramed != null ? questionFramed : "";
        burdenOfProof = burdenOfProof != null ? burdenOfProof : "";
    }
}
