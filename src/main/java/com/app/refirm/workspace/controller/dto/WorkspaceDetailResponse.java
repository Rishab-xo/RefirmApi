package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.entity.LegalWorkspace;

import java.util.List;

public record WorkspaceDetailResponse(
        LegalWorkspace workspace,
        List<WorkspaceDocumentSummary> documents
) {}
