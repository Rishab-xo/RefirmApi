package com.app.refirm.workspace.controller.dto;

import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.WorkspaceMode;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Clean response body for workspace prompt submissions (Wireframe 1B).
 */
public record WorkspacePromptResponse(
        UUID workspaceId,
        WorkspaceMode mode,
        PartyPerspective partyPerspective,
        LegalDomain resolvedDomain,
        String answerMarkdown,
        CaseFactLedger factLedger,
        List<PromptCitationItem> citations,
        PromptCreatedArtifactDTO createdArtifact,
        List<NextStepSuggestionCard> whatIsNextCards
) {
    public record PromptCitationItem(
            UUID documentId,
            String fileName,
            int pageNumber,
            String matchedTopic
    ) {}

    public record PromptCreatedArtifactDTO(
            String artifactType, // 'DRAFT' | 'ARGUMENT_SHEET'
            UUID artifactId,
            String title,
            int version
    ) {
        public PromptCreatedArtifactDTO(String artifactType, String artifactIdStr, String title, int version) {
            this(artifactType, UUID.fromString(artifactIdStr), title, version);
        }
    }


    public WorkspacePromptResponse {
        citations = citations != null ? citations : Collections.emptyList();
        whatIsNextCards = whatIsNextCards != null ? whatIsNextCards : Collections.emptyList();
    }
}
