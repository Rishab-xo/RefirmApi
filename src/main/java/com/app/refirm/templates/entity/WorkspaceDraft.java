package com.app.refirm.templates.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.*;

/**
 * Persisted versioned draft of a hybrid legal pleading or agreement within a workspace.
 * Preserves filled particulars and isolated AI blocks across incremental version updates.
 */
@Entity
@Table(name = "workspace_drafts", indexes = {
        @Index(name = "idx_draft_workspace_user", columnList = "workspace_id, clerk_user_id"),
        @Index(name = "idx_draft_user_updated", columnList = "clerk_user_id, updated_at")
})
@Getter
@Setter
public class WorkspaceDraft implements Persistable<UUID> {

    @Id
    @Column(name = "draft_id")
    private UUID draftId = UUID.randomUUID();

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "clerk_user_id", nullable = false)
    private String clerkUserId;

    @Column(name = "template_id", nullable = false)
    private String templateId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filled_particulars", columnDefinition = "jsonb")
    private Map<String, String> filledParticulars = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_ai_blocks", columnDefinition = "jsonb")
    private Map<String, String> generatedAiBlocks = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_particular_keys", columnDefinition = "jsonb")
    private List<String> missingParticularKeys = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "locked_ai_block_keys", columnDefinition = "jsonb")
    private Set<String> lockedAiBlockKeys = new LinkedHashSet<>();

    public boolean isAiBlockLocked(String blockKey) {
        return lockedAiBlockKeys != null && lockedAiBlockKeys.contains(blockKey);
    }

    public Set<String> getLockedAiBlockKeys() {
        if (lockedAiBlockKeys == null) {
            lockedAiBlockKeys = new LinkedHashSet<>();
        }
        return lockedAiBlockKeys;
    }

    public void setLockedAiBlockKeys(Set<String> lockedAiBlockKeys) {
        this.lockedAiBlockKeys = lockedAiBlockKeys != null ? lockedAiBlockKeys : new LinkedHashSet<>();
    }

    @Column(name = "rendered_markdown", columnDefinition = "TEXT", nullable = false)
    private String renderedMarkdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ─── PERSISTABLE CONFIGURATION FOR PRE-ASSIGNED UUIDS ───

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public UUID getId() {
        return draftId;
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
