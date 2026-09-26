package com.app.refirm.triage.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "triage_sessions")
@Getter
@Setter
public class TriageSession implements Persistable<UUID> {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private String clerkUserId;

    @Column(name = "status")
    private String status = "IN_PROGRESS";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "is_complete")
    private boolean isComplete = false;

    @Column(name = "is_emergency")
    private boolean isEmergency = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_category")
    private LegalCategory issueCategory;

    @Column(name = "gathered_facts", columnDefinition = "TEXT")
    private String gatheredFacts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chat_history", columnDefinition = "jsonb")
    private List<ConversationTurn> chatHistory = new ArrayList<>();

    // ─── PERSISTABLE CONFIGURATION FOR PRE-ASSIGNED UUIDS ───

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public UUID getId() {
        return sessionId;
    }

    @Override
    @Transient
    public boolean isNew() {
        return isNew;
    }

    // Automatically mark the entity as NOT new once loaded from DB or successfully persisted
    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}