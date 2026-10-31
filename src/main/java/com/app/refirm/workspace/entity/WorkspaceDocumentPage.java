package com.app.refirm.workspace.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * Granular page-level record holding markdown text for section-targeted retrieval.
 * Enables fetching exact relevant pages without loading hundreds of whole PDF tokens into prompt context.
 */
@Entity
@Table(name = "workspace_document_pages", indexes = {
        @Index(name = "idx_page_doc_num", columnList = "document_id, page_number"),
        @Index(name = "idx_page_workspace_id", columnList = "workspace_id")
})
@Getter
@Setter
public class WorkspaceDocumentPage implements Persistable<UUID> {

    @Id
    @Column(name = "page_id")
    private UUID pageId = UUID.randomUUID();

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "page_markdown", columnDefinition = "TEXT", nullable = false)
    private String pageMarkdown;

    // ─── PERSISTABLE CONFIGURATION FOR PRE-ASSIGNED UUIDS ───

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public UUID getId() {
        return pageId;
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
