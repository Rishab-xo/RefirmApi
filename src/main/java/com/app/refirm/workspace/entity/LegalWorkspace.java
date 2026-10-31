package com.app.refirm.workspace.entity;

import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.entities.ConversationTurn;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.WorkspaceMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root entity representing a Refirm Workspace (either a fast QUICK_CONSULT or a full CASE_PROJECT).
 * Maintains the single source of truth across triage intake, fact reconciliations, and document vaults.
 */
@Entity
@Table(name = "legal_workspaces", indexes = {
        @Index(name = "idx_workspace_user_mode", columnList = "clerk_user_id, mode"),
        @Index(name = "idx_workspace_user_updated", columnList = "clerk_user_id, updated_at")
})
@Getter
@Setter
public class LegalWorkspace implements Persistable<UUID> {

    @Id
    @Column(name = "workspace_id")
    private UUID workspaceId = UUID.randomUUID();

    @Column(name = "clerk_user_id", nullable = false)
    private String clerkUserId;

    @Column(name = "source_triage_session_id")
    private UUID sourceTriageSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private WorkspaceMode mode = WorkspaceMode.QUICK_CONSULT;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_perspective", nullable = false)
    private PartyPerspective partyPerspective = PartyPerspective.CLAIMANT_PETITIONER;

    @Column(name = "title", nullable = false)
    private String title = "Quick Consult";

    @Column(name = "client_name")
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_domain")
    private LegalDomain primaryDomain;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fact_ledger", columnDefinition = "jsonb")
    private CaseFactLedger factLedger = CaseFactLedger.empty();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "latest_drafting_context", columnDefinition = "jsonb")
    private DraftingContext latestDraftingContext;

    @Column(name = "latest_brief_markdown", columnDefinition = "TEXT")
    private String latestBriefMarkdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chat_history", columnDefinition = "jsonb")
    private List<ConversationTurn> chatHistory = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @Column(name = "is_temporary", nullable = false, columnDefinition = "boolean default false")
    private boolean temporary = false;

    // ─── PERSISTABLE CONFIGURATION FOR PRE-ASSIGNED UUIDS ───

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public UUID getId() {
        return workspaceId;
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
