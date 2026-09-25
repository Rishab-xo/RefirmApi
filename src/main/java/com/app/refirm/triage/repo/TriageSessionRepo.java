package com.app.refirm.triage.repo;

import com.app.refirm.triage.entities.TriageSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TriageSessionRepo extends JpaRepository<TriageSession, UUID> {
    List<TriageSession> findByClerkUserId(String clerkUserId);
}