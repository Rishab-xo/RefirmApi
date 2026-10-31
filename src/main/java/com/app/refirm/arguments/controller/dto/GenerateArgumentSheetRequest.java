package com.app.refirm.arguments.controller.dto;

import com.app.refirm.workspace.domain.PartyPerspective;

public record GenerateArgumentSheetRequest(
        PartyPerspective partyPerspective,
        String customFocusInstruction,
        String title
) {}
