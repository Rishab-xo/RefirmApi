package com.app.refirm.workspace.service;

import com.app.refirm.triage.entities.ConversationTurn;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.PinnedPageCitation;
import com.app.refirm.workspace.domain.WorkspaceChatResponse;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceDocumentPage;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scoped legal Q&A assistant for workspaces.
 * Assembles Tier 1 (ledger + catalog + recent chat) and Tier 2 (Page-OKF retrieved page markdown)
 * to provide hyper-grounded, citation-backed answers without context bloat.
 */
@Service
public class WorkspaceAssistantService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAssistantService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final CaseDocumentOkfRetriever okfRetriever;
    private final ChatClient chatClient;

    @Autowired
    public WorkspaceAssistantService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            CaseDocumentOkfRetriever okfRetriever,
            @Qualifier("conversationChatClient") ChatClient chatClient
    ) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.okfRetriever = okfRetriever;
        this.chatClient = chatClient;
    }

    /**
     * Answers an advocate's case-specific inquiry grounded in the workspace's canonical ledger
     * and targeted Page-OKF pinned document pages.
     */
    @Transactional
    public WorkspaceChatResponse askWorkspaceQuestion(UUID workspaceId, String clerkUserId, String userQuestion) {
        return askWorkspaceAssistant(workspaceId, clerkUserId, userQuestion);
    }

    @Transactional
    public WorkspaceChatResponse askWorkspaceAssistant(UUID workspaceId, String clerkUserId, String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            throw new IllegalArgumentException("Question cannot be blank");
        }

        // 1. Load workspace
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found or access denied: " + workspaceId));

        // 2. Load active document catalog
        List<WorkspaceDocument> activeDocuments = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId)
                .stream()
                .filter(doc -> doc.getIngestionStatus() == com.app.refirm.workspace.domain.IngestionStatus.READY)
                .toList();

        Map<UUID, WorkspaceDocument> docMap = activeDocuments.stream()
                .collect(Collectors.toMap(WorkspaceDocument::getDocumentId, d -> d, (a, b) -> a));

        // 3. Retrieve section-pinned pages (max 5)
        List<WorkspaceDocumentPage> pinnedPages = okfRetriever.retrievePinnedPages(workspaceId, clerkUserId, userQuestion);

        // Build citations
        List<PinnedPageCitation> citations = new ArrayList<>();
        for (WorkspaceDocumentPage page : pinnedPages) {
            WorkspaceDocument doc = docMap.get(page.getDocumentId());
            String fileName = doc != null ? doc.getFileName() : "Document";
            String matchedTopic = "Relevant Evidence";
            if (doc != null && doc.getOkfManifest() != null && doc.getOkfManifest().topicPagePins() != null) {
                for (Map.Entry<String, List<Integer>> entry : doc.getOkfManifest().topicPagePins().entrySet()) {
                    if (entry.getValue() != null && entry.getValue().contains(page.getPageNumber())) {
                        matchedTopic = entry.getKey();
                        break;
                    }
                }
                if ("Relevant Evidence".equals(matchedTopic) && doc.getDocumentType() != null) {
                    matchedTopic = doc.getDocumentType().name();
                }
            }
            citations.add(new PinnedPageCitation(page.getDocumentId(), fileName, page.getPageNumber(), matchedTopic));
        }

        // 4. Assemble grounded multi-tier prompt
        String prompt = buildPrompt(workspace, activeDocuments, pinnedPages, userQuestion);

        // Call ChatClient
        String answerMarkdown;
        try {
            answerMarkdown = chatClient.prompt().user(prompt).call().content();
            if (answerMarkdown == null || answerMarkdown.isBlank()) {
                answerMarkdown = "No answer could be generated from the available case context.";
            }
        } catch (Exception e) {
            log.error("Error invoking chatClient in askWorkspaceAssistant: {}", e.getMessage(), e);
            answerMarkdown = "Assistant could not answer at this moment: " + e.getMessage();
        }

        // 5. Audit trail: append turns to chat history
        if (workspace.getChatHistory() == null) {
            workspace.setChatHistory(new ArrayList<>());
        }
        workspace.getChatHistory().add(new ConversationTurn("USER", userQuestion, Instant.now()));
        workspace.getChatHistory().add(new ConversationTurn("AI", answerMarkdown, Instant.now()));
        workspace.setUpdatedAt(Instant.now());
        workspaceRepository.save(workspace);

        int factVersion = workspace.getFactLedger() != null ? workspace.getFactLedger().version() : 0;
        return new WorkspaceChatResponse(answerMarkdown, citations, factVersion);
    }

    private String buildPrompt(
            LegalWorkspace workspace,
            List<WorkspaceDocument> activeDocuments,
            List<WorkspaceDocumentPage> pinnedPages,
            String userQuestion
    ) {
        PartyPerspective perspective = workspace.getPartyPerspective() != null
                ? workspace.getPartyPerspective()
                : PartyPerspective.CLAIMANT_PETITIONER;

        StringBuilder sb = new StringBuilder();
        sb.append("You are the Refirm Legal Assistant, a precise, hyper-grounded legal assistant assisting an advocate.\n");
        sb.append("Your client/workspace perspective is: ").append(perspective).append(".\n\n");

        sb.append("=== INSTRUCTIONS ===\n");
        sb.append("1. Answer the user's question directly, clearly, and authoritatively using the provided case facts and document pages.\n");
        sb.append("2. Ground all factual assertions strictly in the provided document catalog and pinned page extracts.\n");
        sb.append("3. Cite specific document names and page numbers in the format [FileName, Page X] whenever referring to facts from uploaded documents.\n");
        sb.append("4. Never invent dates, amounts, or statutory provisions not supported by the context.\n");
        sb.append("5. If the pinned pages do not contain the answer, state honestly what is missing or known from the ledger.\n");
        sb.append("6. Always cite relevant Indian statutory provisions accurately (e.g., Section 2(7) for consumer definition, Section 2(11) for deficiency in service, Section 2(47) for unfair trade practices, Section 35 for District Commission complaint, Section 69 for 2-year limitation period under the Consumer Protection Act, 2019).\n");
        sb.append("7. Under Section 2(7) of the Consumer Protection Act, 2019, consideration paid via a friend's card or third-party payment instrument on behalf of the consumer constitutes valid consideration; the third-party cardholder is NOT required to be joined as a co-complainant.\n\n");

        // Tier 1: Statute Pack from latest drafting context
        if (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null) {
            sb.append("=== TIER 1: STATUTE PACK & LEGAL FOUNDATION ===\n");
            for (com.app.refirm.triage.domain.StatutePin pin : workspace.getLatestDraftingContext().statutePack()) {
                sb.append("- ").append(pin.sectionNumber()).append(" (").append(pin.actName()).append("): ").append(pin.operativeText()).append("\n");
            }
            sb.append("\n");
        }

        // Tier 1: Canonical CaseFactLedger
        sb.append("=== TIER 1: CASE FACT LEDGER ===\n");
        CaseFactLedger ledger = workspace.getFactLedger();
        if (ledger != null) {
            sb.append("- Version: ").append(ledger.version()).append("\n");
            if (ledger.extractedFacts() != null) {
                com.app.refirm.triage.domain.ExtractedFacts ef = ledger.extractedFacts();
                sb.append("- Domain: ").append(ef.domain()).append("\n");
                sb.append("- Claimant: ").append(ef.claimant() != null ? ef.claimant().name() : "N/A").append("\n");
                sb.append("- Estimated Financial Loss: ").append(ef.estimatedFinancialLoss()).append("\n");
                sb.append("- Opposite Parties: ").append(ef.oppositeParties()).append("\n");
                sb.append("- Factual Anomalies: ").append(ef.factualAnomalies()).append("\n");
            }
            if (ledger.ruleEvaluationFacts() != null) {
                com.app.refirm.rules.evaluation.ExtractedFactsDTO rf = ledger.ruleEvaluationFacts();
                sb.append("- Rule Domain: ").append(rf.domain()).append("\n");
                sb.append("- Amount: ").append(rf.disputeAmount()).append("\n");
                sb.append("- Location: ").append(rf.location()).append("\n");
                sb.append("- Counterparty: ").append(rf.counterpartyType()).append("\n");
            }
            sb.append("- Lawyer Locked Fields: ").append(ledger.lawyerLockedFields()).append("\n");
        } else {
            sb.append("No fact ledger initialized.\n");
        }
        sb.append("\n");

        // Tier 1: Document Catalog
        sb.append("=== TIER 1: DOCUMENT CATALOG ===\n");
        if (activeDocuments == null || activeDocuments.isEmpty()) {
            sb.append("No uploaded documents in this workspace.\n");
        } else {
            for (WorkspaceDocument doc : activeDocuments) {
                sb.append("- Document: ").append(doc.getFileName())
                        .append(" (Type: ").append(doc.getDocumentType())
                        .append(", Total Pages: ").append(doc.getTotalPages()).append(")\n");
                if (doc.getOkfManifest() != null) {
                    sb.append("  Gist: ").append(doc.getOkfManifest().executiveGist()).append("\n");
                    sb.append("  Important Dates: ").append(doc.getOkfManifest().importantDates()).append("\n");
                    sb.append("  Monetary Amounts: ").append(doc.getOkfManifest().monetaryAmounts()).append("\n");
                }
            }
        }
        sb.append("\n");

        // Tier 1: Last 6 Conversation Turns
        sb.append("=== TIER 1: RECENT CONVERSATION HISTORY ===\n");
        List<ConversationTurn> history = workspace.getChatHistory();
        if (history != null && !history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - 6);
            for (int i = startIdx; i < history.size(); i++) {
                ConversationTurn turn = history.get(i);
                sb.append(turn.getRole()).append(": ").append(turn.getMessage()).append("\n");
            }
        } else {
            sb.append("No prior turns.\n");
        }
        sb.append("\n");

        // Tier 2: Pinned Document Pages
        sb.append("=== TIER 2: PINNED DOCUMENT PAGES (GROUNDING EXTRACTS) ===\n");
        if (pinnedPages == null || pinnedPages.isEmpty()) {
            sb.append("No specific document pages pinned for this query.\n");
        } else {
            for (WorkspaceDocumentPage page : pinnedPages) {
                sb.append("--- PAGE EXTRACT (DocID: ").append(page.getDocumentId())
                        .append(", Page: ").append(page.getPageNumber()).append(") ---\n");
                sb.append(page.getPageMarkdown()).append("\n\n");
            }
        }
        sb.append("\n");

        sb.append("=== USER QUESTION ===\n");
        sb.append(userQuestion).append("\n\n");
        sb.append("=== ASSISTANT ANSWER ===");

        return sb.toString();
    }
}
