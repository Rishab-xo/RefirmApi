package com.app.refirm.workspace;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.*;
import com.app.refirm.workspace.domain.*;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceDocumentPage;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.service.CaseDocumentOkfRetriever;
import com.app.refirm.workspace.service.WorkspaceAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkspaceAssistantServiceTest {

    @Mock
    private LegalWorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceDocumentRepository documentRepository;

    @Mock
    private CaseDocumentOkfRetriever okfRetriever;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private WorkspaceAssistantService assistantService;

    private final UUID workspaceId = UUID.randomUUID();
    private final String clerkUserId = "advocate_test_user";

    @BeforeEach
    void setUp() {
        assistantService = new WorkspaceAssistantService(
                workspaceRepository,
                documentRepository,
                okfRetriever,
                chatClient
        );

        lenient().when(workspaceRepository.save(any(LegalWorkspace.class))).thenAnswer(returnsFirstArg());
    }

    @Test
    @DisplayName("Queries assistant, retrieves pinned pages via OKF retriever, appends turns, and returns citations")
    void askAssistantRetrievesPinnedPagesAndReturnsCitations() {
        // Workspace with initial ledger and empty chat history
        PartyEntity claimant = new PartyEntity("Rahul", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE);
        PartyEntity merchant = new PartyEntity("XYZ Retailer", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE);

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                claimant,
                null,
                List.of(merchant),
                List.of("Double debit occurred during POS transaction"),
                35000.0
        );
        ExtractedFactsDTO ruleFacts = new ExtractedFactsDTO(
                com.app.refirm.rules.model.LegalDomain.CONSUMER,
                35000L,
                "Bengaluru",
                "XYZ Retailer",
                null
        );
        CaseFactLedger ledger = new CaseFactLedger(1, facts, ruleFacts, Set.of(), Instant.now());

        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setClerkUserId(clerkUserId);
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setFactLedger(ledger);
        workspace.setChatHistory(new ArrayList<>());

        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.of(workspace));

        // Active document in workspace catalog
        UUID docId = UUID.randomUUID();
        WorkspaceDocument doc = new WorkspaceDocument();
        doc.setDocumentId(docId);
        doc.setWorkspaceId(workspaceId);
        doc.setClerkUserId(clerkUserId);
        doc.setFileName("bank_chargeback_denial.pdf");
        doc.setDocumentType(DocumentType.LEGAL_NOTICE);
        doc.setTotalPages(2);
        doc.setIngestionStatus(IngestionStatus.READY);
        doc.setIncludedInCaseContext(true);
        doc.setOkfManifest(new DocumentOkfManifest(
                "Chargeback denial notice from card issuer",
                List.of("Axis Bank"),
                List.of("2023-05-20"),
                List.of("INR 35,000"),
                Map.of("chargeback_denial", List.of(1))
        ));

        when(documentRepository.findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId))
                .thenReturn(List.of(doc));

        // Pinned page returned by OKF retriever
        WorkspaceDocumentPage pinnedPage = new WorkspaceDocumentPage();
        pinnedPage.setDocumentId(docId);
        pinnedPage.setWorkspaceId(workspaceId);
        pinnedPage.setPageNumber(1);
        pinnedPage.setPageMarkdown("# bank_chargeback_denial.pdf — Page 1\n\nWe regret to inform you that your dispute of INR 35,000 was declined.");

        when(okfRetriever.retrievePinnedPages(workspaceId, clerkUserId, "Why did the bank deny the chargeback?"))
                .thenReturn(List.of(pinnedPage));

        // Mock ChatClient fluent response
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "According to [bank_chargeback_denial.pdf, Page 1], Axis Bank declined the INR 35,000 chargeback dispute."
        );

        // Execute query
        WorkspaceChatResponse response = assistantService.askWorkspaceAssistant(
                workspaceId,
                clerkUserId,
                "Why did the bank deny the chargeback?"
        );

        // Assert response
        assertThat(response).isNotNull();
        assertThat(response.answerMarkdown()).contains("According to [bank_chargeback_denial.pdf, Page 1]");
        assertThat(response.workspaceFactVersion()).isEqualTo(1);
        assertThat(response.citations()).hasSize(1);

        PinnedPageCitation citation = response.citations().get(0);
        assertThat(citation.documentId()).isEqualTo(docId);
        assertThat(citation.fileName()).isEqualTo("bank_chargeback_denial.pdf");
        assertThat(citation.pageNumber()).isEqualTo(1);
        assertThat(citation.matchedTopic()).isEqualTo("chargeback_denial");

        // Verify conversation turns appended to audit trail
        assertThat(workspace.getChatHistory()).hasSize(2);
        assertThat(workspace.getChatHistory().get(0).getRole()).isEqualTo("USER");
        assertThat(workspace.getChatHistory().get(0).getMessage()).isEqualTo("Why did the bank deny the chargeback?");
        assertThat(workspace.getChatHistory().get(1).getRole()).isEqualTo("AI");
        assertThat(workspace.getChatHistory().get(1).getMessage()).contains("Axis Bank declined");

        verify(workspaceRepository, times(1)).save(workspace);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if question is blank")
    void throwsOnBlankQuestion() {
        assertThatThrownBy(() -> assistantService.askWorkspaceAssistant(workspaceId, clerkUserId, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question cannot be blank");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if workspace is not found or unauthorized")
    void throwsOnWorkspaceNotFound() {
        when(workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> assistantService.askWorkspaceAssistant(workspaceId, clerkUserId, "Any updates?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workspace not found or access denied");
    }
}
