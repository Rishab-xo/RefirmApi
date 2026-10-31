package com.app.refirm.workspace.repository;

import com.app.refirm.workspace.entity.WorkspaceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceDocumentRepository extends JpaRepository<WorkspaceDocument, UUID> {

    List<WorkspaceDocument> findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(
            UUID workspaceId,
            String clerkUserId
    );

    Optional<WorkspaceDocument> findByDocumentIdAndWorkspaceIdAndClerkUserId(
            UUID documentId,
            UUID workspaceId,
            String clerkUserId
    );

    List<WorkspaceDocument> findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(
            UUID workspaceId,
            String clerkUserId
    );

    long countByWorkspaceId(UUID workspaceId);

    @Modifying
    @Query("DELETE FROM WorkspaceDocument d WHERE d.workspaceId = :workspaceId AND d.clerkUserId = :clerkUserId")
    void deleteByWorkspaceIdAndClerkUserId(@Param("workspaceId") UUID workspaceId, @Param("clerkUserId") String clerkUserId);
}
