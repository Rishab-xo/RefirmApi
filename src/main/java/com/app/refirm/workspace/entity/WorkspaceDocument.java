package com.app.refirm.workspace.entity;

import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IngestionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata record for a single case document uploaded into a workspace document vault.
 * Contains the lightweight DocumentOkfManifest for instant section-targeted page retrieval.
 */
@Entity
@Table(name = "workspace_documents", indexes = {
        @Index(name = "idx_doc_workspace_user_included", columnList = "workspace_id, clerk_user_id, included_in_case_context"),
        @Index(name = "idx_doc_workspace_id", columnList = "workspace_id")
})
@Getter
@Setter
public class WorkspaceDocument implements Persistable<UUID> {

    @Id
    @Column(name = "document_id")
    private UUID documentId = UUID.randomUUID();

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "clerk_user_id", nullable = false)
    private String clerkUserId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType = DocumentType.EVIDENCE_OTHER;

    @Column(name = "total_pages", nullable = false)
    private int totalPages = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_status", nullable = false)
    private IngestionStatus ingestionStatus = IngestionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "okf_manifest", columnDefinition = "jsonb")
    private DocumentOkfManifest okfManifest = DocumentOkfManifest.empty();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_summary", columnDefinition = "jsonb")
    private com.app.refirm.workspace.domain.StructuredDocumentSummary structuredSummary;

    @Column(name = "included_in_case_context", nullable = false)
    private boolean includedInCaseContext = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ─── PERSISTABLE CONFIGURATION FOR PRE-ASSIGNED UUIDS ───

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public UUID getId() {
        return documentId;
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
