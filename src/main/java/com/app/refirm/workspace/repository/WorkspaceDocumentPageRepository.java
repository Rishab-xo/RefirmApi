package com.app.refirm.workspace.repository;

import com.app.refirm.workspace.entity.WorkspaceDocumentPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkspaceDocumentPageRepository extends JpaRepository<WorkspaceDocumentPage, UUID> {

    List<WorkspaceDocumentPage> findByDocumentIdAndPageNumberInOrderByPageNumberAsc(
            UUID documentId,
            Collection<Integer> pageNumbers
    );

    List<WorkspaceDocumentPage> findByWorkspaceIdAndDocumentIdInOrderByPageNumberAsc(
            UUID workspaceId,
            Collection<UUID> documentIds
    );

    List<WorkspaceDocumentPage> findByDocumentIdOrderByPageNumberAsc(UUID documentId);

    @Modifying
    @Query("DELETE FROM WorkspaceDocumentPage p WHERE p.workspaceId = :workspaceId")
    void deleteByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
