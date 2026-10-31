package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.domain.PartyPerspective;

import java.util.List;
import java.util.UUID;

public record PromoteWorkspaceRequest(
        String caseTitle,
        String clientName,
        PartyPerspective perspective,
        List<UUID> excludedDocumentIds
) {}
