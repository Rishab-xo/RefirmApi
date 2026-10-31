package com.app.refirm.workspace;

import com.app.refirm.triage.pipeline.TriagePipelineOrchestrator;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.WorkspaceMode;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.service.FactReconcilerService;
import com.app.refirm.workspace.service.WorkspaceOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkspacePromotionTest {

    @Mock
    private LegalWorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceDocumentRepository documentRepository;

    @Mock
    private com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository pageRepository;

    @Mock
    private FactReconcilerService factReconcilerService;

    @Mock
    private TriagePipelineOrchestrator triagePipelineOrchestrator;

    @Mock
    private com.app.refirm.workspace.repository.WorkspaceSavedAuthorityRepository savedAuthorityRepository;

    @Mock
    private com.app.refirm.templates.repository.WorkspaceDraftRepository draftRepository;

    @Mock
    private com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository argumentSheetRepository;

    private WorkspaceOrchestratorService orchestratorService;

    private final UUID workspaceId = UUID.randomUUID();
    private final String clerkUserId = "user_clerk_lawyer_99";

    @BeforeEach
    void setUp() {
        orchestratorService = new WorkspaceOrchestratorService(
                workspaceRepository,
                documentRepository,
                pageRepository,
                factReconcilerService,
                triagePipelineOrchestrator,
                null,
                null,
                null,
                savedAuthorityRepository,
                draftRepository,
                argumentSheetRepository
        );
        lenient().when(workspaceRepository.save(any(LegalWorkspace.class))).thenAnswer(returnsFirstArg());
    }

    @Test
    @DisplayName("Promotes QUICK_CONSULT to CASE_PROJECT and marks excluded documents correctly")
    void shouldPromoteWorkspaceAndExcludeUncheckedDocuments() {
        // Given existing QUICK_CONSULT workspace
        LegalWorkspace quickConsult = new LegalWorkspace();
        quickConsult.setWorkspaceId(workspaceId);
        quickConsult.setClerkUserId(clerkUserId);
        quickConsult.setMode(WorkspaceMode.QUICK_CONSULT);
        quickConsult.setTitle("Quick Consult");
        quickConsult.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(quickConsult));

        // Two documents attached to the workspace
        UUID docId1 = UUID.randomUUID();
        UUID docId2 = UUID.randomUUID();

        WorkspaceDocument doc1 = new WorkspaceDocument();
        doc1.setDocumentId(docId1);
        doc1.setIncludedInCaseContext(true);

        WorkspaceDocument doc2 = new WorkspaceDocument();
        doc2.setDocumentId(docId2);
        doc2.setIncludedInCaseContext(true);

        when(documentRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(workspaceId, clerkUserId))
                .thenReturn(List.of(doc1, doc2));

        // When promoting with doc2 excluded
        LegalWorkspace promoted = orchestratorService.promoteToCaseProject(
                workspaceId,
                clerkUserId,
                "Apex Promoters Delay Matter",
                "Amitabh Singhania",
                PartyPerspective.CLAIMANT_PETITIONER,
                List.of(docId2)
        );

        // Then verify atomic state transition
        assertThat(promoted.getMode()).isEqualTo(WorkspaceMode.CASE_PROJECT);
        assertThat(promoted.getTitle()).isEqualTo("Apex Promoters Delay Matter");
        assertThat(promoted.getClientName()).isEqualTo("Amitabh Singhania");
        assertThat(promoted.getPromotedAt()).isNotNull();

        // Verify doc2 was unchecked from case context
        verify(documentRepository).save(argThat(d -> d.getDocumentId().equals(docId2) && !d.isIncludedInCaseContext()));
        assertThat(doc1.isIncludedInCaseContext()).isTrue();
        assertThat(doc2.isIncludedInCaseContext()).isFalse();
    }

    @Test
    @DisplayName("Cleanly deletes workspace and cascades across all 5 child repositories")
    void shouldCleanlyDeleteWorkspaceAndAllAssociatedRecordsFromDatabase() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setTitle("Quick Consult to Delete");

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        // When deleting workspace
        orchestratorService.deleteWorkspace(workspaceId, clerkUserId);

        // Then verify all child records and workspace are purged
        verify(pageRepository).deleteByWorkspaceId(workspaceId);
        verify(documentRepository).deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        verify(savedAuthorityRepository).deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        verify(draftRepository).deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        verify(argumentSheetRepository).deleteByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        verify(workspaceRepository).delete(workspace);
    }

    @Test
    @DisplayName("Throws ResponseStatusException when deleting non-existent or unauthorized workspace")
    void shouldThrowNotFoundWhenDeletingNonExistentOrUnauthorizedWorkspace() {
        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> orchestratorService.deleteWorkspace(workspaceId, clerkUserId)
        );

        verify(workspaceRepository, never()).delete(any());
    }
}
