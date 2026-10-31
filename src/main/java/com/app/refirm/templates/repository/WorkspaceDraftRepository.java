package com.app.refirm.templates.repository;

import com.app.refirm.templates.entity.WorkspaceDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceDraftRepository extends JpaRepository<WorkspaceDraft, UUID> {

    List<WorkspaceDraft> findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(UUID workspaceId, String clerkUserId);

    Optional<WorkspaceDraft> findByDraftIdAndWorkspaceIdAndClerkUserId(UUID draftId, UUID workspaceId, String clerkUserId);

    @Modifying
    @Query("DELETE FROM WorkspaceDraft d WHERE d.workspaceId = :workspaceId AND d.clerkUserId = :clerkUserId")
    void deleteByWorkspaceIdAndClerkUserId(@Param("workspaceId") UUID workspaceId, @Param("clerkUserId") String clerkUserId);
}
