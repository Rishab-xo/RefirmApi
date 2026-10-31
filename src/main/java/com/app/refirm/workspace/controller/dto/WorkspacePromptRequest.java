package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.PromptExecutionMode;

/**
 * Request payload for submitting a user prompt from Home Screen or Active Chat.
 */
public record WorkspacePromptRequest(
        String promptText,
        PromptExecutionMode executionMode,
        String responseLanguage,
        String locationHint,
        PartyPerspective partyPerspective,
        Boolean temporary
) {
    public WorkspacePromptRequest {
        if (executionMode == null) {
            executionMode = PromptExecutionMode.AUTO;
        }
        if (responseLanguage == null || responseLanguage.isBlank()) {
            responseLanguage = "English";
        }
    }

    public WorkspacePromptRequest(
            String promptText,
            PromptExecutionMode executionMode,
            String responseLanguage,
            String locationHint,
            PartyPerspective partyPerspective
    ) {
        this(promptText, executionMode, responseLanguage, locationHint, partyPerspective, false);
    }
}

