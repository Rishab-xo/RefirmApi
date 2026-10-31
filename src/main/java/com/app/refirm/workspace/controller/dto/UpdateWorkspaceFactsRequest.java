package com.app.refirm.workspace.controller.dto;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.workspace.domain.PartyPerspective;

import java.util.Collections;
import java.util.Set;

/**
 * Request to lock or edit case facts in collapsible bar (Step 1.3 - Wireframe 1B).
 */
public record UpdateWorkspaceFactsRequest(
        ExtractedFacts updatedFacts,
        ExtractedFactsDTO updatedRuleFacts,
        Set<String> fieldsToLock,
        PartyPerspective partyPerspective
) {
    public UpdateWorkspaceFactsRequest {
        fieldsToLock = fieldsToLock != null ? Collections.unmodifiableSet(fieldsToLock) : Collections.emptySet();
    }
}
