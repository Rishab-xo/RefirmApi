package com.app.refirm.arguments.repository;

import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceArgumentSheetRepository extends JpaRepository<WorkspaceArgumentSheet, UUID> {

    List<WorkspaceArgumentSheet> findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(UUID workspaceId, String clerkUserId);

    List<WorkspaceArgumentSheet> findByWorkspaceIdOrderByVersionDesc(UUID workspaceId);

    List<WorkspaceArgumentSheet> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceId(UUID workspaceId);

    Optional<WorkspaceArgumentSheet> findBySheetIdAndWorkspaceIdAndClerkUserId(UUID sheetId, UUID workspaceId, String clerkUserId);

    @Modifying
    @Query("DELETE FROM WorkspaceArgumentSheet s WHERE s.workspaceId = :workspaceId AND s.clerkUserId = :clerkUserId")
    void deleteByWorkspaceIdAndClerkUserId(@Param("workspaceId") UUID workspaceId, @Param("clerkUserId") String clerkUserId);
}

