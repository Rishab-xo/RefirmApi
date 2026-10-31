package com.app.refirm.arguments.entity;

import com.app.refirm.arguments.domain.ArgumentDossier;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted courtroom argument preparation sheet for a workspace.
 * Encapsulates the versioned ArgumentDossier, perspective, locked submission IDs,
 * source ledger version, and rendered courtroom Markdown dossier.
 */
@Entity
@Table(name = "workspace_argument_sheets", indexes = {
        @Index(name = "idx_arg_workspace_user", columnList = "workspace_id, clerk_user_id"),
        @Index(name = "idx_arg_user_updated", columnList = "clerk_user_id, updated_at")
})
@Getter
@Setter
public class WorkspaceArgumentSheet implements Persistable<UUID> {

    @Id
    @Column(name = "sheet_id")
    private UUID sheetId = UUID.randomUUID();

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "clerk_user_id", nullable = false)
    private String clerkUserId;

    @Column(name = "title", nullable = false)
    private String title = "Courtroom Argument Sheet";

    @Enumerated(EnumType.STRING)
    @Column(name = "party_perspective", nullable = false)
    private PartyPerspective partyPerspective = PartyPerspective.CLAIMANT_PETITIONER;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "source_fact_ledger_version", nullable = false)
    private int sourceFactLedgerVersion = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "argument_dossier", columnDefinition = "jsonb")
    private ArgumentDossier argumentDossier = ArgumentDossier.empty();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "locked_submission_ids", columnDefinition = "jsonb")
    private Set<String> lockedSubmissionIds = new LinkedHashSet<>();

    @Column(name = "rendered_markdown", columnDefinition = "TEXT", nullable = false)
    private String renderedMarkdown = "";

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
        return sheetId;
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

    public boolean isSubmissionLocked(String submissionId) {
        return lockedSubmissionIds != null && lockedSubmissionIds.contains(submissionId);
    }

    public Set<String> getLockedSubmissionIds() {
        if (lockedSubmissionIds == null) {
            lockedSubmissionIds = new LinkedHashSet<>();
        }
        return lockedSubmissionIds;
    }

    public void setLockedSubmissionIds(Set<String> lockedSubmissionIds) {
        this.lockedSubmissionIds = lockedSubmissionIds != null ? lockedSubmissionIds : new LinkedHashSet<>();
    }

    /**
     * Checks if this argument sheet has become stale compared to the current workspace state.
     * An argument sheet is stale if the party perspective has changed, or if the workspace fact ledger
     * has progressed to a newer version than this sheet was generated against.
     */
    public boolean isStaleComparedTo(LegalWorkspace workspace) {
        if (workspace == null) {
            return false;
        }
        if (workspace.getPartyPerspective() != this.partyPerspective) {
            return true;
        }
        return workspace.getFactLedger() != null
                && workspace.getFactLedger().version() > this.sourceFactLedgerVersion;
    }
}
