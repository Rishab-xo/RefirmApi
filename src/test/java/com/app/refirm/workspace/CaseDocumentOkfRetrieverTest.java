package com.app.refirm.workspace;

import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IngestionStatus;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceDocumentPage;
import com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.service.CaseDocumentOkfRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseDocumentOkfRetrieverTest {

    @Mock
    private WorkspaceDocumentRepository documentRepository;

    @Mock
    private WorkspaceDocumentPageRepository pageRepository;

    private CaseDocumentOkfRetriever retriever;

    private final UUID workspaceId = UUID.randomUUID();
    private final String clerkUserId = "user_clerk_test_123";

    @BeforeEach
    void setUp() {
        retriever = new CaseDocumentOkfRetriever(documentRepository, pageRepository);
    }

    @Test
    @DisplayName("Retrieves only pinned pages from DocumentOkfManifest matching the query")
    void shouldRetrieveOnlyPinnedPages() {
        UUID docId1 = UUID.randomUUID();
        WorkspaceDocument doc1 = new WorkspaceDocument();
        doc1.setDocumentId(docId1);
        doc1.setWorkspaceId(workspaceId);
        doc1.setClerkUserId(clerkUserId);
        doc1.setFileName("AutoCare_Receipt_and_Denial.pdf");
        doc1.setDocumentType(DocumentType.INVOICE_RECEIPT);
        doc1.setIngestionStatus(IngestionStatus.READY);
        doc1.setIncludedInCaseContext(true);
        doc1.setOkfManifest(new DocumentOkfManifest(
                "Receipt of transaction and formal email denial from platform",
                List.of("AutoCare Express"),
                List.of("2024-03-15"),
                List.of("₹85,000"),
                Map.of(
                        "pos_receipt", List.of(2),
                        "denial_email", List.of(4)
                )
        ));

        when(documentRepository.findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId))
                .thenReturn(List.of(doc1));

        WorkspaceDocumentPage page2 = new WorkspaceDocumentPage();
        page2.setPageId(UUID.randomUUID());
        page2.setDocumentId(docId1);
        page2.setWorkspaceId(workspaceId);
        page2.setPageNumber(2);
        page2.setPageMarkdown("### POS Slip: Payment of Rs. 85,000 captured via terminal");

        when(pageRepository.findByDocumentIdAndPageNumberInOrderByPageNumberAsc(eq(docId1), anyCollection()))
                .thenReturn(List.of(page2));

        List<WorkspaceDocumentPage> results = retriever.retrievePinnedPages(workspaceId, clerkUserId, "Show me the pos receipt payment");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getPageNumber()).isEqualTo(2);
        assertThat(results.get(0).getPageMarkdown()).contains("POS Slip");
    }

    @Test
    @DisplayName("Strictly enforces MAX_PINNED_PAGES_PER_QUERY = 5 across multiple multi-page documents")
    void shouldStrictlyEnforceFivePageCeiling() {
        UUID docId1 = UUID.randomUUID();
        WorkspaceDocument doc1 = new WorkspaceDocument();
        doc1.setDocumentId(docId1);
        doc1.setWorkspaceId(workspaceId);
        doc1.setClerkUserId(clerkUserId);
        doc1.setFileName("Evidence_Bundle_1.pdf");
        doc1.setIngestionStatus(IngestionStatus.READY);
        doc1.setIncludedInCaseContext(true);
        doc1.setOkfManifest(new DocumentOkfManifest(
                "Bundle 1",
                List.of("AutoCare"),
                List.of(),
                List.of(),
                Map.of("defect_proof", List.of(1, 2, 3, 4))
        ));

        UUID docId2 = UUID.randomUUID();
        WorkspaceDocument doc2 = new WorkspaceDocument();
        doc2.setDocumentId(docId2);
        doc2.setWorkspaceId(workspaceId);
        doc2.setClerkUserId(clerkUserId);
        doc2.setFileName("Evidence_Bundle_2.pdf");
        doc2.setIngestionStatus(IngestionStatus.READY);
        doc2.setIncludedInCaseContext(true);
        doc2.setOkfManifest(new DocumentOkfManifest(
                "Bundle 2",
                List.of("Garage"),
                List.of(),
                List.of(),
                Map.of("defect_proof", List.of(7, 8, 9, 10))
        ));

        when(documentRepository.findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId))
                .thenReturn(List.of(doc1, doc2));

        // Mock 4 pages from doc1 and 4 pages from doc2
        List<WorkspaceDocumentPage> doc1Pages = new ArrayList<>();
        for (int p : List.of(1, 2, 3, 4)) {
            WorkspaceDocumentPage page = new WorkspaceDocumentPage();
            page.setDocumentId(docId1);
            page.setPageNumber(p);
            page.setPageMarkdown("Doc 1 Page " + p);
            doc1Pages.add(page);
        }

        List<WorkspaceDocumentPage> doc2Pages = new ArrayList<>();
        for (int p : List.of(7, 8, 9, 10)) {
            WorkspaceDocumentPage page = new WorkspaceDocumentPage();
            page.setDocumentId(docId2);
            page.setPageNumber(p);
            page.setPageMarkdown("Doc 2 Page " + p);
            doc2Pages.add(page);
        }

        when(pageRepository.findByDocumentIdAndPageNumberInOrderByPageNumberAsc(eq(docId1), anyCollection()))
                .thenReturn(doc1Pages);
        when(pageRepository.findByDocumentIdAndPageNumberInOrderByPageNumberAsc(eq(docId2), anyCollection()))
                .thenReturn(doc2Pages);

        List<WorkspaceDocumentPage> retrieved = retriever.retrievePinnedPages(workspaceId, clerkUserId, "defect proof investigation");

        // MUST be capped strictly at 5 pages
        assertThat(retrieved).hasSize(CaseDocumentOkfRetriever.MAX_PINNED_PAGES_PER_QUERY);
        assertThat(retrieved).hasSize(5);
    }
}
