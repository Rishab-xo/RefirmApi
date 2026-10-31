package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.StructuredDocumentSummary;

import java.util.UUID;

public record DocumentDetailSummaryResponse(
        UUID documentId,
        String fileName,
        DocumentOkfManifest okfManifest,
        StructuredDocumentSummary structuredSummary
) {
}
