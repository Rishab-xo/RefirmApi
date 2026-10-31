package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.domain.PartyPerspective;

public record CreateCaseProjectRequest(
        String caseTitle,
        String clientName,
        PartyPerspective perspective
) {}
