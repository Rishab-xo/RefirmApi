package com.app.refirm.templates.controller.dto;

import com.app.refirm.templates.entity.WorkspaceDraft;

import java.time.Instant;
import java.util.*;

/**
 * Court-ready versioned WorkspaceDraft DTO (Wireframe 2A).
 * Features wordCount, lastModifiedKeys, and versionChangeSummary.
 */
public record WorkspaceDraftDTO(
        UUID draftId,
        UUID workspaceId,
        String templateId,
        String title,
        int version,
        String versionChangeSummary,
        List<String> lastModifiedKeys,
        Map<String, String> filledParticulars,
        Map<String, String> generatedAiBlocks,
        Set<String> lockedAiBlockKeys,
        List<String> missingParticularKeys,
        String renderedMarkdown,
        int wordCount,
        Instant updatedAt
) {
    public static WorkspaceDraftDTO from(WorkspaceDraft draft, String changeSummary, List<String> modifiedKeys) {
        int words = draft.getRenderedMarkdown() != null && !draft.getRenderedMarkdown().isBlank()
                ? draft.getRenderedMarkdown().trim().split("\\s+").length
                : 0;

        return new WorkspaceDraftDTO(
                draft.getDraftId(),
                draft.getWorkspaceId(),
                draft.getTemplateId(),
                draft.getTitle(),
                draft.getVersion(),
                changeSummary != null ? changeSummary : ("v" + draft.getVersion() + " initialized"),
                modifiedKeys != null ? modifiedKeys : Collections.emptyList(),
                draft.getFilledParticulars() != null ? draft.getFilledParticulars() : Collections.emptyMap(),
                draft.getGeneratedAiBlocks() != null ? draft.getGeneratedAiBlocks() : Collections.emptyMap(),
                draft.getLockedAiBlockKeys() != null ? draft.getLockedAiBlockKeys() : Collections.emptySet(),
                draft.getMissingParticularKeys() != null ? draft.getMissingParticularKeys() : Collections.emptyList(),
                draft.getRenderedMarkdown() != null ? draft.getRenderedMarkdown() : "",
                words,
                draft.getUpdatedAt()
        );
    }
}
