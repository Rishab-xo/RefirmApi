package com.app.refirm.workspace.controller.dto;

import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IngestionStatus;
import com.app.refirm.workspace.domain.StructuredDocumentSummary;
import com.app.refirm.workspace.entity.WorkspaceDocument;

import java.util.UUID;

/**
 * Full WorkspaceDocument DTO returned on document upload and vault inspection.
 */
public record WorkspaceDocumentDTO(
        UUID documentId,
        UUID workspaceId,
        String fileName,
        DocumentType documentType,
        int totalPages,
        IngestionStatus ingestionStatus,
        boolean includedInCaseContext,
        DocumentOkfManifest okfManifest,
        StructuredDocumentSummary structuredSummary,
        int updatedFactLedgerVersion
) {
    public static WorkspaceDocumentDTO from(WorkspaceDocument doc, int factLedgerVersion) {
        return new WorkspaceDocumentDTO(
                doc.getDocumentId(),
                doc.getWorkspaceId(),
                doc.getFileName(),
                doc.getDocumentType(),
                doc.getTotalPages(),
                doc.getIngestionStatus(),
                doc.isIncludedInCaseContext(),
                doc.getOkfManifest(),
                doc.getStructuredSummary(),
                factLedgerVersion
        );
    }
}
