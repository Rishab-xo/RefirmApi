package com.app.refirm.arguments.service;

import com.app.refirm.arguments.domain.*;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.core.service.LegalRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.app.refirm.workspace.util.JsonFenceSanitizer;

import java.time.Instant;
import java.util.*;

/**
 * Core orchestrator for Courtroom Argument Preparation, Adversary Rebuttal Matrix,
 * Bench Grill Simulator, and Authority Firewall Sanitization.
 */
@Service
public class ArgumentPrepService {

    private static final Logger log = LoggerFactory.getLogger(ArgumentPrepService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final WorkspaceArgumentSheetRepository argumentSheetRepository;
    private final PrecedentAuthorityProvider precedentProvider;
    private final AuthorityFirewallSanitizer firewallSanitizer;
    private final ArgumentMarkdownRenderer markdownRenderer;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final LegalRetrievalService legalRetrievalService;

    @Autowired
    public ArgumentPrepService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            PrecedentAuthorityProvider precedentProvider,
            AuthorityFirewallSanitizer firewallSanitizer,
            ArgumentMarkdownRenderer markdownRenderer,
            @Qualifier("conversationChatClient") ChatClient chatClient,
            ObjectMapper objectMapper,
            @Autowired(required = false) LegalRetrievalService legalRetrievalService
    ) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.argumentSheetRepository = argumentSheetRepository;
        this.precedentProvider = precedentProvider;
        this.firewallSanitizer = firewallSanitizer;
        this.markdownRenderer = markdownRenderer;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.legalRetrievalService = legalRetrievalService;
    }

    public ArgumentPrepService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            PrecedentAuthorityProvider precedentProvider,
            AuthorityFirewallSanitizer firewallSanitizer,
            ArgumentMarkdownRenderer markdownRenderer,
            ChatClient chatClient,
            ObjectMapper objectMapper
    ) {
        this(workspaceRepository, documentRepository, argumentSheetRepository,
                precedentProvider, firewallSanitizer, markdownRenderer, chatClient, objectMapper, null);
    }

    public ArgumentPrepService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            PrecedentAuthorityProvider precedentProvider,
            AuthorityFirewallSanitizer firewallSanitizer,
            ArgumentMarkdownRenderer markdownRenderer
    ) {
        this(workspaceRepository, documentRepository, argumentSheetRepository,
                precedentProvider, firewallSanitizer, markdownRenderer, null, new ObjectMapper(), null);
    }

    /**
     * Method 1: Generates an end-to-end Courtroom Argument Sheet for a workspace.
     */
    @Transactional
    public WorkspaceArgumentSheet generateArgumentSheet(
            UUID workspaceId,
            String clerkUserId,
            PartyPerspective overridePerspective,
            String customFocusInstruction
    ) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        PartyPerspective targetPerspective = overridePerspective != null
                ? overridePerspective
                : workspace.getPartyPerspective();

        CaseFactLedger ledger = workspace.getFactLedger();
        int sourceFactLedgerVersion = ledger != null ? ledger.version() : 0;

        DraftingContext context = workspace.getLatestDraftingContext();
        List<StatutePin> allowedStatutes = new ArrayList<>();
        if (context != null && context.statutePack() != null) {
            allowedStatutes.addAll(context.statutePack());
        }

        String effectiveQuery = ((customFocusInstruction != null && !customFocusInstruction.isBlank()) ? customFocusInstruction + " " : "")
                + (workspace.getTitle() != null ? workspace.getTitle() : "") + " "
                + (context != null && context.userNarrative() != null ? context.userNarrative() : "");

        List<String> triggeredBarDirectives = new ArrayList<>();
        if (legalRetrievalService != null) {
            String factLedgerText = (ledger != null && ledger.extractedFacts() != null)
                    ? ledger.extractedFacts().toString()
                    : "";

            LegalRetrievalService.RetrievalResult retrievalResult = legalRetrievalService.retrieveUniversal3BucketStatutes(
                    effectiveQuery,
                    workspace.getPrimaryDomain(),
                    factLedgerText,
                    12
            );
            if (retrievalResult != null) {
                if (retrievalResult.toStatutePins() != null) {
                    for (StatutePin pin : retrievalResult.toStatutePins()) {
                        String pinActNorm = normalizeActForDedup(pin.actName());
                        String pinSecNorm = normalizeSecForDedup(pin.sectionNumber());
                        boolean alreadyPresent = allowedStatutes.stream()
                                .anyMatch(existing -> normalizeActForDedup(existing.actName()).equals(pinActNorm)
                                        && normalizeSecForDedup(existing.sectionNumber()).equals(pinSecNorm));
                        if (!alreadyPresent) {
                            allowedStatutes.add(pin);
                        }
                    }
                }
                if (retrievalResult.triggeredBarDirectives() != null) {
                    triggeredBarDirectives.addAll(retrievalResult.triggeredBarDirectives());
                }
            }
        }

        List<WorkspaceDocument> allDocs = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId);

        List<WorkspaceDocument> activeEvidenceDocs = (allDocs != null)
                ? allDocs.stream().filter(d -> d.getDocumentType() != DocumentType.JUDGMENT_PRECEDENT).toList()
                : List.of();

        List<VerifiedPrecedentAuthority> precedents = precedentProvider.resolveVerifiedPrecedents(workspace);

        // Build prompts for ChatClient
        String systemPrompt = buildAdvocacySystemPrompt(targetPerspective, allowedStatutes, precedents, context, triggeredBarDirectives);
        StringBuilder userPrompt = buildAdvocacyUserPrompt(
                workspace,
                targetPerspective,
                allowedStatutes,
                precedents,
                activeEvidenceDocs,
                customFocusInstruction,
                triggeredBarDirectives
        );

        ArgumentDossier rawDossier = null;
        if (chatClient != null) {
            try {
                rawDossier = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt.toString())
                        .call()
                        .entity(ArgumentDossier.class);
            } catch (Exception e) {
                log.warn("Direct entity extraction from ChatClient failed: {}. Attempting content parse...", e.getMessage());
                try {
                    String rawContent = chatClient.prompt()
                            .system(systemPrompt)
                            .user(userPrompt.toString())
                            .call()
                            .content();
                    if (rawContent != null && !rawContent.isBlank()) {
                        rawDossier = parseDossierJson(rawContent);
                    }
                } catch (Exception ex) {
                    log.warn("Raw content extraction also failed: {}", ex.getMessage());
                }
            }
        }

        if (rawDossier == null) {
            rawDossier = buildDeterministicFallbackDossier(
                    workspace,
                    targetPerspective,
                    allowedStatutes,
                    precedents,
                    activeEvidenceDocs,
                    triggeredBarDirectives
            );
        }

        // Apply strict Authority Firewall sanitization
        ArgumentDossier sanitizedDossier = firewallSanitizer.sanitizeDossier(
                rawDossier,
                allowedStatutes,
                precedents,
                activeEvidenceDocs
        );

        boolean isTacticalHearingNote = sanitizedDossier != null
                && sanitizedDossier.openingPitch60Seconds() != null
                && sanitizedDossier.openingPitch60Seconds().contains("Strategic Advisory");

        String title = isTacticalHearingNote
                ? "Tactical Hearing Note & Counter-Submissions"
                : (targetPerspective == PartyPerspective.CLAIMANT_PETITIONER
                ? "Claimant Courtroom Argument Sheet"
                : (targetPerspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                ? "Respondent Defense Argument Sheet"
                : "Neutral Court Evaluation Sheet"));

        // Pass the statute pack and customFocusInstruction into the overloaded renderer so Section 2B and Source Coverage render
        String renderedMarkdown = markdownRenderer.renderToMarkdown(
                title,
                targetPerspective,
                sanitizedDossier,
                false,
                allowedStatutes,
                effectiveQuery.trim()
        );

        WorkspaceArgumentSheet sheet = new WorkspaceArgumentSheet();
        sheet.setWorkspaceId(workspaceId);
        sheet.setClerkUserId(clerkUserId);
        sheet.setTitle(title);
        sheet.setPartyPerspective(targetPerspective);
        sheet.setVersion(1);
        sheet.setSourceFactLedgerVersion(sourceFactLedgerVersion);
        sheet.setArgumentDossier(sanitizedDossier);
        sheet.setLockedSubmissionIds(new LinkedHashSet<>());
        sheet.setRenderedMarkdown(renderedMarkdown);
        sheet.setCreatedAt(Instant.now());
        sheet.setUpdatedAt(Instant.now());

        log.info("Generated argument sheet v1 for workspace '{}' with perspective '{}'", workspaceId, targetPerspective);
        return argumentSheetRepository.save(sheet);
    }

    /**
     * Method 2: Appends a targeted Rebuttal to an existing Argument Sheet countering an opponent's contention.
     * Strictly preserves existing submissions, issues, and locked submission IDs.
     */
    @Transactional
    public WorkspaceArgumentSheet appendTargetedRebuttal(
            UUID workspaceId,
            UUID sheetId,
            String clerkUserId,
            String oppositePartyContention
    ) {
        WorkspaceArgumentSheet sheet = argumentSheetRepository
                .findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Argument sheet not found: " + sheetId));

        LegalWorkspace workspace = workspaceRepository
                .findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        DraftingContext context = workspace.getLatestDraftingContext();
        List<StatutePin> allowedStatutes = new ArrayList<>();
        if (context != null && context.statutePack() != null) {
            allowedStatutes.addAll(context.statutePack());
        }

        // Dynamic Cross-Statute Context Resolution:
        if (legalRetrievalService != null && oppositePartyContention != null && !oppositePartyContention.isBlank()) {
            try {
                List<StatutePin> crossStatutePins = legalRetrievalService.retrieveCrossStatutePinsForContention(
                        oppositePartyContention,
                        workspace.getPrimaryDomain(),
                        5
                );
                if (crossStatutePins != null && !crossStatutePins.isEmpty()) {
                    for (StatutePin pin : crossStatutePins) {
                        boolean alreadyPresent = allowedStatutes.stream()
                                .anyMatch(existing -> existing.actName().equalsIgnoreCase(pin.actName())
                                        && existing.sectionNumber().equalsIgnoreCase(pin.sectionNumber()));
                        if (!alreadyPresent) {
                            allowedStatutes.add(pin);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Dynamic cross-statute lookup failed for contention '{}': {}", oppositePartyContention, e.getMessage());
            }
        }
        List<VerifiedPrecedentAuthority> precedents = precedentProvider.resolveVerifiedPrecedents(workspace);

        List<WorkspaceDocument> allDocs = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId);
        List<WorkspaceDocument> activeEvidenceDocs = (allDocs != null)
                ? allDocs.stream().filter(d -> d.getDocumentType() != DocumentType.JUDGMENT_PRECEDENT).toList()
                : List.of();

        List<RebuttalMatrixItem> existingRebuttals = new ArrayList<>(
                sheet.getArgumentDossier().rebuttalMatrix() != null
                        ? sheet.getArgumentDossier().rebuttalMatrix()
                        : List.of()
        );

        String nextRebuttalId = "REB_" + (existingRebuttals.size() + 1);

        RebuttalMatrixItem newRebuttal = null;
        if (chatClient != null) {
            String rebuttalSysPrompt = """
                    You are a Senior High Court Advocate and courtroom strategist in India.
                    Draft a single, razor-sharp courtroom counter-rebuttal answering the opponent's contention.
                    
                    STRICT PERSONA RULE (COUNSEL FOR THE PARTY, NEVER FIRST-PERSON LITIGANT "I"):
                    Write strictly from the perspective of Senior Advocate / Counsel addressing the Bench on behalf of the represented party:
                    "May it please this Hon'ble Forum / Court, it is respectfully submitted on behalf of the Complainant/Respondent that the Opposite Party's contention is misconceived in law and fact..."
                    NEVER use first-person singular pronouns ("I", "my", "me", "mine"). State counter-rebuttal assertively without self-sabotaging hedges.
                    
                    ASSERTIVE ADVOCACY & INLINE BRACKETED MARKERS:
                    - State factual grounds affirmatively and persuasively. If an exact document or date is not in record, insert an inline marker like [Verify from Record: Date/Exhibit] rather than apologetic prose.
                    - Arguing in the alternative: Where appropriate, provide a tight alternative submission ("In the alternative, and strictly without prejudice, even assuming...").
                    
                    MANDATORY 2-FOLD SENIOR ADVOCATE STRUCTURE:
                    Every courtroom counter-rebuttal MUST deliver a seamless 2-fold refutation in natural courtroom prose:
                    1. Substantive Defeat of the Defence on Facts & Ingredients:
                       Directly attack why the opposite party fails the statutory preconditions of the defence they invoked.
                       Specifically, if the opponent invokes intermediary safe harbour / Section 79 IT Act or claims to be a 'mere platform' / intermediary:
                       * Argue that safe harbour under Section 79(1) IT Act is expressly barred by Section 79(2)(a)–(c) IT Act where the platform is not a passive conduit of third-party information, but actively initiates the commercial booking, sets/collects the service consideration, allocates the executing workshop under its own brand, and controls invoicing/warranty.
                       * Cite Section 2(17) ('Electronic Service Provider') and Section 2(42) of the Consumer Protection Act, 2019 read with the Consumer Protection (E-Commerce) Rules, 2020, under which an aggregator controlling service fulfilment cannot evade liability for deficiency in service (Section 2(11)).
                    2. Procedural & Statutory Survival:
                       Cite Section 100 CPA, 2019 (remedies are in addition to and not in derogation of other laws) or parallel statutory non-derogation provision.
                    
                    CRITICAL FORMATTING INSTRUCTION:
                    DO NOT print robotic meta-labels such as "PRONG 1 — Substantive defeat:" or "PRONG 2 — Procedural and statutory survival:". Weave both the substantive factual attack and the statutory non-derogation survival into fluent, natural courtroom advocacy.
                    
                    Ground your answer strictly in the provided statutes and precedents. Do not hallucinate case citations.
                    """;
            String rebuttalUserPrompt = "Opponent Contention: " + oppositePartyContention + "\n"
                    + "Available Statutes: " + allowedStatutes.stream().map(s -> s.actName() + " " + s.sectionNumber()).toList() + "\n"
                    + "Available Precedents: " + precedents.stream().map(VerifiedPrecedentAuthority::caseTitleAndCitation).toList();

            try {
                newRebuttal = chatClient.prompt()
                        .system(rebuttalSysPrompt)
                        .user(rebuttalUserPrompt)
                        .call()
                        .entity(RebuttalMatrixItem.class);
            } catch (Exception e) {
                log.warn("ChatClient rebuttal generation failed: {}", e.getMessage());
            }
        }

        if (newRebuttal == null) {
            if (isIntermediaryOrSafeHarbourContention(oppositePartyContention)) {
                String substantiveRebuttal = "My Lords / Your Honour, it is respectfully submitted on behalf of the Complainant that the Opposite Party's reliance on Section 79 of the Information Technology Act, 2000 is misconceived in law and fact. "
                        + "First, safe harbour under Section 79(1) IT Act applies solely to passive transmission of third-party data and is expressly excluded under Section 79(2)(a)–(c) and Section 79(3) where, as on the present record, the platform is not a passive conduit but actively initiates the commercial booking, sets and collects the service consideration, allocates the executing workshop under its own proprietary brand, and controls invoicing and warranty commitments. Under Section 2(17) ('Electronic Service Provider') and Section 2(42) of the Consumer Protection Act, 2019 read with the Consumer Protection (E-Commerce) Rules, 2020, an aggregator controlling service fulfillment cannot evade statutory liability for deficiency in service under Section 2(11). "
                        + "Second, under Section 100 of the Consumer Protection Act, 2019, the provisions of this Act are in addition to and not in derogation of any other law for the time being in force, so the consumer remedy survives independently and concurrent jurisdiction is intact.";
                String anchor = "Section 79(2)/(3) IT Act, 2000 r/w Sections 2(17), 2(42), 2(11) & 100 Consumer Protection Act, 2019";
                newRebuttal = new RebuttalMatrixItem(nextRebuttalId, oppositePartyContention, substantiveRebuttal, anchor);
            } else {
                String statuteAnchor = !allowedStatutes.isEmpty()
                        ? allowedStatutes.get(0).actName() + " " + allowedStatutes.get(0).sectionNumber()
                        : "Section 100 Consumer Protection Act, 2019";
                String genericRebuttal = "My Lords / Your Honour, it is respectfully submitted on behalf of the Complainant that the Opposite Party's contention is legally untenable on facts and statutory ingredients. "
                        + "First, the Opposite Party fails the statutory preconditions of the defence invoked, as the pleaded facts and documentary record establish direct breach of statutory duties. "
                        + "Second, under Section 100 of the Consumer Protection Act, 2019, the statutory remedies available before this Hon'ble Commission operate in addition to, and not in derogation of, any parallel statutory or contractual framework.";
                newRebuttal = new RebuttalMatrixItem(nextRebuttalId, oppositePartyContention, genericRebuttal, statuteAnchor);
            }
        } else {
            newRebuttal = new RebuttalMatrixItem(
                    nextRebuttalId,
                    oppositePartyContention,
                    newRebuttal.ourCounterRebuttal(),
                    newRebuttal.statutoryOrEvidentiaryAnchor()
            );
        }

        // Sanitize new rebuttal via firewall
        ArgumentDossier singleItemDossier = new ArgumentDossier(
                "", List.of(), List.of(), List.of(newRebuttal), List.of(), List.of()
        );
        ArgumentDossier sanitizedSingle = firewallSanitizer.sanitizeDossier(
                singleItemDossier,
                allowedStatutes,
                precedents,
                activeEvidenceDocs
        );
        RebuttalMatrixItem sanitizedRebuttal = sanitizedSingle.rebuttalMatrix().get(0);

        existingRebuttals.add(sanitizedRebuttal);

        ArgumentDossier updatedDossier = new ArgumentDossier(
                sheet.getArgumentDossier().openingPitch60Seconds(),
                sheet.getArgumentDossier().framedIssues(),
                sheet.getArgumentDossier().coreSubmissions(),
                existingRebuttals,
                sheet.getArgumentDossier().benchGrillQuestions(),
                sheet.getArgumentDossier().preHearingEvidenceChecklist()
        );

        sheet.setArgumentDossier(updatedDossier);
        sheet.setVersion(sheet.getVersion() + 1);
        sheet.setUpdatedAt(Instant.now());

        boolean isStale = sheet.isStaleComparedTo(workspace);
        String renderedMarkdown = markdownRenderer.renderToMarkdown(
                sheet.getTitle(),
                sheet.getPartyPerspective(),
                updatedDossier,
                isStale,
                allowedStatutes
        );
        sheet.setRenderedMarkdown(renderedMarkdown);

        log.info("Appended targeted rebuttal '{}' to sheet '{}', new version: {}", nextRebuttalId, sheetId, sheet.getVersion());
        return argumentSheetRepository.save(sheet);
    }

    /**
     * Method 3: 0 AI Tokens - Manually updates or locks a specific Argument Submission.
     */
    @Transactional
    public WorkspaceArgumentSheet lockOrUpdateSubmissionManually(
            UUID workspaceId,
            UUID sheetId,
            String clerkUserId,
            String submissionId,
            ArgumentSubmission updatedSubmission,
            boolean lockSubmission
    ) {
        WorkspaceArgumentSheet sheet = argumentSheetRepository
                .findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Argument sheet not found: " + sheetId));

        LegalWorkspace workspace = workspaceRepository
                .findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        List<ArgumentSubmission> currentSubmissions = new ArrayList<>(
                sheet.getArgumentDossier().coreSubmissions() != null
                        ? sheet.getArgumentDossier().coreSubmissions()
                        : List.of()
        );

        boolean found = false;
        for (int i = 0; i < currentSubmissions.size(); i++) {
            if (currentSubmissions.get(i).submissionId().equalsIgnoreCase(submissionId)) {
                currentSubmissions.set(i, updatedSubmission);
                found = true;
                break;
            }
        }

        if (!found) {
            currentSubmissions.add(updatedSubmission);
        }

        if (lockSubmission) {
            sheet.getLockedSubmissionIds().add(submissionId);
        }

        ArgumentDossier updatedDossier = new ArgumentDossier(
                sheet.getArgumentDossier().openingPitch60Seconds(),
                sheet.getArgumentDossier().framedIssues(),
                currentSubmissions,
                sheet.getArgumentDossier().rebuttalMatrix(),
                sheet.getArgumentDossier().benchGrillQuestions(),
                sheet.getArgumentDossier().preHearingEvidenceChecklist()
        );

        sheet.setArgumentDossier(updatedDossier);
        sheet.setVersion(sheet.getVersion() + 1);
        sheet.setUpdatedAt(Instant.now());

        List<StatutePin> allowedStatutes = (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null)
                ? workspace.getLatestDraftingContext().statutePack()
                : List.of();
        boolean isStale = sheet.isStaleComparedTo(workspace);
        String renderedMarkdown = markdownRenderer.renderToMarkdown(
                sheet.getTitle(),
                sheet.getPartyPerspective(),
                updatedDossier,
                isStale,
                allowedStatutes
        );
        sheet.setRenderedMarkdown(renderedMarkdown);

        log.info("Updated submission '{}' in sheet '{}', lock: {}, new version: {}", submissionId, sheetId, lockSubmission, sheet.getVersion());
        return argumentSheetRepository.save(sheet);
    }

    // ─── PROMPT BUILDERS ───

    private String resolveForumCalibrationInstruction(DraftingContext context) {
        if (context == null) return "";
        String forum = context.forumAnalysis();
        if (forum == null || forum.isBlank()) return "";
        String lower = forum.toLowerCase();
        boolean isAppellateOrTribunal = lower.contains("high court")
                || lower.contains("supreme court")
                || lower.contains("appellate")
                || lower.contains("tribunal")
                || lower.contains("arbitral")
                || lower.contains("national commission")
                || lower.contains("state commission")
                || lower.contains("nclt")
                || lower.contains("nclat")
                || lower.contains("ncdrc");
        if (isAppellateOrTribunal) {
            return """
                    FORUM CALIBRATION (CONSTITUTIONAL / APPELLATE / SPECIALIZED TRIBUNAL - %s):
                    Adopt a tight, proposition-driven, statutory-construction and ratio-decidendi style. Emphasize jurisdictional competence, legal questions of law, statutory interpretation, and governing precedents.
                    """.formatted(forum);
        } else {
            return """
                    FORUM CALIBRATION (TRIAL / DISTRICT / ORIGINAL FACT-FINDING FORUM - %s):
                    Adopt a fact-and-chronology-driven, exhibit-anchored courtroom style. Focus on foundational pleadings, sequence of events, primary receipts/invoices/reports, and prima facie burden shifting.
                    """.formatted(forum);
        }
    }

    private String buildAdvocacySystemPrompt(
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents
    ) {
        return buildAdvocacySystemPrompt(perspective, allowedStatutes, precedents, null);
    }

    private String buildAdvocacySystemPrompt(
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            DraftingContext context
    ) {
        return buildAdvocacySystemPrompt(perspective, allowedStatutes, precedents, context, List.of());
    }

    private String buildAdvocacySystemPrompt(
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            DraftingContext context,
            List<String> triggeredBarDirectives
    ) {
        String rolePosture = perspective == PartyPerspective.CLAIMANT_PETITIONER
                ? """
                ADVOCACY POSTURE: CLAIMANT / PETITIONER
                - Write openingPitch60Seconds and all coreSubmissions in INSTITUTIONAL COUNSEL ADVOCACY VOICE
                  ready to be delivered aloud to the Bench on behalf of the Complainant / Petitioner:
                  "May it please this Hon'ble Forum / Court, it is respectfully submitted on behalf of the Complainant that under Section 2(7)(ii)..."
                  "The Complainant respectfully submits that Opposite Party No. 1 and No. 2 are jointly and severally liable..."
                  NEVER speak in first-person litigant voice ("I", "my", "me", "mine").
                  NEVER use third-person advisory prose such as "The claimant should prove..." or "Plead territorial jurisdiction...".
                - Frame affirmative submissions establishing: (1) Locus standi & maintainability, (2) Compliance with
                  statutory limitation, (3) Prima facie ingredients of statutory violation, (4) Direct causation of
                  financial or material injury, and (5) Specific, quantified reliefs with statutory interest.
                - Anticipate aggressive maintainability challenges and formulate robust counter-rebuttals.
                - IMPORTANT: Do NOT suggest that a third-party credit-card holder or account holder be made a
                  co-complainant unless explicitly instructed. Frame such a person strictly as a pro-forma witness
                  or payment instrument holder to be supported by an affidavit and account statement.
                """
                : (perspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                ? """
                ADVOCACY POSTURE: OPPOSITE PARTY / RESPONDENT
                - Write openingPitch60Seconds and all coreSubmissions in INSTITUTIONAL COUNSEL DEFENSE VOICE
                  ready to be delivered aloud to the Bench on behalf of the Respondent / Opposite Party:
                  "May it please this Hon'ble Forum / Court, the Respondent respectfully submits that the present proceedings are
                   entirely misconceived and barred at the threshold..."
                  NEVER speak in first-person litigant voice ("I", "my", "me", "mine").
                  NEVER use third-person advisory prose.
                - Frame razor-sharp defense submissions attacking: (1) Threshold maintainability bars & non-joinder,
                  (2) Absence of cause of action, (3) Statutory safe harbours / immunities, (4) Absence of expert
                  evidence, and (5) Speculative and unmitigated damages claims.
                - Anticipate emotional or generalized hardship arguments and rebut them strictly on legal grounds.
                """
                : """
                ADVOCACY POSTURE: NEUTRAL COURT ASSISTANT / EVALUATOR
                - Write submissions in balanced, objective legal analysis voice. Weigh competing propositions,
                  burden of proof allocation, and evidentiary gaps.
                """);

        String forumCalibration = resolveForumCalibrationInstruction(context);

        String barDirectiveInstruction = "";
        if (triggeredBarDirectives != null && !triggeredBarDirectives.isEmpty()) {
            barDirectiveInstruction = "\nPROACTIVELY CONFRONT & DISTINGUISH TRIGGERED STATUTORY BARS:\n"
                    + "The case facts, dates, or party profile have triggered the following mandatory statutory bar / maintainability check(s):\n"
                    + String.join("\n- ", triggeredBarDirectives)
                    + "\nYour coreSubmissions and rebuttalMatrix MUST explicitly confront and distinguish each triggered statutory provision!\n";
        }

        return """
                You are a senior Advocate and courtroom strategist in India.
                Your task is to generate a comprehensive, structured courtroom Argument Dossier (ArgumentDossier).
                
                %s
                
                %s
                %s
                
                OUTPUT SCOPE DETERMINATION:
                Analyze the user's prompt. You MUST still return the standard JSON object, but adapt the content density of the JSON fields based on the query type:
                - SCOPE 1 (TACTICAL_HEARING_NOTE): If the prompt is for an interim application, procedural challenge, or interlocutory defense (e.g., opposing a reference, stay, or injunction): Address ONLY the contested motion. Limit 'framedIssues' to 1-3 targeted procedural issues. Do not pad 'coreSubmissions' with unrelated trial thresholds. Connect admissions across issues (e.g., a balance-sheet admission negates an arbitration dispute).
                - SCOPE 2 (PROPOSITION_NOTE): If the prompt is a single legal question without a factual dispute: Provide a crisp legal proposition, controlling ratios, and adverse distinctions.
                - SCOPE 3 (FULL_ARGUMENT_DOSSIER): If the prompt is an open-ended dispute narrative or full trial brief: Populate all fields comprehensively.

                STRATEGIC COUNSEL ADVISORY (CANDOUR DIRECTIVE):
                - STRATEGIC COUNSEL ADVISORY: If the user's prompt explicitly instructs an absolute, high-risk, or legally vulnerable stance (e.g., claiming a statute flatly extinguishes another regime when established jurisprudence prescribes a nuanced rule of sequencing/harmonisation), do NOT silently weaken the argument without notice. Instead, prepend a blockquote starting with "> **Strategic Advisory & Framing Note:**" at the very beginning of the 'openingPitch60Seconds' JSON string, stating: "You instructed to argue [Goal]. However, advancing an unqualified assertion risks resistance under [Jurisprudence]. The stronger strategy is [Recommended Framing]." If the requested argument is legally sound, omit this advisory entirely.

                STRICT PERSONA RULE (COUNSEL FOR THE PARTY, NEVER FIRST-PERSON LITIGANT "I"):
                - Write every section strictly from the perspective of Counsel addressing the Bench on behalf of the represented party:
                  e.g., "May it please this Hon'ble Forum / Court, Counsel for the Complainant/Petitioner/Claimant/Respondent respectfully submits..." or "It is respectfully submitted on behalf of the Petitioner that...".
                - STRICT BAN: NEVER use first-person singular pronouns ("I", "my", "me", "mine") to refer to the litigant, the litigant's property, or the litigant's burden of proof anywhere in openingPitch60Seconds, framedIssues, coreSubmissions, rebuttalMatrix, or benchGrillQuestions!
                  * WRONG: "I am the vehicle owner... my Ford EcoSport... my friend's card... Burden of Proof: I must prove...".
                  * CORRECT: "The Complainant is the registered owner... the subject vehicle... the consideration was tendered via... Initial onus lies on the Complainant...".
                
                CLEAN COURTROOM PROSE & BAN ON INLINE BRACKET CLUTTER (SECTIONS 1 TO 5):
                - State the represented party's factual case and statutory propositions affirmatively, forcefully, and persuasively in clean, fluent spoken courtroom English that Counsel can read straight to the Bench.
                - STRICT BAN: DO NOT litter oral sentences in openingPitch60Seconds, coreSubmissions, rebuttalMatrix, or benchGrillQuestions with inline bracket markers like `[Verify from Record: ...]` or `[Annex: ...]`!
                - Consolidate all missing evidentiary particulars, dates, invoice numbers, signing particulars, and required documentary annexures strictly into Section 6 (preHearingEvidenceChecklist).
                
                STRATEGIC FRAMING & SEQUENCING (OPENING PITCH & CS1):
                - Open with strategic framing: identify the primary statutory hook, conflict, or procedural trap in the matter (for example, if two enactments collide such as insolvency vs. arbitration, explain the exact sequencing rule / order of business established by the Supreme Court rather than making a flat assertion).
                - Anticipate how opposing counsel will try to turn the leading authority against us, and disarm it immediately.
                - Write openingPitch60Seconds in crisp, natural spoken courtroom English ready to read straight to the Bench.
                
                NATURAL STRATEGIC SUBMISSIONS (NO ROBOTIC BOILERPLATE REPETITION):
                - STOP mechanically ending every single submission (CS1–CS6) with "In the alternative, and strictly without prejudice..."! That creates robotic, templated advocacy.
                - State the primary submission with conviction. Include an alternative plea ONLY on a specific submission where a genuine legal or factual fallback exists (e.g., limitation acknowledgment vs. fresh promise, or primary defect vs. warranty breach), and phrase it naturally and variably without repeating stock phrases.
                - For framedIssues.burdenOfProof: state the governing statutory standard and how the initial onus shifts to the opposite party once prima facie facts are shown (e.g., "Initial onus lies on the Complainant to establish the service transaction and defect via primary invoice and technical report, whereupon the burden shifts to the Opposite Parties to justify the non-disclosure"), NEVER "I must prove...".
                
                STRATEGIC WEAK POINTS & BENCH TRAPS (BENCH GRILL QUESTIONS):
                - Frame benchGrillQuestions around the hardest strategic weak points, timing traps, and bench traps Counsel must prepare for (e.g., phrasing traps to avoid, adverse High Court/NCLAT/SC rulings, signing dates vs. upload dates, qualified balance-sheet footnote traps, or timing defects in the opponent's filing).
                - Provide a concrete, authoritative courtroom response for each trap.
                
                MANDATORY 2-FOLD SENIOR ADVOCATE STRUCTURE FOR REBUTTALS:
                When drafting rebuttalMatrix items countering opponent arguments:
                - Substantive Defeat on Facts & Ingredients: Directly attack why opponent fails statutory preconditions.
                  If opponent invokes intermediary safe harbour / Section 79 IT Act or claims to be a 'mere platform':
                  * Show safe harbour under Section 79(1) is barred under Section 79(2)(a)–(c) where the platform actively initiates booking, sets/collects service consideration, allocates workshop under its own brand, and controls invoicing/warranty.
                  * Cite Section 2(17) ('Electronic Service Provider') and Section 2(42) CPA, 2019 read with Consumer Protection (E-Commerce) Rules, 2020 (cannot evade liability for deficiency under Section 2(11)).
                - Procedural & Statutory Survival: Cite Section 100 CPA, 2019 (remedies are in addition to and not in derogation of any other law) or parallel statutory non-derogation provision.
                - Use INSTITUTIONAL COUNSEL COURTROOM SUBMISSION VOICE ("May it please this Hon'ble Commission / Court, it is respectfully submitted that the Opposite Party's reliance on ... is misconceived in law and fact...").
                - CRITICAL: Never output literal meta-labels such as "PRONG 1 —" or "PRONG 2 —" in the text.
                
                2-TIER AUTHORITY RULE & STRICT FIREWALL INSTRUCTIONS:
                1. Tier A (Corpus-Verified Authorities): When VERIFIED PRECEDENT AUTHORITIES are provided, cite them with their exact citations and ratio.
                2. Tier B (Settled Landmark Supreme Court / High Court Jurisprudence): Where a well-settled, binding Supreme Court or High Court landmark judgment directly governs the exact statutory question (e.g., on arbitrability, limitation acknowledgments, Section 10A bars, bail guidelines, cheque dishonour, intermediary safe harbour, etc.), ACTIVELY CITE the controlling landmark judgment(s), explain their exact ratio/sequencing rule, and distinguish adverse holdings (e.g. how the opponent will try to cite it and why our case is distinguishable on facts).
                   - Never invent fake case names or guess pinpoint paragraph numbers you are unsure of. If citing a real landmark authority not present in verifiedPrecedents, append "(verify pin-cite before hearing)".
                3. Only cite statutory provisions listed in the STATUTE PACK section (or unbundled cross-statutes directly relevant to the dispute).
                4. Anchor evidence ONLY to the uploaded document filenames provided. If no relevant document is
                   present for a submission, set verifiedEvidenceAnchor to "NONE_UPLOADED" and do NOT invent a filename.
                5. Do NOT repeat the phrase "no relevant evidence is in the uploaded vault" or similar noise across
                   every submission. Consolidate evidence gaps in preHearingEvidenceChecklist only.
                6. Output strictly JSON conforming to the ArgumentDossier schema.
                """.formatted(rolePosture, forumCalibration, barDirectiveInstruction);
    }

    private StringBuilder buildAdvocacyUserPrompt(
            LegalWorkspace workspace,
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs,
            String customFocusInstruction
    ) {
        return buildAdvocacyUserPrompt(workspace, perspective, allowedStatutes, precedents, activeEvidenceDocs, customFocusInstruction, List.of());
    }

    private StringBuilder buildAdvocacyUserPrompt(
            LegalWorkspace workspace,
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs,
            String customFocusInstruction,
            List<String> triggeredBarDirectives
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("### CASE TITLE: ").append(workspace.getTitle()).append("\n");
        sb.append("### TARGET PERSPECTIVE: ").append(perspective).append("\n\n");

        if (workspace.getFactLedger() != null) {
            sb.append("### CASE FACT LEDGER (Version ").append(workspace.getFactLedger().version()).append("):\n");
            if (workspace.getFactLedger().extractedFacts() != null) {
                sb.append(workspace.getFactLedger().extractedFacts().toString()).append("\n");
            }
        }

        if (workspace.getLatestDraftingContext() != null) {
            sb.append("\n### DISPUTE NARRATIVE:\n").append(workspace.getLatestDraftingContext().userNarrative()).append("\n");
        }

        sb.append("\n### GOVERNING STATUTE PACK:\n");
        if (allowedStatutes != null && !allowedStatutes.isEmpty()) {
            for (StatutePin pin : allowedStatutes) {
                sb.append("- ").append(pin.actName()).append(" ").append(pin.sectionNumber()).append(": ").append(pin.operativeText()).append("\n");
            }
        } else {
            sb.append("None provided.\n");
        }

        sb.append("\n### VERIFIED PRECEDENT AUTHORITIES:\n");
        if (precedents != null && !precedents.isEmpty()) {
            for (VerifiedPrecedentAuthority auth : precedents) {
                sb.append("- ").append(auth.caseTitleAndCitation()).append(" (Ratio: ").append(auth.ratioSummary()).append(")\n");
            }
        } else {
            sb.append("NONE UPLOADED OR VERIFIED.\n");
        }

        sb.append("\n### UPLOADED EVIDENTIARY VAULT DOCUMENTS:\n");
        List<WorkspaceDocument> relevantDocs = filterRelevantVaultDocuments(activeEvidenceDocs, workspace);
        if (relevantDocs != null && !relevantDocs.isEmpty()) {
            for (WorkspaceDocument doc : relevantDocs) {
                sb.append("- ").append(doc.getFileName()).append(" (Pages: ").append(doc.getTotalPages()).append(")\n");
                String gist = doc.getStructuredSummary() != null && doc.getStructuredSummary().executiveGist() != null
                        ? doc.getStructuredSummary().executiveGist()
                        : "";
                if (!gist.isBlank()) {
                    sb.append("  Summary: ").append(gist, 0, Math.min(gist.length(), 200)).append("\n");
                }
            }
        } else {
            sb.append("NO RELEVANT CASE DOCUMENTS UPLOADED. Submit evidentiary annexures as PDF to anchor submissions.\n");
        }

        if (triggeredBarDirectives != null && !triggeredBarDirectives.isEmpty()) {
            sb.append("\n### TRIGGERED STATUTORY BAR / MAINTAINABILITY WARNINGS (MUST CONFRONT & DISTINGUISH):\n");
            for (String directive : triggeredBarDirectives) {
                sb.append("- ⚠️ ").append(directive).append("\n");
            }
        }

        if (customFocusInstruction != null && !customFocusInstruction.isBlank()) {
            sb.append("\n### ADVOCATE SPECIFIC INSTRUCTION:\n").append(customFocusInstruction.trim()).append("\n");
        }

        sb.append("\nGenerate the complete courtroom ArgumentDossier now in JSON format:\n");
        sb.append("OUTPUT SCOPE DETERMINATION: Adapt content density in the JSON object (SCOPE 1: TACTICAL_HEARING_NOTE for interim/procedural motions with 1-3 targeted issues; SCOPE 2: PROPOSITION_NOTE for legal questions; SCOPE 3: FULL_ARGUMENT_DOSSIER for full briefs). All list fields must remain non-null (use empty lists [] if intentionally shortened).\n");
        sb.append("STRATEGIC COUNSEL ADVISORY: If instructed to argue an absolute, high-risk, or legally vulnerable stance, do not silently weaken it; prepend a blockquote starting with '> **Strategic Advisory & Framing Note:** You instructed to argue [Goal]. However, advancing an unqualified assertion risks resistance under [Jurisprudence]. The stronger strategy is [Recommended Framing].\\n\\n' to openingPitch60Seconds.\n");
        sb.append("IMPORTANT: Write strictly from the institutional perspective of Counsel representing the party (e.g. 'The Complainant submits...', 'May it please this Hon'ble Forum/Court...'). NEVER use first-person singular pronouns ('I', 'my', 'me', 'mine'). State submissions affirmatively and persuasively without self-sabotaging hedging. Confine evidence gaps strictly to preHearingEvidenceChecklist.\n");
        sb.append("Write clean, fluent courtroom prose without inline [Verify from Record: ...] bracket clutter in Sections 1–5. Consolidate evidentiary gaps strictly into Section 6.\n");
        sb.append("Only include alternative pleas where a genuine legal or fallback exists; do not repeat stock boilerplate phrases across submissions.\n");
        sb.append("Identify hardest strategic weak points and bench traps in benchGrillQuestions.\n");
        sb.append("Cap preHearingEvidenceChecklist to a maximum of 6 high-priority, concrete documentary items.\n");
        return sb;
    }

    /**
     * Filters workspace vault documents to only those that are plausibly relevant to the case.
     * Documents whose executive gist or file name has zero overlap with case domain/parties/facts
     * (e.g. an unrelated personal resume or unrelated contract) are excluded so they are not
     * referenced across every submission S1-S7.
     */
    private List<WorkspaceDocument> filterRelevantVaultDocuments(
            List<WorkspaceDocument> allDocs,
            LegalWorkspace workspace
    ) {
        if (allDocs == null || allDocs.isEmpty()) return List.of();

        // Build a keyword set from workspace title, client name, fact ledger, and primary domain
        Set<String> caseKeywords = new java.util.HashSet<>();
        if (workspace.getTitle() != null) {
            Arrays.stream(workspace.getTitle().toLowerCase().split("\\s+")).filter(w -> w.length() > 3).forEach(caseKeywords::add);
        }
        if (workspace.getClientName() != null) {
            Arrays.stream(workspace.getClientName().toLowerCase().split("\\s+")).filter(w -> w.length() > 3).forEach(caseKeywords::add);
        }
        if (workspace.getPrimaryDomain() != null) {
            caseKeywords.add(workspace.getPrimaryDomain().name().toLowerCase());
        }
        if (workspace.getFactLedger() != null && workspace.getFactLedger().extractedFacts() != null) {
            String factsStr = workspace.getFactLedger().extractedFacts().toString().toLowerCase();
            Arrays.stream(factsStr.split("\\s+")).filter(w -> w.length() > 4).limit(30).forEach(caseKeywords::add);
        }

        if (caseKeywords.isEmpty()) {
            // Cannot determine relevance; include all
            return allDocs;
        }

        return allDocs.stream().filter(doc -> {
            String gist = doc.getStructuredSummary() != null && doc.getStructuredSummary().executiveGist() != null
                    ? doc.getStructuredSummary().executiveGist()
                    : "";
            String combined = ((doc.getFileName() != null ? doc.getFileName() : "") + " " + gist).toLowerCase();
            return caseKeywords.stream().anyMatch(combined::contains);
        }).collect(java.util.stream.Collectors.toList());
    }

    private ArgumentDossier buildDeterministicFallbackDossier(
            LegalWorkspace workspace,
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs
    ) {
        return buildDeterministicFallbackDossier(workspace, perspective, allowedStatutes, precedents, activeEvidenceDocs, List.of());
    }

    private ArgumentDossier buildDeterministicFallbackDossier(
            LegalWorkspace workspace,
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs,
            List<String> triggeredBarDirectives
    ) {
        String clientName = workspace.getClientName() != null ? workspace.getClientName() : "Client";
        String pitch = perspective == PartyPerspective.CLAIMANT_PETITIONER
                ? "May it please this Hon'ble Court, this is an originating proceeding on behalf of " + clientName
                + " seeking statutory relief for clear statutory violations resulting in documented financial injury."
                : "May it please this Hon'ble Court, on behalf of the Respondent, we submit that the present proceedings are entirely misconceived, premature, and barred at the threshold.";

        List<FramedLegalIssue> issues = List.of(
                new FramedLegalIssue(
                        "ISSUE_1",
                        "Whether the proceedings are maintainable and within statutory limitation?",
                        perspective == PartyPerspective.CLAIMANT_PETITIONER
                                ? "Initial onus lies on the Claimant / Petitioner to establish territorial and pecuniary jurisdiction, whereupon the burden shifts to the Opposite Party to establish any statutory bar."
                                : "Onus lies on the Respondent to substantiate the threshold maintainability bar raised."
                ),
                new FramedLegalIssue(
                        "ISSUE_2",
                        "Whether there exists a subsisting legal liability and quantifiable injury under applicable law?",
                        "Initial onus rests on the Complainant to establish the transaction and breach, whereupon the burden shifts to the Opposite Party to substantiate rebuttal."
                )
        );

        List<String> statutePins = allowedStatutes.stream()
                .map(s -> s.actName() + " " + s.sectionNumber())
                .limit(2)
                .toList();

        List<String> precedentPins = precedents.stream()
                .map(VerifiedPrecedentAuthority::caseTitleAndCitation)
                .limit(2)
                .toList();

        String evidenceAnchor = (activeEvidenceDocs != null && !activeEvidenceDocs.isEmpty())
                ? "[" + activeEvidenceDocs.get(0).getFileName() + ", Page 1]"
                : "NONE_UPLOADED";

        String gapWarning = evidenceAnchor.equals("NONE_UPLOADED")
                ? "No documentary evidence uploaded to substantiate this submission. Upload relevant annexures to vault."
                : null;

        List<ArgumentSubmission> submissions = List.of(
                new ArgumentSubmission(
                        "SUB_1",
                        perspective == PartyPerspective.CLAIMANT_PETITIONER ? "Maintainability and Territorial Jurisdiction" : "Threshold Maintainability Bar",
                        perspective == PartyPerspective.CLAIMANT_PETITIONER
                                ? "The Claimant has established complete compliance with statutory conditions precedent and limitation."
                                : "The present proceedings are barred under statutory preconditions and disclose no actionable cause of action.",
                        statutePins,
                        precedentPins,
                        evidenceAnchor,
                        gapWarning
                )
        );

        List<RebuttalMatrixItem> rebuttals = new ArrayList<>();
        int rebIdx = 1;
        if (triggeredBarDirectives != null && !triggeredBarDirectives.isEmpty()) {
            for (String directive : triggeredBarDirectives) {
                rebuttals.add(new RebuttalMatrixItem(
                        "REB_" + rebIdx++,
                        "Opponent will assert statutory maintainability bar: " + directive,
                        perspective == PartyPerspective.CLAIMANT_PETITIONER
                                ? "May it please this Hon'ble Court/Tribunal, the statutory bar is inapplicable or excluded on facts, supported by written acknowledgment and continuous cause of action."
                                : "The statutory bar constitutes a mandatory jurisdictional impediment requiring summary dismissal.",
                        !statutePins.isEmpty() ? statutePins.get(0) : "Statutory Maintainability Ground"
                ));
            }
        }
        if (rebuttals.isEmpty()) {
            rebuttals.add(new RebuttalMatrixItem(
                    "REB_1",
                    perspective == PartyPerspective.CLAIMANT_PETITIONER
                            ? "Opponent will assert that dispute is purely commercial/contractual or lacks statutory remedy."
                            : "Claimant will assert that statutory liability is strict and non-rebuttable.",
                    perspective == PartyPerspective.CLAIMANT_PETITIONER
                            ? "Statutory remedies are concurrent and independent of private contractual disputes."
                            : "Statutory liability requires establishing condition precedents which Claimant has failed to discharge.",
                    !statutePins.isEmpty() ? statutePins.get(0) : "Statutory Provisions"
            ));
        }

        List<BenchQuestionItem> benchQuestions = List.of(
                new BenchQuestionItem(
                        "Counsel, how do you cross the threshold limitation hurdle and establish jurisdiction?",
                        "We submit that limitation began to run only upon accrual of the final cause of action, and all ingredients are established on affidavit.",
                        !statutePins.isEmpty() ? statutePins.get(0) : "Governing Statute"
                )
        );

        List<String> checklist = List.of(
                "Ensure authenticated copy of complaint/reply is ready with pagination",
                "Verify proof of service and tracking receipts",
                "Carry original or certified copies of relied documentary annexures"
        );

        return new ArgumentDossier(pitch, issues, submissions, rebuttals, benchQuestions, checklist);
    }

    private ArgumentDossier parseDossierJson(String json) {
        try {
            String clean = JsonFenceSanitizer.sanitizeJson(json);
            return objectMapper.readValue(clean, ArgumentDossier.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON into ArgumentDossier: {}", e.getMessage());
            return null;
        }
    }

    private boolean isIntermediaryOrSafeHarbourContention(String contention) {
        if (contention == null || contention.isBlank()) return false;
        String lower = contention.toLowerCase();
        return lower.contains("79")
                || lower.contains("intermediary")
                || lower.contains("safe harbour")
                || lower.contains("safe harbor")
                || lower.contains("mere platform")
                || lower.contains("aggregator")
                || lower.contains("it act");
    }

    private static String normalizeActForDedup(String act) {
        if (act == null) return "";
        return act.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String normalizeSecForDedup(String sec) {
        if (sec == null) return "";
        String s = sec.toLowerCase().replace("section", "").replace("sec", "").replace("_", "").replace(".md", "");
        return s.replaceAll("[^a-z0-9]", "");
    }
}
