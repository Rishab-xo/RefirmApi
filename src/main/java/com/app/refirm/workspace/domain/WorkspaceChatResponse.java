package com.app.refirm.workspace.domain;

import java.util.List;

/**
 * Answer payload returned by the Scoped Workspace Q&A Assistant.
 * Includes grounded markdown answer, document/page citations, and current fact ledger version.
 */
public record WorkspaceChatResponse(
        String answerMarkdown,
        List<PinnedPageCitation> citations,
        int workspaceFactVersion
) {
    public WorkspaceChatResponse {
        answerMarkdown = answerMarkdown != null ? answerMarkdown : "";
        citations = citations != null ? List.copyOf(citations) : List.of();
    }
}
