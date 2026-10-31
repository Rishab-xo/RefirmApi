package com.app.refirm.workspace.service;

import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.service.ArgumentPrepService;
import com.app.refirm.templates.domain.TemplateRecommendation;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.service.HybridTemplateDraftService;
import com.app.refirm.templates.service.TemplateRecommendationService;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.controller.dto.NextStepSuggestionCard;
import com.app.refirm.workspace.controller.dto.WorkspacePromptRequest;
import com.app.refirm.workspace.controller.dto.WorkspacePromptResponse;
import com.app.refirm.workspace.controller.dto.WorkspacePromptResponse.PromptCitationItem;
import com.app.refirm.workspace.controller.dto.WorkspacePromptResponse.PromptCreatedArtifactDTO;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.PromptExecutionMode;
import com.app.refirm.workspace.domain.WorkspaceChatResponse;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Core Prompt Router implementing View 1B (Wireframes 1A & 1B).
 * Executes safe routing across Triage, Query, Research, Arguments, and Review.
 * In AUTO mode, it NEVER automatically triggers a full 3,000-word Draft,
 * instead presenting 3 dynamic "What's next?" cards for lawyer consent.
 */
@Service
public class WorkspacePromptRouterService {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePromptRouterService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceOrchestratorService orchestratorService;
    private final WorkspaceAssistantService assistantService;
    private final TemplateRecommendationService recommendationService;
    private final HybridTemplateDraftService draftService;
    private final ArgumentPrepService argumentPrepService;
    private final com.app.refirm.workspace.repository.WorkspaceDocumentRepository documentRepository;

