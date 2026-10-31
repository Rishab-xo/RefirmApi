package com.app.refirm.workspace.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted record of a legal authority saved / pinned to a specific case matter.
 */
@Entity
@Table(name = "workspace_saved_authorities", indexes = {
        @Index(name = "idx_saved_auth_ws_user", columnList = "workspace_id, clerk_user_id"),
        @Index(name = "idx_saved_auth_unique", columnList = "workspace_id, authority_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceSavedAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "clerk_user_id", nullable = false)
    private String clerkUserId;

    @Column(name = "authority_id", nullable = false)
    private String authorityId;

    @Column(name = "case_title")
    private String caseTitle;

    @Column(name = "primary_citation")
    private String primaryCitation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public WorkspaceSavedAuthority(UUID workspaceId, String clerkUserId, String authorityId, String caseTitle, String primaryCitation) {
        this.workspaceId = workspaceId;
        this.clerkUserId = clerkUserId;
        this.authorityId = authorityId;
        this.caseTitle = caseTitle;
        this.primaryCitation = primaryCitation;
        this.createdAt = Instant.now();
    }
}
