package com.app.refirm.workspace.repository;

import com.app.refirm.workspace.entity.WorkspaceSavedAuthority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceSavedAuthorityRepository extends JpaRepository<WorkspaceSavedAuthority, UUID> {

    List<WorkspaceSavedAuthority> findByWorkspaceIdAndClerkUserIdOrderByCreatedAtDesc(UUID workspaceId, String clerkUserId);

    Optional<WorkspaceSavedAuthority> findByWorkspaceIdAndAuthorityIdAndClerkUserId(UUID workspaceId, String authorityId, String clerkUserId);

    boolean existsByWorkspaceIdAndAuthorityIdAndClerkUserId(UUID workspaceId, String authorityId, String clerkUserId);

    long countByWorkspaceIdAndClerkUserId(UUID workspaceId, String clerkUserId);

    @Modifying
    @Query("DELETE FROM WorkspaceSavedAuthority a WHERE a.workspaceId = :workspaceId AND a.clerkUserId = :clerkUserId")
    void deleteByWorkspaceIdAndClerkUserId(@Param("workspaceId") UUID workspaceId, @Param("clerkUserId") String clerkUserId);
}
