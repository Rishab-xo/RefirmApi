package com.app.refirm.workspace.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured legal IRAC (Issue, Rule, Application/Analysis, Conclusion) brief,
 * populated specifically for case judgments, judicial precedents, and impugned orders.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IracBrief(
        String facts,
        List<String> issuesFramed,
        List<String> provisionsCited,
        String ratioDecidendi,
        String finalOrderHeld
) {
    public IracBrief {
        facts = facts != null ? facts : "";
        issuesFramed = issuesFramed != null ? List.copyOf(issuesFramed) : List.of();
        provisionsCited = provisionsCited != null ? List.copyOf(provisionsCited) : List.of();
        ratioDecidendi = ratioDecidendi != null ? ratioDecidendi : "";
        finalOrderHeld = finalOrderHeld != null ? finalOrderHeld : "";
    }
}