    @Autowired
    public WorkspacePromptRouterService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceOrchestratorService orchestratorService,
            @Autowired(required = false) WorkspaceAssistantService assistantService,
            @Autowired(required = false) TemplateRecommendationService recommendationService,
            @Autowired(required = false) HybridTemplateDraftService draftService,
            @Autowired(required = false) ArgumentPrepService argumentPrepService,
            @Autowired(required = false) com.app.refirm.workspace.repository.WorkspaceDocumentRepository documentRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.orchestratorService = orchestratorService;
        this.assistantService = assistantService;
        this.recommendationService = recommendationService;
        this.draftService = draftService;
        this.argumentPrepService = argumentPrepService;
        this.documentRepository = documentRepository;
    }

    public WorkspacePromptRouterService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceOrchestratorService orchestratorService,
            WorkspaceAssistantService assistantService,
            TemplateRecommendationService recommendationService,
            HybridTemplateDraftService draftService,
            ArgumentPrepService argumentPrepService
    ) {
        this(workspaceRepository, orchestratorService, assistantService, recommendationService, draftService, argumentPrepService, null);
    }

    @Transactional
    public WorkspacePromptResponse handlePrompt(
            UUID workspaceId,
            String clerkUserId,
            WorkspacePromptRequest request
    ) {
        LegalWorkspace workspace;
        if (workspaceId == null) {
            PartyPerspective perspective = request != null && request.partyPerspective() != null
                    ? request.partyPerspective()
                    : PartyPerspective.CLAIMANT_PETITIONER;
            workspace = orchestratorService.createQuickConsult(clerkUserId, perspective);
        } else {
            workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                    .orElseGet(() -> {
                        PartyPerspective perspective = request != null && request.partyPerspective() != null
                                ? request.partyPerspective()
                                : PartyPerspective.CLAIMANT_PETITIONER;
                        return orchestratorService.createQuickConsult(clerkUserId, perspective);
                    });
        }

        if (request != null && request.temporary() != null && workspace.getMode() == com.app.refirm.workspace.domain.WorkspaceMode.QUICK_CONSULT) {
            workspace.setTemporary(Boolean.TRUE.equals(request.temporary()));
            workspace = workspaceRepository.save(workspace);
        }

        if (request != null && request.partyPerspective() != null && request.partyPerspective() != workspace.getPartyPerspective()) {
            workspace.setPartyPerspective(request.partyPerspective());
            workspace = workspaceRepository.save(workspace);
        }

        String promptText = request != null && request.promptText() != null ? request.promptText().trim() : "";
        PromptExecutionMode mode = request != null && request.executionMode() != null
                ? request.executionMode()
                : PromptExecutionMode.AUTO;
        String locationHint = request != null ? request.locationHint() : "";

        String answerMarkdown;
        List<PromptCitationItem> citations = new ArrayList<>();
        PromptCreatedArtifactDTO createdArtifact = null;

        boolean hasVaultDocs = documentRepository != null && documentRepository.countByWorkspaceId(workspace.getWorkspaceId()) > 0;
        int wordCount = promptText.isBlank() ? 0 : promptText.trim().split("\\s+").length;

        // Archetype 4: DIRECT_ARGUMENTS_OR_JUDGMENTS
        boolean isArchetype4Args = (mode == PromptExecutionMode.ARGUMENTS)
                || (mode == PromptExecutionMode.AUTO && isArgumentPrompt(promptText));

        // Archetype 3: DOCUMENT_CENTRIC_QUERY
        boolean isArchetype3Doc = (mode == PromptExecutionMode.REVIEW)
                || (hasVaultDocs && isDocumentPrompt(promptText));

        // Archetype 1: SHORT_LEGAL_QUERY (< 40 words, no multi-party dispute narrative or monetary loss claim)
        boolean isArchetype1Short = (mode == PromptExecutionMode.AUTO || mode == PromptExecutionMode.QUERY || mode == PromptExecutionMode.RESEARCH)
                && wordCount < 40
                && !isDisputeStory(promptText)
                && !isArchetype4Args
                && !isArchetype3Doc;

        // Archetype 2: FULL_DISPUTE_BRIEF (Dispute story or narrative prompt >= 40 words)
        boolean isArchetype2Dispute = !isArchetype4Args && !isArchetype3Doc && !isArchetype1Short && mode != PromptExecutionMode.DRAFT;

        if (mode == PromptExecutionMode.DRAFT) {
            // Explicit DRAFT requested
            String templateId = null;
            if (recommendationService != null) {
                List<TemplateRecommendation> recs = recommendationService.recommendForWorkspace(workspace);
                if (!recs.isEmpty()) {
                    templateId = recs.get(0).template().templateId();
                }
            }
            if (templateId == null) {
                templateId = "consumer_complaint_sec35_edaakhil";
            }

            if (draftService != null) {
                try {
                    WorkspaceDraft draft = draftService.initializeDraft(workspace.getWorkspaceId(), clerkUserId, templateId, Collections.emptyMap());
                    createdArtifact = new PromptCreatedArtifactDTO("DRAFT", draft.getDraftId(), draft.getTitle(), draft.getVersion());
                    answerMarkdown = "### Courtroom Draft Initialized (v1)\n\n"
                            + "Initialized **" + draft.getTitle() + "** with 0 tokens using your verified case ledger facts.\n\n"
                            + "- **Draft Version:** v" + draft.getVersion() + "\n"
                            + "- **Locked AI Blocks:** " + draft.getLockedAiBlockKeys().size() + "\n"
                            + "- **Missing Particulars:** " + draft.getMissingParticularKeys().size() + "\n\n"
                            + "You can now edit particulars on the right sidebar or trigger AI block population.";
                } catch (Exception ex) {
                    log.warn("Draft initialization failed, falling back to triage answer: {}", ex.getMessage());
                    answerMarkdown = "Attempted to initialize draft, but encountered: " + ex.getMessage();
                }
            } else {
                answerMarkdown = "Drafting service not initialized in current context.";
            }
        } else if (isArchetype4Args) {
            // Archetype 4: DIRECT_ARGUMENTS_OR_JUDGMENTS
            boolean hasSubstantivePromptFacts = promptText != null && !promptText.isBlank() && (
                    promptText.trim().length() > 40
                    || isDisputeStory(promptText)
                    || promptText.matches("(?is).*\\b(20\\d\\d|default|rs\\.?|crores?|lakhs?|inr|dated?|cheque|invoice|section)\\b.*")
            );
            boolean isUntriaged = workspace.getLatestDraftingContext() == null
                    || workspace.getFactLedger() == null
                    || workspace.getFactLedger().extractedFacts() == null;

            if ((isUntriaged || hasSubstantivePromptFacts) && orchestratorService != null) {
                log.info("Workspace {}: Reconciling latest prompt facts into CaseFactLedger before argument synthesis", workspace.getWorkspaceId());
                try {
                    workspace = orchestratorService.runStrategicTriageInWorkspace(
                            workspace.getWorkspaceId(),
                            clerkUserId,
                            promptText,
                            locationHint
                    );
                    workspace = workspaceRepository.findById(workspace.getWorkspaceId()).orElse(workspace);
                } catch (Exception ex) {
                    log.warn("Fact reconciliation before argument synthesis encountered error: {}", ex.getMessage());
                }
            }

            if (argumentPrepService != null) {
                try {
                    WorkspaceArgumentSheet sheet = argumentPrepService.generateArgumentSheet(
                            workspace.getWorkspaceId(),
                            clerkUserId,
                            workspace.getPartyPerspective(),
                            promptText
                    );
                    createdArtifact = new PromptCreatedArtifactDTO("ARGUMENT_SHEET", sheet.getSheetId(), sheet.getTitle(), sheet.getVersion());
                    if (sheet.getRenderedMarkdown() != null && !sheet.getRenderedMarkdown().isBlank()) {
                        answerMarkdown = sheet.getRenderedMarkdown();
                    } else {
                        answerMarkdown = "### Courtroom Argument Dossier Synthesized (v1)\n\n"
                                + "Synthesized end-to-end oral hearing argument sheet grounded in verified case facts.\n\n"
                                + "- **Perspective:** " + sheet.getPartyPerspective() + "\n"
                                + "- **60-Second Pitch:** Generated\n"
                                + "- **Framed Issues:** " + (sheet.getArgumentDossier() != null ? sheet.getArgumentDossier().framedIssues().size() : 0) + "\n"
                                + "- **Rebuttal Matrix:** " + (sheet.getArgumentDossier() != null ? sheet.getArgumentDossier().rebuttalMatrix().size() : 0) + " points\n";
                    }
                } catch (Exception ex) {
                    log.warn("Argument synthesis failed: {}", ex.getMessage());
                    answerMarkdown = "Argument preparation error: " + ex.getMessage();
                }
            } else {
                answerMarkdown = "Courtroom argument service not available.";
            }
        } else if (isArchetype3Doc || isArchetype1Short) {
            // Archetype 3: DOCUMENT_CENTRIC_QUERY or Archetype 1: SHORT_LEGAL_QUERY
            // Routed through WorkspaceAssistantService for crisp, direct, grounded answer without damages table bloat
            if (assistantService != null) {
                try {
                    WorkspaceChatResponse chatResp = assistantService.askWorkspaceQuestion(workspace.getWorkspaceId(), clerkUserId, promptText);
                    answerMarkdown = chatResp.answerMarkdown();
                    if (chatResp.citations() != null) {
                        citations = chatResp.citations().stream()
                                .map(c -> new PromptCitationItem(c.documentId(), c.fileName(), c.pageNumber(), c.matchedTopic()))
                                .toList();
                    }
                } catch (Exception ex) {
                    log.warn("Assistant query failed: {}", ex.getMessage());
                    answerMarkdown = "Legal analysis: " + ex.getMessage();
                }
            } else {
                answerMarkdown = "Assistant service not available.";
            }
        } else if (isArchetype2Dispute) {
            // Archetype 2: FULL_DISPUTE_BRIEF
            log.info("Workspace {}: Running full strategic triage pipeline for dispute narrative", workspace.getWorkspaceId());
            try {
                workspace = orchestratorService.runStrategicTriageInWorkspace(
                        workspace.getWorkspaceId(),
                        clerkUserId,
                        promptText,
                        locationHint
                );
                workspace = workspaceRepository.findById(workspace.getWorkspaceId()).orElse(workspace);
                answerMarkdown = workspace.getLatestBriefMarkdown() != null && !workspace.getLatestBriefMarkdown().isBlank()
                        ? workspace.getLatestBriefMarkdown()
                        : "### Strategic Case Triage Complete\n\n"
                        + "Successfully extracted key factual entities, quantified legal claims, and mapped relevant statutory grounds into your Case Fact Ledger.";
            } catch (Exception ex) {
                log.error("Strategic triage failed, falling back to grounded assistant: {}", ex.getMessage(), ex);
                if (assistantService != null) {
                    WorkspaceChatResponse chatResp = assistantService.askWorkspaceQuestion(workspace.getWorkspaceId(), clerkUserId, promptText);
                    answerMarkdown = chatResp.answerMarkdown();
                    if (chatResp.citations() != null) {
                        citations = chatResp.citations().stream()
                                .map(c -> new PromptCitationItem(c.documentId(), c.fileName(), c.pageNumber(), c.matchedTopic()))
                                .toList();
                    }
                } else {
                    answerMarkdown = "Case processing encountered an error: " + ex.getMessage();
                }
            }
        } else {
            answerMarkdown = "Prompt processed.";
        }


        if (workspace.getPrimaryDomain() == null || workspace.getPrimaryDomain() == LegalDomain.UNKNOWN) {
            workspace.setPrimaryDomain(LegalDomain.CONSUMER);
        }

        // Guarantee conversation turns are audited and persisted in PostgreSQL chat_history
        if (workspace.getChatHistory() == null) {
            workspace.setChatHistory(new ArrayList<>());
        }
        boolean turnAlreadyAdded = false;
        if (!workspace.getChatHistory().isEmpty()) {
            int lastIdx = workspace.getChatHistory().size() - 1;
            com.app.refirm.triage.entities.ConversationTurn lastTurn = workspace.getChatHistory().get(lastIdx);
            if ("AI".equalsIgnoreCase(lastTurn.getRole()) && answerMarkdown != null && answerMarkdown.equals(lastTurn.getMessage())) {
                turnAlreadyAdded = true;
            }
        }
        if (!turnAlreadyAdded && !promptText.isBlank()) {
            workspace.getChatHistory().add(new com.app.refirm.triage.entities.ConversationTurn("USER", promptText, java.time.Instant.now()));
            workspace.getChatHistory().add(new com.app.refirm.triage.entities.ConversationTurn("AI", answerMarkdown, java.time.Instant.now()));
        }
        workspace.setUpdatedAt(java.time.Instant.now());
        workspace = workspaceRepository.save(workspace);

        // Generate the 3 "What's next?" cards
        List<NextStepSuggestionCard> nextCards = buildNextStepCards(workspace);

        LegalDomain domain = workspace.getPrimaryDomain() != null ? workspace.getPrimaryDomain() : LegalDomain.CONSUMER;
        CaseFactLedger ledger = workspace.getFactLedger() != null ? workspace.getFactLedger() : CaseFactLedger.empty();

        return new WorkspacePromptResponse(
                workspace.getWorkspaceId(),
                workspace.getMode(),
                workspace.getPartyPerspective(),
                domain,
                answerMarkdown,
                ledger,
                citations,
                createdArtifact,
                nextCards
        );
    }

    private List<NextStepSuggestionCard> buildNextStepCards(LegalWorkspace workspace) {
        List<NextStepSuggestionCard> cards = new ArrayList<>();

        // Card 1: DRAFT Card (Personalized via recommendation engine)
        String templateId = "consumer_complaint_sec35_edaakhil";
        String templateLabel = "Consumer Complaint Sec 35 (v1: Free)";
        if (recommendationService != null) {
            List<TemplateRecommendation> recs = recommendationService.recommendForWorkspace(workspace);
            if (!recs.isEmpty()) {
                TemplateRecommendation top = recs.get(0);
                templateId = top.template().templateId();
                templateLabel = top.template().title() + " (v1: Free)";
            }
        }
        cards.add(new NextStepSuggestionCard(
                "card-draft-1",
                "DRAFT",
                templateLabel,
                "INITIALIZE_TEMPLATE_DRAFT",
                templateId,
                null,
                "v1: Free"
        ));

        // Card 2: COURTROOM ARGUMENT Card
        cards.add(new NextStepSuggestionCard(
                "card-args-2",
                "ARGS",
                "Courtroom Argument Prep & Rebuttal Matrix",
                "OPEN_ARGUMENT_SHEET",
                null,
                null,
                "Oral Prep"
        ));

        // Card 3: FOLLOWUP PROMPT Card
        cards.add(new NextStepSuggestionCard(
                "card-research-3",
                "BOOK_RESEARCH",
                "Verify Statutory Grounds & Precedent Authorities",
                "RUN_FOLLOWUP_PROMPT",
                null,
                "Verify statutory grounds, limitation period, and verified precedents under " + (workspace.getPrimaryDomain() != null ? workspace.getPrimaryDomain() : "Consumer") + " law",
                "Instant"
        ));

        return cards;
    }

    private boolean isArgumentPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return prompt.matches("(?is).*\\b(prepare|draft|give|frame|build|formulate|advance|present|structure|anticipate|counter|rebut)\\b.*\\b(arguments?|rebuttals?|submissions?|oral\\s+notes?|bench\\s+grill|bench\\s+questions?)\\b.*")
                || prompt.matches("(?is).*\\b(courtroom\\s+arguments?|argument\\s+sheet|rebuttal\\s+matrix|bench\\s+grill|bench\\s+questions?)\\b.*");
    }

    private boolean isDocumentPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return prompt.matches("(?is).*\\b(document|doc|pdf|file|page|invoice|bill|receipt|annexure|agreement|contract|record|evidence|exhibit|review|clause|uploaded)\\b.*");
    }

    private boolean isDisputeStory(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String lower = prompt.toLowerCase();
        boolean hasCurrencyOrNumbers = lower.contains("₹")
                || lower.matches("(?is).*\\b(rs\\.?|rupees|inr|lakhs?|crores?)\\b.*");
        boolean hasDisputeVerbs = lower.matches("(?is).*\\b(purchased|bought|ordered|booked|delivered|defective|damaged|refused|denied|failed|delayed|refund|repudiated|negligence|cheated)\\b.*");
        boolean hasParties = lower.matches("(?is).*\\b(builder|dealer|seller|hospital|doctor|airline|bank|insurance|company|opposite party|complainant)\\b.*");
        return (hasCurrencyOrNumbers && hasDisputeVerbs) || (hasDisputeVerbs && hasParties);
    }
}

