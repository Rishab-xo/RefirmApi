package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IngestionStatus;
import com.app.refirm.workspace.entity.WorkspaceDocument;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceDocumentSummary(
        UUID documentId,
        String fileName,
        DocumentType documentType,
        int totalPages,
        IngestionStatus ingestionStatus,
        boolean includedInCaseContext,
        DocumentOkfManifest okfManifest,
        Instant createdAt
) {
    public static WorkspaceDocumentSummary fromEntity(WorkspaceDocument doc) {
        return new WorkspaceDocumentSummary(
                doc.getDocumentId(),
                doc.getFileName(),
                doc.getDocumentType(),
                doc.getTotalPages(),
                doc.getIngestionStatus(),
                doc.isIncludedInCaseContext(),
                doc.getOkfManifest(),
                doc.getCreatedAt()
        );
    }
}
