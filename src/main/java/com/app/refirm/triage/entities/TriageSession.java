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

    private String clerkUserId;
    private String status;
    private Instant createdAt;
    private boolean isComplete;

    @Enumerated(EnumType.STRING)
    private LegalCategory issueCategory;

    @Column(columnDefinition = "TEXT")
    // If using JSON mapping, keep your existing annotations here
    private String gatheredFacts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
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