package com.app.refirm.workspace.repository;

import com.app.refirm.workspace.domain.WorkspaceMode;
import com.app.refirm.workspace.entity.LegalWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalWorkspaceRepository extends JpaRepository<LegalWorkspace, UUID> {

    Optional<LegalWorkspace> findByWorkspaceIdAndClerkUserId(UUID workspaceId, String clerkUserId);

    List<LegalWorkspace> findByClerkUserIdAndModeOrderByUpdatedAtDesc(String clerkUserId, WorkspaceMode mode);

    List<LegalWorkspace> findByClerkUserIdOrderByUpdatedAtDesc(String clerkUserId);

    List<LegalWorkspace> findByClerkUserIdAndTemporaryFalseOrderByUpdatedAtDesc(String clerkUserId);

    List<LegalWorkspace> findByClerkUserIdAndModeAndTemporaryFalseOrderByUpdatedAtDesc(String clerkUserId, WorkspaceMode mode);
}

