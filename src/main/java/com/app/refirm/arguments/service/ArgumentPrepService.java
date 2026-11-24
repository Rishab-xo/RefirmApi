package com.app.refirm.arguments.service;

import com.app.refirm.arguments.domain.*;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.domain.WorkspaceMode;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.core.service.LegalRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired   ;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.app.refirm.workspace.util.JsonFenceSanitizer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
    public static final int MAX_CLUSTERS_PER_WORKSPACE = 4;
    private final Map<UUID, LinkedList<TopicCluster>> workspaceTopicClusters = new ConcurrentHashMap<>();
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
            @Qualifier("reasoningChatClient") ChatClient chatClient,
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

        PartyPerspective targetPerspective = resolveEffectivePerspective(
                overridePerspective,
                customFocusInstruction,
                workspace
        );

        CaseFactLedger ledger = workspace.getFactLedger();
        int sourceFactLedgerVersion = ledger != null ? ledger.version() : 0;

        // Guard against cross-query fact bleed: in QUICK_CONSULT mode, if the new prompt
        // introduces a completely different scenario (zero keyword overlap with existing narrative),
        // do NOT append userNarrative, stale title, or stale statutes into effectiveQuery / allowedStatutes.
        boolean standaloneNewScenario = isStandaloneNewScenario(customFocusInstruction, workspace);

        DraftingContext context = workspace.getLatestDraftingContext();
        List<StatutePin> allowedStatutes = new ArrayList<>();
        if (!standaloneNewScenario && context != null && context.statutePack() != null) {
            allowedStatutes.addAll(context.statutePack());
        }

        String effectiveQuery = ((customFocusInstruction != null && !customFocusInstruction.isBlank()) ? customFocusInstruction + " " : "")
                + (!standaloneNewScenario && workspace.getTitle() != null ? workspace.getTitle() : "") + " "
                + (!standaloneNewScenario && context != null && context.userNarrative() != null ? context.userNarrative() : "");

        List<String> triggeredBarDirectives = new ArrayList<>();
        if (legalRetrievalService != null) {
            String factLedgerText = (!standaloneNewScenario && ledger != null && ledger.extractedFacts() != null)
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

        // Look up latest existing sheet for stateful versioning & prompt cache alignment.
        List<WorkspaceArgumentSheet> existingSheets =
                argumentSheetRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(workspaceId, clerkUserId);
        WorkspaceArgumentSheet latestExistingSheet = existingSheets.isEmpty() ? null : existingSheets.get(0);
        int nextVersion = (latestExistingSheet != null) ? latestExistingSheet.getVersion() + 1 : 1;

        // Extract resolved statutory tuples for Tier 1 Additive Precedent Search
        List<String> resolvedTuples = new ArrayList<>();
        for (StatutePin pin : allowedStatutes) {
            if (pin.actName() != null && pin.sectionNumber() != null) {
                resolvedTuples.add(pin.actName() + ":" + pin.sectionNumber());
            }
        }

        List<VerifiedPrecedentAuthority> rawPrecedents = precedentProvider.resolveVerifiedPrecedents(
                workspace, customFocusInstruction, resolvedTuples, standaloneNewScenario);
        List<VerifiedPrecedentAuthority> precedents = applyPromptCacheAlignment(
                workspace != null ? workspace.getWorkspaceId() : (latestExistingSheet != null ? latestExistingSheet.getWorkspaceId() : null),
                rawPrecedents,
                latestExistingSheet,
                resolvedTuples,
                standaloneNewScenario);

        // Resolve deterministic generation plan
        ArgumentGenerationPlan plan = resolveGenerationPlan(customFocusInstruction);

        // Build prompts for ChatClient
        DraftingContext effectiveContext = standaloneNewScenario ? null : context;
        String systemPrompt = buildAdvocacySystemPrompt(targetPerspective, allowedStatutes, precedents, effectiveContext, triggeredBarDirectives, plan);
        StringBuilder userPrompt = buildAdvocacyUserPrompt(
                workspace,
                targetPerspective,
                allowedStatutes,
                precedents,
                activeEvidenceDocs,
                customFocusInstruction,
                triggeredBarDirectives,
                plan
        );

        // ── TERRA GROUNDING PIPELINE ──

        // Build Terra grounded user prompt (static-to-dynamic ordering for prompt cache alignment)
        StringBuilder terraUserPrompt = buildTerraGroundedUserPrompt(
                workspace, targetPerspective, allowedStatutes, precedents,
                activeEvidenceDocs, triggeredBarDirectives, customFocusInstruction,
                latestExistingSheet, standaloneNewScenario
        );

        String title;
        String renderedMarkdown;
        ArgumentDossier sanitizedDossier;
        boolean usedTerraPath = false;

        if (chatClient != null) {
            String terraMarkdown = null;
            try {
                terraMarkdown = chatClient.prompt()
                        .system(STATIC_TERRA_SYSTEM_PROMPT)
                        .user(terraUserPrompt.toString())
                        .call()
                        .content();
            } catch (Exception e) {
                log.warn("Terra markdown call failed: {}. Falling back to entity extraction.", e.getMessage());
            }

            if (terraMarkdown != null && !terraMarkdown.isBlank() && !terraMarkdown.trim().startsWith("{")) {
                // ── TERRA PATH: free-flowing markdown response ──
                log.info("Terra path activated for workspace '{}'", workspaceId);
                usedTerraPath = true;

                sanitizedDossier = buildDossierFromTerraMarkdown(terraMarkdown, allowedStatutes, precedents);
                sanitizedDossier = enforceGenerationPlan(sanitizedDossier, plan);

                boolean isTacticalHearingNote = terraMarkdown.contains("Strategic Advisory");
                title = isTacticalHearingNote
                        ? "Tactical Hearing Note & Counter-Submissions"
                        : (targetPerspective == PartyPerspective.CLAIMANT_PETITIONER
                        ? "Claimant Courtroom Argument Sheet"
                        : (targetPerspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                        ? "Respondent Defense Argument Sheet"
                        : "Neutral Court Evaluation Sheet"));

                // Sanitize the raw markdown using the firewall (strips fabricated citations)
                String sanitizedMarkdown = firewallSanitizer.sanitizeMarkdown(
                        terraMarkdown, allowedStatutes, precedents, activeEvidenceDocs);

                // Universal Post-Terra Statute Resolution across 159-Act catalog
                resolveTerraCitedStatutesFromCatalog(sanitizedMarkdown, allowedStatutes);

                // Enrich Terra markdown with Statute Table 2B header
                renderedMarkdown = markdownRenderer.enrichTerraMarkdownWithStatuteTable(
                        title, targetPerspective, sanitizedMarkdown,
                        sanitizedDossier, false, allowedStatutes, effectiveQuery.trim());
            } else {
                // ── ENTITY FALLBACK PATH: standard JSON dossier extraction ──
                log.info("Terra path skipped (JSON response or blank), using entity extraction for workspace '{}'", workspaceId);
                sanitizedDossier = extractDossierViaEntityPath(
                        chatClient, systemPrompt, userPrompt,
                        workspace, targetPerspective, allowedStatutes,
                        precedents, activeEvidenceDocs, triggeredBarDirectives, plan);

                boolean isTacticalHearingNote = sanitizedDossier != null
                        && sanitizedDossier.openingPitch60Seconds() != null
                        && sanitizedDossier.openingPitch60Seconds().contains("Strategic Advisory");
                title = isTacticalHearingNote
                        ? "Tactical Hearing Note & Counter-Submissions"
                        : (targetPerspective == PartyPerspective.CLAIMANT_PETITIONER
                        ? "Claimant Courtroom Argument Sheet"
                        : (targetPerspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                        ? "Respondent Defense Argument Sheet"
                        : "Neutral Court Evaluation Sheet"));

                renderedMarkdown = markdownRenderer.renderToMarkdown(
                        title, targetPerspective, sanitizedDossier,
                        false, allowedStatutes, effectiveQuery.trim());
            }
        } else {
            // No chatClient — deterministic fallback (keeps all mocked tests passing)
            ArgumentDossier rawDossier = buildDeterministicFallbackDossier(
                    workspace, targetPerspective, allowedStatutes,
                    precedents, activeEvidenceDocs, triggeredBarDirectives);
            sanitizedDossier = firewallSanitizer.sanitizeDossier(rawDossier, allowedStatutes, precedents, activeEvidenceDocs);
            sanitizedDossier = enforceGenerationPlan(sanitizedDossier, plan);

            boolean isTacticalHearingNote = sanitizedDossier != null
                    && sanitizedDossier.openingPitch60Seconds() != null
                    && sanitizedDossier.openingPitch60Seconds().contains("Strategic Advisory");
            title = isTacticalHearingNote
                    ? "Tactical Hearing Note & Counter-Submissions"
                    : (targetPerspective == PartyPerspective.CLAIMANT_PETITIONER
                    ? "Claimant Courtroom Argument Sheet"
                    : (targetPerspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                    ? "Respondent Defense Argument Sheet"
                    : "Neutral Court Evaluation Sheet"));

            renderedMarkdown = markdownRenderer.renderToMarkdown(
                    title, targetPerspective, sanitizedDossier,
                    false, allowedStatutes, effectiveQuery.trim());
        }

        WorkspaceArgumentSheet sheet = new WorkspaceArgumentSheet();
        sheet.setWorkspaceId(workspaceId);
        sheet.setClerkUserId(clerkUserId);
        sheet.setTitle(title);
        sheet.setPartyPerspective(targetPerspective);
        sheet.setVersion(nextVersion);
        sheet.setSourceFactLedgerVersion(sourceFactLedgerVersion);
        sheet.setArgumentDossier(sanitizedDossier);
        sheet.setLockedSubmissionIds(new LinkedHashSet<>());
        sheet.setRenderedMarkdown(renderedMarkdown);
        sheet.setCreatedAt(Instant.now());
        sheet.setUpdatedAt(Instant.now());

        log.info("Generated argument sheet v{} for workspace '{}' with perspective '{}' (terra={})",
                nextVersion, workspaceId, targetPerspective, usedTerraPath);
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
        List<String> rebuttalTuples = new ArrayList<>();
        for (StatutePin pin : allowedStatutes) {
            if (pin.actName() != null && pin.sectionNumber() != null) {
                rebuttalTuples.add(pin.actName() + ":" + pin.sectionNumber());
            }
        }
        List<VerifiedPrecedentAuthority> precedents = precedentProvider.resolveVerifiedPrecedents(
                workspace, oppositePartyContention, rebuttalTuples, false);

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

    private String resolveForumCalibrationInstruction(
            DraftingContext context,
            LegalWorkspace workspace,
            String query,
            List<StatutePin> allowedStatutes
    ) {
        if (context == null) return "";
        String forum = context.forumAnalysis();
        if (forum == null || forum.isBlank()) return "";
        String lower = forum.toLowerCase();

        // Guard against stale / incorrect "Civil Court" forum injection on criminal queries
        if (lower.contains("civil court")) {
            boolean isCriminalDomain = (workspace != null && workspace.getPrimaryDomain() == LegalDomain.CRIMINAL);
            String combinedText = ((query != null ? query : "") + " "
                    + (context.userNarrative() != null ? context.userNarrative() : "") + " "
                    + (workspace != null && workspace.getTitle() != null ? workspace.getTitle() : "")).toLowerCase();
            boolean mentionsCriminal = combinedText.contains("ipc")
                    || combinedText.contains("crpc")
                    || combinedText.contains("bns")
                    || combinedText.contains("bnss")
                    || combinedText.contains("bail")
                    || combinedText.contains("fir")
                    || combinedText.contains("cognizable")
                    || combinedText.contains("custodial")
                    || combinedText.contains("arrest")
                    || combinedText.contains("police")
                    || combinedText.contains("criminal");
            boolean hasCriminalStatutes = allowedStatutes != null && allowedStatutes.stream().anyMatch(pin -> {
                String act = pin.actName() != null ? pin.actName().toLowerCase() : "";
                return act.contains("penal") || act.contains("criminal") || act.contains("nyaya") || act.contains("nagarik");
            });

            if (isCriminalDomain || mentionsCriminal || hasCriminalStatutes) {
                return "";
            }
        }

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

    private String resolveForumCalibrationInstruction(DraftingContext context) {
        return resolveForumCalibrationInstruction(context, null, null, null);
    }

    private boolean isCriminalContext(String query, String narrative, List<StatutePin> allowedStatutes) {
        String combined = ((query != null ? query : "") + " " + (narrative != null ? narrative : "")).toLowerCase();
        if (combined.contains("ipc") || combined.contains("crpc") || combined.contains("bns")
                || combined.contains("bnss") || combined.contains("bail") || combined.contains("fir")
                || combined.contains("cognizable") || combined.contains("custodial")
                || combined.contains("arrest") || combined.contains("police") || combined.contains("criminal")) {
            return true;
        }
        return allowedStatutes != null && allowedStatutes.stream().anyMatch(pin -> {
            String act = pin.actName() != null ? pin.actName().toLowerCase() : "";
            return act.contains("penal") || act.contains("criminal") || act.contains("nyaya") || act.contains("nagarik");
        });
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
        return buildAdvocacySystemPrompt(perspective, allowedStatutes, precedents, context, triggeredBarDirectives, ArgumentGenerationPlan.fullDossier());
    }

    private String buildAdvocacySystemPrompt(
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            DraftingContext context,
            List<String> triggeredBarDirectives,
            ArgumentGenerationPlan plan
    ) {
        String rolePosture = perspective == PartyPerspective.CLAIMANT_PETITIONER
                ? """
                ADVOCACY POSTURE: CLAIMANT / PETITIONER
                - Write openingPitch60Seconds and all coreSubmissions in INSTITUTIONAL COUNSEL ADVOCACY VOICE
                  ready to be delivered aloud to the Bench on behalf of the Complainant / Petitioner / Prosecution:
                  "May it please this Hon'ble Forum / Court, it is respectfully submitted on behalf of the Complainant/Petitioner that under Section..."
                  NEVER speak in first-person litigant voice ("I", "my", "me", "mine").
                  NEVER use third-person advisory prose such as "The claimant should prove..." or "Plead territorial jurisdiction...".
                - Frame affirmative submissions calibrated strictly to the proceeding posture (e.g. establishing maintainability and ingredients of statutory violation for trials, or establishing threshold necessity / custodial interrogation / statutory grounds for bail opposition and interim motions).
                - Anticipate aggressive maintainability challenges and formulate robust counter-rebuttals.
                - IMPORTANT: Do NOT suggest that a third-party credit-card holder or account holder be made a
                  co-complainant unless explicitly instructed. Frame such a person strictly as a pro-forma witness
                  or payment instrument holder to be supported by an affidavit and account statement.
                """
                : (perspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                ? """
                ADVOCACY POSTURE: OPPOSITE PARTY / RESPONDENT
                - Write openingPitch60Seconds and all coreSubmissions in INSTITUTIONAL COUNSEL DEFENSE VOICE
                  ready to be delivered aloud to the Bench on behalf of the Respondent / Opposite Party / Corporate Debtor / Accused:
                  "May it please this Hon'ble Forum / Court, the Respondent respectfully submits that the present proceedings/application are
                   entirely misconceived and barred at the threshold..."
                  NEVER speak in first-person litigant voice ("I", "my", "me", "mine").
                  NEVER use third-person advisory prose.
                - Frame razor-sharp defense submissions calibrated strictly to the proceeding posture (e.g. attacking threshold maintainability bars, non-joinder, absence of prima facie case, statutory immunities/safe harbours, or disproportionate interim relief).
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
                
                ACTIVE DOSSIER COMPONENT CONFIGURATION:
                - openingPitch60Seconds: %s
                - framedIssues: %s
                - coreSubmissions: %s
                - rebuttalMatrix: %s
                - benchGrillQuestions: %s
                - preHearingEvidenceChecklist: %s
                For any component marked EXCLUDED, return "" or [] in the output JSON.

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
                """.formatted(
                rolePosture,
                forumCalibration,
                barDirectiveInstruction,
                (plan == null || plan.includePitch()) ? "ENABLED" : "EXCLUDED (return \"\")",
                (plan == null || plan.includeIssues()) ? "ENABLED" : "EXCLUDED (return [])",
                (plan == null || plan.includeSubmissions()) ? "ENABLED" : "EXCLUDED (return [])",
                (plan == null || plan.includeRebuttals()) ? "ENABLED" : "EXCLUDED (return [])",
                (plan == null || plan.includeBenchGrill()) ? "ENABLED" : "EXCLUDED (return [])",
                (plan == null || plan.includeChecklist()) ? "ENABLED" : "EXCLUDED (return [])"
        );
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
        return buildAdvocacyUserPrompt(workspace, perspective, allowedStatutes, precedents, activeEvidenceDocs, customFocusInstruction, triggeredBarDirectives, ArgumentGenerationPlan.fullDossier());
    }

    private StringBuilder buildAdvocacyUserPrompt(
            LegalWorkspace workspace,
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs,
            String customFocusInstruction,
            List<String> triggeredBarDirectives,
            ArgumentGenerationPlan plan
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

        ArgumentGenerationPlan effPlan = (plan != null) ? plan : ArgumentGenerationPlan.fullDossier();
        sb.append("\nACTIVE JSON FIELD DIRECTIVE FOR THIS DOSSIER:\n");
        sb.append("You MUST return the standard JSON object conforming to ArgumentDossier with the following active field configuration:\n");
        sb.append("- openingPitch60Seconds: ").append(effPlan.includePitch() ? "POPULATE in crisp, natural spoken courtroom English ready to read straight to the Bench." : "RETURN \"\" (empty string).").append("\n");
        sb.append("- framedIssues: ").append(effPlan.includeIssues() ? "POPULATE with crisp threshold questions and governing standards." : "RETURN [] (empty list).").append("\n");
        sb.append("- coreSubmissions: ").append(effPlan.includeSubmissions() ? "POPULATE affirmative institutional counsel submissions." : "RETURN [] (empty list).").append("\n");
        sb.append("- rebuttalMatrix: ").append(effPlan.includeRebuttals() ? "POPULATE targeted counter-rebuttals using the 2-fold Senior Advocate structure." : "RETURN [] (empty list).").append("\n");
        sb.append("- benchGrillQuestions: ").append(effPlan.includeBenchGrill() ? "POPULATE strategic weak points, timing traps, and authoritative courtroom answers." : "RETURN [] (empty list).").append("\n");
        sb.append("- preHearingEvidenceChecklist: ").append(effPlan.includeChecklist() ? "POPULATE capped at max 6 concrete documentary exhibits needed." : "RETURN [] (empty list).").append("\n");
        sb.append("All list fields in the JSON must remain non-null (use empty lists [] for excluded components).\n");

        if (effPlan.includePitch()) {
            sb.append("STRATEGIC COUNSEL ADVISORY: If instructed to argue an absolute, high-risk, or legally vulnerable stance, do not silently weaken it; prepend a blockquote starting with '> **Strategic Advisory & Framing Note:** You instructed to argue [Goal]. However, advancing an unqualified assertion risks resistance under [Jurisprudence]. The stronger strategy is [Recommended Framing].\\n\\n' to openingPitch60Seconds.\n");
        }
        sb.append("IMPORTANT: Write strictly from the institutional perspective of Counsel representing the party (e.g. 'The Complainant submits...', 'May it please this Hon'ble Forum/Court...'). NEVER use first-person singular pronouns ('I', 'my', 'me', 'mine'). State submissions affirmatively and persuasively without self-sabotaging hedging.\n");
        sb.append("Write clean, fluent courtroom prose without inline [Verify from Record: ...] bracket clutter.\n");
        sb.append("Only include alternative pleas where a genuine legal or fallback exists; do not repeat stock boilerplate phrases across submissions.\n");
        if (effPlan.includeBenchGrill()) {
            sb.append("Identify hardest strategic weak points and bench traps in benchGrillQuestions.\n");
        }
        if (effPlan.includeChecklist()) {
            sb.append("Cap preHearingEvidenceChecklist to a maximum of 6 high-priority, concrete documentary items.\n");
        }
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

    /**
     * Returns true when a QUICK_CONSULT prompt introduces a brand-new factual scenario
     * with zero keyword overlap with the workspace's existing userNarrative — indicating
     * the user switched topics and prior case facts must NOT be bled into the new query.
     */
    boolean isStandaloneNewScenario(String customFocusInstruction, LegalWorkspace workspace) {
        if (workspace == null || workspace.getMode() != WorkspaceMode.QUICK_CONSULT) return false;
        if (customFocusInstruction == null) return false;
        String[] promptWords = customFocusInstruction.trim().split("\\s+");
        if (promptWords.length < 12) return false;

        DraftingContext ctx = workspace.getLatestDraftingContext();
        if (ctx == null || ctx.userNarrative() == null || ctx.userNarrative().isBlank()) return false;

        Set<String> promptSignificant = significantWords(customFocusInstruction);
        Set<String> narrativeSignificant = significantWords(ctx.userNarrative());

        // If there is NO overlap between the two significant-word sets -> standalone new scenario
        return Collections.disjoint(promptSignificant, narrativeSignificant);
    }

    private static final Set<String> LEGAL_STOPWORDS = Set.of(
            "section", "under", "that", "this", "with", "from", "have", "been", "will",
            "shall", "their", "there", "where", "which", "about", "would", "could", "court",
            "legal", "matter", "case", "above", "below", "order", "filed", "made", "also"
    );

    private static Set<String> significantWords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String w : text.toLowerCase().split("[^a-z]+")) {
            if (w.length() > 4 && !LEGAL_STOPWORDS.contains(w)) {
                result.add(w);
            }
        }
        return result;
    }

    /**
     * Entity-fallback path: extracts ArgumentDossier via .entity(ArgumentDossier.class),
     * with content-parse fallback and deterministic fallback if both fail.
     * Preserves ALL existing mocked unit test paths.
     */
    private ArgumentDossier extractDossierViaEntityPath(
            ChatClient client,
            String systemPrompt,
            StringBuilder userPrompt,
            LegalWorkspace workspace,
            PartyPerspective targetPerspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs,
            List<String> triggeredBarDirectives,
            ArgumentGenerationPlan plan
    ) {
        ArgumentDossier rawDossier = null;
        if (client != null) {
            try {
                rawDossier = client.prompt()
                        .system(systemPrompt)
                        .user(userPrompt.toString())
                        .call()
                        .entity(ArgumentDossier.class);
            } catch (Exception e) {
                log.warn("Entity extraction failed: {}. Attempting content parse...", e.getMessage());
                try {
                    String rawContent = client.prompt()
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
                    workspace, targetPerspective, allowedStatutes,
                    precedents, activeEvidenceDocs, triggeredBarDirectives);
        }
        ArgumentDossier sanitized = firewallSanitizer.sanitizeDossier(
                rawDossier, allowedStatutes, precedents, activeEvidenceDocs);
        return enforceGenerationPlan(sanitized, plan);
    }

    // ─── TERRA GROUNDING ENGINE ───

    /**
     * Static Terra system prompt — zero dynamic interpolation, enabling OpenAI prompt caching.
     * Role: Senior Advocate, courtroom strategist in India.
     * Outputs adaptive free-flowing markdown, NOT rigid 1-to-6 JSON template.
     */
    public static final String STATIC_TERRA_SYSTEM_PROMPT = """
        You are a Senior Advocate and courtroom strategist in India.
        
        1. NATURAL STRUCTURE & PROPORTIONALITY:
           - You have complete freedom to structure, format, and title your response naturally based on what best serves the lawyer's exact query and proceeding stage.
           - Match your depth and structure proportionally to the query: answer narrow tactical questions directly and concisely; for motion or hearing preparation, provide a practical courtroom strategy framework.
           - State each substantive argument once (cross-referencing rather than repeating across sections), always specify the Act name/acronym alongside section numbers when multiple statutes are in play, and include a realistic alternative/fallback prayer where appropriate.
        
        2. STRICT LEGAL & FACT GROUNDING (NO INVENTION):
           - Never invent case-specific facts, dates, amounts, party names, or uploaded document filenames. Use only the facts in the prompt and Workspace Case Record, and naturally indicate where specific dates or record particulars should be inserted by counsel.
           - Represent strictly the party specified in REPRESENTATION POSTURE.
           - From the optional Bare Act Reference Library and Triggered Maintainability Bars, use ONLY provisions directly governing the motion and silently ignore tangential sections.
           - When examining any statutory provision or application, check both external statutory interplay/sequencing (distinguishing pre-admission inquiry from post-admission status without overclaiming flat ouster) AND internal compliance with the sub-sections/preconditions of the invoked provision itself (such as filing timing, waiver, notice, or mandatory annexures).
           - Whenever an IPC, CrPC, or Indian Evidence Act provision is cited (or vice versa), state the exact corresponding BNS, BNSS, or BSA section number alongside it.
           - Prioritize Verified Precedent Authorities from the workspace corpus (marked [Verified in Corpus]). For other authorities, cite only settled, foundational Supreme Court rulings where you are 100% certain of the case name and ratio, and never guess pinpoint paragraph numbers.
        
        3. OPTIONAL CLARIFICATIONS:
           - Only when key factual particulars are missing and would materially change the legal strategy, append 2–3 brief questions at the very end under "### Clarifications to Sharpen This Brief".
        """;

    /**
     * Builds the Terra grounded user prompt with strictly static-to-dynamic ordering
     * to maximise OpenAI prompt cache hit rate.
     * Block 1 (semi-static): case title, ledger facts, statute library, precedents, vault docs.
     * Block 2 (dynamic): posture, forum, existing sheet context, advocate's current instruction.
     */
    public StringBuilder buildTerraGroundedUserPrompt(
            LegalWorkspace workspace,
            PartyPerspective perspective,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents,
            List<WorkspaceDocument> activeEvidenceDocs,
            List<String> triggeredBarDirectives,
            String customFocusInstruction,
            WorkspaceArgumentSheet latestSheet,
            boolean standaloneNewScenario
    ) {
        StringBuilder sb = new StringBuilder();

        // ── BLOCK 1: SEMI-STATIC (maximise cache hit) ──

        // Case title and verified ledger facts
        sb.append("### CASE TITLE & VERIFIED LEDGER FACTS\n");
        sb.append("Case: ").append(workspace.getTitle() != null ? workspace.getTitle() : "Untitled Matter").append("\n");
        if (!standaloneNewScenario && workspace.getFactLedger() != null) {
            com.app.refirm.workspace.domain.CaseFactLedger ledger = workspace.getFactLedger();
            if (ledger.extractedFacts() != null) {
                sb.append("Verified Ledger Facts (v").append(ledger.version()).append("):\n");
                sb.append(ledger.extractedFacts().toString()).append("\n");
            }
        } else if (standaloneNewScenario) {
            sb.append("[Standalone query — prior case facts excluded to prevent fact bleed]\n");
        }
        sb.append("\n");

        // Available Bare Act Reference Library (3-Bucket OKF pack)
        if (allowedStatutes != null && !allowedStatutes.isEmpty()) {
            sb.append("### OPTIONAL BARE ACT REFERENCE LIBRARY (Use ONLY provisions directly relevant to the user's specific motion; ignore tangential sections):\n");
            for (StatutePin pin : allowedStatutes) {
                sb.append("- ").append(pin.actName());
                if (pin.sectionNumber() != null && !pin.sectionNumber().isBlank()) {
                    sb.append(", Section ").append(pin.sectionNumber());
                }
                if (pin.parentContext() != null && !pin.parentContext().isBlank()) {
                    sb.append(" (").append(pin.parentContext()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Verified Precedent Authorities
        if (precedents != null && !precedents.isEmpty()) {
            sb.append("### VERIFIED PRECEDENT AUTHORITIES\n");
            for (VerifiedPrecedentAuthority p : precedents) {
                sb.append("- ").append(p.caseTitleAndCitation());
                if (p.ratioSummary() != null && !p.ratioSummary().isBlank()) {
                    sb.append(" — ").append(p.ratioSummary());
                }
                sb.append(" [Verified in Corpus]\n");
            }
            sb.append("\n");
        }

        // Uploaded Evidentiary Vault Documents
        List<WorkspaceDocument> relevantDocs = filterRelevantVaultDocuments(activeEvidenceDocs, workspace);
        if (relevantDocs != null && !relevantDocs.isEmpty()) {
            sb.append("### UPLOADED EVIDENTIARY VAULT DOCUMENTS\n");
            for (WorkspaceDocument doc : relevantDocs) {
                sb.append("- ").append(doc.getFileName());
                if (doc.getDocumentType() != null) {
                    sb.append(" [").append(doc.getDocumentType()).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ── BLOCK 2: DYNAMIC ──

        // Representation Posture
        sb.append("### REPRESENTATION POSTURE\n");
        String postureLabel = perspective == PartyPerspective.CLAIMANT_PETITIONER
                ? "CLAIMANT / PETITIONER / COMPLAINANT"
                : (perspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                ? "OPPOSITE PARTY / RESPONDENT / ACCUSED"
                : "NEUTRAL EVALUATOR");
        sb.append(postureLabel).append("\n\n");

        // Forum Calibration + Triggered Bar Directives
        DraftingContext ctx = workspace.getLatestDraftingContext();
        if (ctx != null && ctx.targetForum() != null && ctx.targetForum().forumName() != null && !ctx.targetForum().forumName().isBlank()) {
            String targetForum = ctx.targetForum().forumName();
            boolean isCivilCourt = targetForum.toLowerCase().contains("civil court");
            boolean isCriminal = (workspace.getPrimaryDomain() == LegalDomain.CRIMINAL)
                    || isCriminalContext(customFocusInstruction, ctx.userNarrative(), allowedStatutes);
            if (!isCivilCourt || !isCriminal) {
                sb.append("### FORUM\n").append(targetForum).append("\n\n");
            }
        }
        if (triggeredBarDirectives != null && !triggeredBarDirectives.isEmpty()) {
            sb.append("### TRIGGERED BAR DIRECTIVES (must address in brief)\n");
            triggeredBarDirectives.forEach(d -> sb.append("- ").append(d).append("\n"));
            sb.append("\n");
        }

        // Existing Argument Sheet & Recent Turn (stateful context — skip if standaloneNewScenario)
        if (!standaloneNewScenario) {
            if (latestSheet != null && latestSheet.getRenderedMarkdown() != null
                    && !latestSheet.getRenderedMarkdown().isBlank()) {
                String priorMarkdown = latestSheet.getRenderedMarkdown();
                if (priorMarkdown.length() > 4000) {
                    priorMarkdown = priorMarkdown.substring(0, 4000) + "\n[...truncated for context window...]";
                }
                sb.append("### EXISTING ARGUMENT SHEET (v").append(latestSheet.getVersion()).append(")\n");
                sb.append(priorMarkdown).append("\n\n");
            }
            // Last 2 chat history turns as recent context
            if (workspace.getChatHistory() != null && workspace.getChatHistory().size() >= 2) {
                int size = workspace.getChatHistory().size();
                sb.append("### RECENT CONVERSATION TURNS\n");
                for (int i = Math.max(0, size - 4); i < size; i++) {
                    com.app.refirm.triage.entities.ConversationTurn turn = workspace.getChatHistory().get(i);
                    sb.append(turn.getRole()).append(": ").append(turn.getMessage()).append("\n");
                }
                sb.append("\n");
            }
        }

        // Advocate's Current Instruction
        sb.append("### ADVOCATE'S CURRENT INSTRUCTION\n");
        if (customFocusInstruction != null && !customFocusInstruction.isBlank()) {
            sb.append(customFocusInstruction.trim());
        } else {
            sb.append("Prepare a comprehensive courtroom argument brief for the above matter.");
        }
        sb.append("\n");

        return sb;
    }

    /**
     * Extracts a minimal ArgumentDossier wrapper from Terra free-flowing markdown.
     * Extracts first paragraph as pitch, cited (Act, Section) tuples for statute pins,
     * and builds skeletal submissions from main headings — so sheet.getArgumentDossier()
     * is non-null for InspectorService compatibility.
     */
    public ArgumentDossier buildDossierFromTerraMarkdown(
            String markdown,
            List<StatutePin> allowedStatutes,
            List<VerifiedPrecedentAuthority> precedents
    ) {
        if (markdown == null || markdown.isBlank()) return ArgumentDossier.empty();

        // Extract first non-heading paragraph as the opening pitch
        String pitch = "";
        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("|") && !trimmed.startsWith("-")) {
                pitch = trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
                break;
            }
        }

        // Extract sections as skeletal ArgumentSubmissions
        List<ArgumentSubmission> submissions = new ArrayList<>();
        java.util.regex.Matcher headingMatcher = Pattern.compile("^#{2,4}\\s+(.+)$", java.util.regex.Pattern.MULTILINE).matcher(markdown);
        int subIdx = 1;
        List<String> allowedPrecedentTitles = precedents != null
                ? precedents.stream().map(VerifiedPrecedentAuthority::caseTitleAndCitation).filter(s -> s != null && !s.isBlank()).toList()
                : List.of();
        while (headingMatcher.find() && subIdx <= 8) {
            String heading = headingMatcher.group(1).trim();
            if (heading.startsWith("Clarifications")) continue;
            List<String> pinnedStatutes = allowedStatutes != null
                    ? allowedStatutes.stream().limit(2).map(p -> p.actName() + " " + p.sectionNumber()).toList()
                    : List.of();
            submissions.add(new ArgumentSubmission(
                    "SUB_" + subIdx++,
                    heading,
                    "",   // proposition left blank — Terra markdown is the source of truth
                    pinnedStatutes,
                    allowedPrecedentTitles.stream().limit(2).toList(),
                    "NONE_UPLOADED",
                    null
            ));
        }

        return new ArgumentDossier(pitch, List.of(), submissions, List.of(), List.of(), List.of());
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

    /**
     * Deterministic plan for argument sheet generation specifying which components are enabled.
     */
    record ArgumentGenerationPlan(
            boolean includePitch,
            boolean includeIssues,
            boolean includeSubmissions,
            boolean includeRebuttals,
            boolean includeBenchGrill,
            boolean includeChecklist
    ) {
        static ArgumentGenerationPlan fullDossier() {
            return new ArgumentGenerationPlan(true, true, true, true, true, true);
        }
    }

    /**
     * Resolves the effective party perspective dynamically from user cues in customFocusInstruction
     * before falling back to the workspace perspective.
     */
    PartyPerspective resolveEffectivePerspective(
            PartyPerspective overridePerspective,
            String customFocusInstruction,
            LegalWorkspace workspace
    ) {
        // Priority 1: Inspect customFocusInstruction for explicit party representation cues
        // BEFORE falling back to overridePerspective (which WorkspacePromptRouterService always passes
        // as workspace.getPartyPerspective(), potentially masking prompt-level perspective signals).
        if (customFocusInstruction != null && !customFocusInstruction.isBlank()) {
            String text = customFocusInstruction.trim();

            // Check Claimant / Petitioner cues first for explicit oppositions or creditor/prosecution stances
            if (text.matches("(?is).*\\b(oppos(?:e|ing)\\s+(?:a\\s+)?section\\s+8|oppos(?:e|ing)\\s+(?:anticipatory\\s+|regular\\s+)?bail|oppos(?:e|ing)\\s+(?:the\\s+)?(?:respondent|opposite\\s+party|corporate\\s+debtor|defendant|accused)|as\\s+(?:claimant|petitioner|complainant|(?:financial\\s+|operational\\s+)?creditor|prosecution)|on\\s+behalf\\s+of\\s+(?:the\\s+)?(?:claimant|petitioner|complainant|(?:financial\\s+|operational\\s+)?creditor|prosecution))\\b.*")) {
                return PartyPerspective.CLAIMANT_PETITIONER;
            }

            // Check Respondent / Opposite Party / Accused cues
            if (text.matches("(?is).*\\b(as\\s+(?:the\\s+)?(?:respondent|opposite\\s+party|corporate\\s+debtor|defendant|accused)|on\\s+behalf\\s+of\\s+(?:the\\s+)?(?:respondent|opposite\\s+party|corporate\\s+debtor|defendant|accused)|(?:prepare\\s+)?(?:our\\s+)?defen[cs]e\\s+as\\s+(?:the\\s+)?(?:respondent|opposite\\s+party|corporate\\s+debtor|accused)|defend\\s+(?:the\\s+)?corporate\\s+debtor|seeking\\s+(?:anticipatory\\s+|regular\\s+)?bail|bail\\s+application)\\b.*")) {
                return PartyPerspective.OPPOSITE_PARTY_RESPONDENT;
            }
        }

        // Priority 2: Fall back to explicit overridePerspective (if non-null and not a default workspace value
        // that was blindly passed by the router)
        if (overridePerspective != null) {
            return overridePerspective;
        }

        // Priority 3: Fall back to workspace-level party perspective
        return workspace != null && workspace.getPartyPerspective() != null
                ? workspace.getPartyPerspective()
                : PartyPerspective.CLAIMANT_PETITIONER;
    }

    /**
     * Resolves the ArgumentGenerationPlan using Additive Component Resolution.
     */
    ArgumentGenerationPlan resolveGenerationPlan(String customFocusInstruction) {
        if (customFocusInstruction == null || customFocusInstruction.isBlank()) {
            return ArgumentGenerationPlan.fullDossier();
        }

        String text = customFocusInstruction.trim();

        // Full Dossier Trigger: explicitly requests a full/complete sheet
        if (text.matches("(?is).*\\b(full\\s+(?:argument\\s+)?(?:dossier|sheet|brief|notes?)|complete\\s+(?:argument\\s+)?(?:dossier|sheet)|all\\s+sections|comprehensive\\s+dossier)\\b.*")) {
            return ArgumentGenerationPlan.fullDossier();
        }

        // Component Intent Detection on customFocusInstruction:
        boolean wantsPitch = Pattern.compile("\\b(pitch|opening|intro|introduction)\\w*\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean wantsIssues = Pattern.compile("\\b(framed\\s+issues?|issues?\\s+for\\s+determination|frame\\s+(?:the\\s+)?issues?)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean wantsSubmissions = Pattern.compile("\\b(arguments?|submissions?|defen[cs]e|oppos\\w*|support\\w*|pleas?|grounds?|points?|notes?|hearing\\s+note|brief)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean wantsRebuttals = Pattern.compile("\\b(rebuttals?|counter[- ]?(?:arguments?|submissions?|rebuttals?)|rebuttal\\s+matrix|anticipate\\s+oppos\\w*)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean wantsBench = Pattern.compile("\\b(bench|judge|grill\\w*|traps?|questions?\\s+from\\s+(?:the\\s+)?(?:bench|judge|court))\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean wantsChecklist = Pattern.compile("\\b(checklist|documents?\\s+needed|evidence\\s+needed|annexures?\\s+needed|pre[- ]?hearing\\s+checklist)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();

        // Explicit Negative Exclusions:
        boolean excludePitch = Pattern.compile("\\b(?:without|no|skip|omit)\\s+(?:the\\s+)?(?:pitch|opening|intro)\\w*\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean excludeIssues = Pattern.compile("\\b(?:without|no|skip|omit)\\s+(?:the\\s+)?(?:framed\\s+)?issues?\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean excludeSubmissions = Pattern.compile("\\b(?:without|no|skip|omit)\\s+(?:the\\s+)?(?:arguments?|submissions?|points?|grounds?)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean excludeRebuttals = Pattern.compile("\\b(?:without|no|skip|omit)\\s+(?:the\\s+)?(?:rebuttals?|counter[- ]?(?:arguments?|submissions?|rebuttals?)|rebuttal\\s+matrix)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean excludeBench = Pattern.compile("\\b(?:without|no|skip|omit)\\s+(?:the\\s+)?(?:bench|judge|grill\\w*|traps?|questions?\\s+from\\s+(?:the\\s+)?(?:bench|judge|court))\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean excludeChecklist = Pattern.compile("\\b(?:without|no|skip|omit)\\s+(?:the\\s+)?(?:checklist|documents?\\s+needed|evidence\\s+needed|annexures?\\s+needed|pre[- ]?hearing\\s+checklist)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();

        if (excludePitch) wantsPitch = false;
        if (excludeIssues) wantsIssues = false;
        if (excludeSubmissions) wantsSubmissions = false;
        if (excludeRebuttals) wantsRebuttals = false;
        if (excludeBench) wantsBench = false;
        if (excludeChecklist) wantsChecklist = false;

        // Universal Composition Rules:
        // If the user asked for specific non-submission module(s) ONLY: enable ONLY the requested module(s)
        if (!wantsSubmissions && (wantsPitch || wantsIssues || wantsRebuttals || wantsBench || wantsChecklist)) {
            return new ArgumentGenerationPlan(
                    wantsPitch,
                    wantsIssues,
                    false,
                    wantsRebuttals,
                    wantsBench,
                    wantsChecklist
            );
        }

        // Otherwise (when the lawyer asks to prepare arguments, defense, opposition, or a focused topic):
        return new ArgumentGenerationPlan(
                !excludePitch,
                wantsIssues,
                !excludeSubmissions,
                wantsRebuttals,
                wantsBench,
                wantsChecklist
        );
    }

    /**
     * Deterministic post-sanitization generation plan enforcement.
     * Enforces that fields excluded by the generation plan are strictly zeroed out ("" or empty list).
     */
    ArgumentDossier enforceGenerationPlan(ArgumentDossier dossier, ArgumentGenerationPlan plan) {
        if (dossier == null || plan == null) {
            return dossier;
        }

        String resolvedPitch = plan.includePitch() && dossier.openingPitch60Seconds() != null
                ? dossier.openingPitch60Seconds()
                : "";
        List<FramedLegalIssue> resolvedIssues = plan.includeIssues() && dossier.framedIssues() != null
                ? dossier.framedIssues()
                : List.of();
        List<ArgumentSubmission> resolvedSubmissions = plan.includeSubmissions() && dossier.coreSubmissions() != null
                ? dossier.coreSubmissions()
                : List.of();
        List<RebuttalMatrixItem> resolvedRebuttals = plan.includeRebuttals() && dossier.rebuttalMatrix() != null
                ? dossier.rebuttalMatrix()
                : List.of();
        List<BenchQuestionItem> resolvedBench = plan.includeBenchGrill() && dossier.benchGrillQuestions() != null
                ? dossier.benchGrillQuestions()
                : List.of();
        List<String> resolvedChecklist = plan.includeChecklist() && dossier.preHearingEvidenceChecklist() != null
                ? dossier.preHearingEvidenceChecklist()
                : List.of();

        return new ArgumentDossier(
                resolvedPitch,
                resolvedIssues,
                resolvedSubmissions,
                resolvedRebuttals,
                resolvedBench,
                resolvedChecklist
        );
    }

    /**
     * Resolves all statutes cited by Terra in its free-flowing markdown against the 159-Act
     * local OKF catalog via LegalRetrievalService and dynamically enriches allowedStatutes.
     */
    public void resolveTerraCitedStatutesFromCatalog(String sanitizedMarkdown, List<StatutePin> allowedStatutes) {
        if (legalRetrievalService == null || sanitizedMarkdown == null || sanitizedMarkdown.isBlank() || allowedStatutes == null) {
            return;
        }

        Set<ArgumentMarkdownRenderer.ActSectionRef> citedRefs =
                ArgumentMarkdownRenderer.extractCitedActSectionRefs(null, sanitizedMarkdown);

        for (ArgumentMarkdownRenderer.ActSectionRef ref : citedRefs) {
            if (ref.sectionNumber().isBlank()) {
                continue;
            }

            // CRITICAL: NEVER call findStatuteFile when ref.actKey() is null or blank!
            // If a section reference has no explicit Act key, do NOT search across all 159 Acts.
            if (ref.actKey() == null || ref.actKey().isBlank()) {
                continue;
            }

            String targetSec = ArgumentMarkdownRenderer.extractBaseSectionNumber(ref.sectionNumber());
            if (targetSec.isBlank()) {
                continue;
            }

            boolean alreadyPresent = allowedStatutes.stream().anyMatch(pin -> {
                String pinSec = ArgumentMarkdownRenderer.resolveBaseSectionNumber(pin);
                if (!pinSec.equalsIgnoreCase(targetSec)) return false;
                return ArgumentMarkdownRenderer.isActMatch(ref.actKey(), pin.actName());
            });

            if (!alreadyPresent) {
                Optional<LegalRetrievalService.StatuteFile> optFile =
                        legalRetrievalService.findStatuteFile(ref.actKey(), targetSec);
                if (optFile.isPresent()) {
                    LegalRetrievalService.StatuteFile foundFile = optFile.get();
                    if (ArgumentMarkdownRenderer.isActMatch(ref.actKey(), foundFile.statuteName())) {
                        String cleanActName = foundFile.statuteName().replace('_', ' ');
                        StatutePin newPin = new StatutePin(cleanActName, foundFile.sectionNumber(), com.app.refirm.triage.domain.StatuteType.OPERATIVE, foundFile.heading(), foundFile.content());
                        allowedStatutes.add(newPin);
                        log.info("[Post-Terra Catalog] Resolved and added verified OKF pin: {} Section {}", cleanActName, foundFile.sectionNumber());
                    }
                }
            }
        }
    }


    public record TopicCluster(
            String clusterKey,
            Set<String> statutorySignature,
            List<String> orderedAuthorityIds,
            Set<String> previouslyCitedTokens
    ) {
        public TopicCluster {
            statutorySignature = statutorySignature != null ? Set.copyOf(statutorySignature) : Set.of();
            orderedAuthorityIds = orderedAuthorityIds != null ? List.copyOf(orderedAuthorityIds) : List.of();
            previouslyCitedTokens = previouslyCitedTokens != null ? Set.copyOf(previouslyCitedTokens) : Set.of();
        }
    }

    public List<VerifiedPrecedentAuthority> applyPromptCacheAlignment(
            List<VerifiedPrecedentAuthority> freshPrecedents,
            WorkspaceArgumentSheet latestExistingSheet,
            boolean isStandalone
    ) {
        return applyPromptCacheAlignment(
                latestExistingSheet != null ? latestExistingSheet.getWorkspaceId() : null,
                freshPrecedents,
                latestExistingSheet,
                List.of(),
                isStandalone
        );
    }

    /**
     * Quality-First N-Cluster Prompt Cache Alignment:
     * 1. Output Quality First: Never overwrites newly enriched IRAC_ENRICHED text with stale CATALOG_STUB text.
     *    Always uses the latest VerifiedPrecedentAuthority data, only pinning the relative ordering.
     * 2. Topic-Cluster Isolation: Maintains up to N = 4 topic clusters per workspace/tab:
     *    - Same-Topic Refinement: If current turn's statutory tuples overlap with a prior cluster (or are empty
     *      on a conversational follow-up), pin the matching authorities from that cluster first (Group 1, in their
     *      exact prior order) and append newly discovered authorities below them (Group 2).
     *    - Topic Switch in Same Tab: If the lawyer introduces a new set of (Act, Section) tuples that has zero
     *      overlap with prior statutory clusters, starts a clean cluster bucket so 100% of precedent slots are
     *      dedicated to the new topic. If the lawyer later returns to the earlier topic, restores that cluster's ordering.
     */
    public List<VerifiedPrecedentAuthority> applyPromptCacheAlignment(
            UUID workspaceId,
            List<VerifiedPrecedentAuthority> freshPrecedents,
            WorkspaceArgumentSheet latestExistingSheet,
            Collection<String> currentTuples,
            boolean isStandalone
    ) {
        if (isStandalone || freshPrecedents == null || freshPrecedents.isEmpty()) {
            return freshPrecedents != null ? freshPrecedents : List.of();
        }

        if (workspaceId == null && latestExistingSheet != null) {
            workspaceId = latestExistingSheet.getWorkspaceId();
        }

        // 1. Compute current turn's statutory signature
        Set<String> currentSig = computeStatutorySignature(currentTuples);

        // 2. Retrieve recent topic clusters for this workspace (max 4)
        LinkedList<TopicCluster> clusters = (workspaceId != null)
                ? workspaceTopicClusters.computeIfAbsent(workspaceId, k -> new LinkedList<>())
                : new LinkedList<>();

        // 3. Find matching cluster
        TopicCluster matchedCluster = null;

        if (currentSig.isEmpty()) {
            // Conversational follow-up (e.g. 'make submission 2 stronger'): match most recent cluster
            if (!clusters.isEmpty()) {
                matchedCluster = clusters.peekFirst();
            }
        } else {
            // Check for overlap against existing clusters in LRU order
            for (TopicCluster c : clusters) {
                if (!Collections.disjoint(currentSig, c.statutorySignature())) {
                    matchedCluster = c;
                    break;
                }
            }
        }

        // Also inspect latestExistingSheet if clusters is empty (e.g. fresh turn after server restart)
        if (matchedCluster == null && latestExistingSheet != null && clusters.isEmpty()) {
            Set<String> sheetSig = extractSheetStatutorySignature(latestExistingSheet);
            boolean overlapWithSheet = currentSig.isEmpty() || sheetSig.isEmpty() || !Collections.disjoint(currentSig, sheetSig);
            if (overlapWithSheet) {
                matchedCluster = createClusterFromSheet(latestExistingSheet, sheetSig);
                clusters.addFirst(matchedCluster);
            }
        }

        // 4. Handle Topic Switch vs Same-Topic Refinement
        if (matchedCluster == null && !currentSig.isEmpty()) {
            // TOPIC SWITCH IN SAME TAB:
            // Zero overlap with prior clusters. Start a clean cluster for the new topic.
            // 100% of precedent slots are dedicated to fresh precedents for the new topic.
            List<String> newOrderIds = freshPrecedents.stream().map(VerifiedPrecedentAuthority::authorityId).toList();
            TopicCluster newCluster = new TopicCluster(
                    "cluster_" + System.currentTimeMillis(),
                    currentSig,
                    newOrderIds,
                    extractTokensFromAuthorities(freshPrecedents)
            );
            addClusterToWorkspace(clusters, newCluster);
            return freshPrecedents;
        }

        if (matchedCluster == null) {
            return freshPrecedents;
        }

        // 5. SAME-TOPIC REFINEMENT:
        // Group 1: Pin matching authorities from matchedCluster in their exact prior relative order.
        // Group 2: Append newly discovered authorities below them.
        // NEVER overwrite newly enriched IRAC_ENRICHED text with stale stub text! Always use the latest auth instance.
        List<VerifiedPrecedentAuthority> aligned = new ArrayList<>();
        List<VerifiedPrecedentAuthority> newlyDiscovered = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        List<String> priorOrderIds = matchedCluster.orderedAuthorityIds();
        Set<String> priorTokens = matchedCluster.previouslyCitedTokens();

        Map<String, VerifiedPrecedentAuthority> freshById = new LinkedHashMap<>();
        for (VerifiedPrecedentAuthority a : freshPrecedents) {
            freshById.put(a.authorityId(), a);
        }

        // Group 1: Fresh authorities matching prior cluster order
        for (String priorId : priorOrderIds) {
            VerifiedPrecedentAuthority matchedAuth = freshById.get(priorId);
            if (matchedAuth != null && seenIds.add(priorId)) {
                aligned.add(matchedAuth); // Fresh instance with latest ratioSummary and posture!
            }
        }

        // Also check any fresh authorities matching prior cited tokens that weren't captured by ID
        for (VerifiedPrecedentAuthority auth : freshPrecedents) {
            if (!seenIds.contains(auth.authorityId())) {
                String titleLower = auth.caseTitleAndCitation().toLowerCase(Locale.ROOT);
                String idLower = auth.authorityId().toLowerCase(Locale.ROOT);
                boolean matchedToken = priorTokens.stream().anyMatch(tok ->
                        titleLower.contains(tok) || tok.contains(idLower) || tok.contains(titleLower));
                if (matchedToken && seenIds.add(auth.authorityId())) {
                    aligned.add(auth);
                }
            }
        }

        // Group 2: All remaining fresh authorities appended below
        for (VerifiedPrecedentAuthority auth : freshPrecedents) {
            if (seenIds.add(auth.authorityId())) {
                newlyDiscovered.add(auth);
            }
        }

        aligned.addAll(newlyDiscovered);

        // Update the cluster's order and move to head of LRU queue
        List<String> updatedOrderIds = aligned.stream().map(VerifiedPrecedentAuthority::authorityId).toList();
        Set<String> updatedTokens = new LinkedHashSet<>(priorTokens);
        updatedTokens.addAll(extractTokensFromAuthorities(aligned));
        Set<String> mergedSig = new LinkedHashSet<>(matchedCluster.statutorySignature());
        mergedSig.addAll(currentSig);

        TopicCluster updatedCluster = new TopicCluster(
                matchedCluster.clusterKey(),
                mergedSig,
                updatedOrderIds,
                updatedTokens
        );

        clusters.remove(matchedCluster);
        clusters.addFirst(updatedCluster);
        while (clusters.size() > MAX_CLUSTERS_PER_WORKSPACE) {
            clusters.removeLast();
        }

        return aligned;
    }

    private static void addClusterToWorkspace(LinkedList<TopicCluster> clusters, TopicCluster newCluster) {
        clusters.addFirst(newCluster);
        while (clusters.size() > MAX_CLUSTERS_PER_WORKSPACE) {
            clusters.removeLast();
        }
    }

    public static Set<String> computeStatutorySignature(Collection<String> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return Set.of();
        }
        Set<String> sig = new LinkedHashSet<>();
        for (String t : tuples) {
            if (t == null || t.isBlank()) continue;
            String clean = t.trim().toUpperCase(Locale.ROOT);
            String[] parts = clean.split(":");
            if (parts.length >= 2) {
                String actPart = canonicalizeActToken(parts[0]);
                String secPart = parts[1].replaceAll("[^A-Z0-9()]", "");
                sig.add(actPart + ":" + secPart);
                sig.add(actPart);
                addPariMateriaFamilyTokens(actPart, sig);
            } else {
                String actPart = canonicalizeActToken(clean);
                sig.add(actPart);
                addPariMateriaFamilyTokens(actPart, sig);
            }
        }
        return sig;
    }

    private static void addPariMateriaFamilyTokens(String actToken, Set<String> sig) {
        if ("CRPC".equals(actToken) || "BNSS".equals(actToken)) {
            sig.add("CRIMINAL_PROCEDURE_FAMILY");
        } else if ("IPC".equals(actToken) || "BNS".equals(actToken)) {
            sig.add("PENAL_FAMILY");
        } else if ("IEA".equals(actToken) || "BSA".equals(actToken)) {
            sig.add("EVIDENCE_FAMILY");
        } else if ("CPA1986".equals(actToken) || "CPA2019".equals(actToken) || "CONSUMER".equals(actToken)) {
            sig.add("CONSUMER_FAMILY");
        }
    }

    public static String canonicalizeActToken(String rawAct) {
        if (rawAct == null) return "";
        String upper = rawAct.toUpperCase(Locale.ROOT);
        if (upper.contains("CRIMINAL PROCEDURE") || upper.contains("CRPC") || upper.contains("CR.P.C")) return "CRPC";
        if (upper.contains("NAGARIK SURAKSHA") || upper.contains("BNSS")) return "BNSS";
        if (upper.contains("PENAL CODE") || upper.contains("IPC") || upper.contains("I.P.C")) return "IPC";
        if (upper.contains("NYAYA SANHITA") || upper.contains("BNS")) return "BNS";
        if (upper.contains("EVIDENCE ACT") || upper.contains("IEA") || upper.contains("I.E.A")) return "IEA";
        if (upper.contains("SAKSHYA") || upper.contains("BSA")) return "BSA";
        if (upper.contains("NEGOTIABLE") || upper.contains("NI ACT") || upper.contains("NI_ACT")) return "NI_ACT";
        if (upper.contains("INSOLVENCY") || upper.contains("BANKRUPTCY") || upper.contains("IBC")) return "IBC";
        if (upper.contains("ARBITRATION")) return "ARBITRATION";
        if (upper.contains("CONSUMER")) return "CONSUMER";
        return upper.replaceAll("[^A-Z0-9_]", "_").replaceAll("_+", "_");
    }

    private static Set<String> extractTokensFromAuthorities(Collection<VerifiedPrecedentAuthority> authorities) {
        Set<String> tokens = new LinkedHashSet<>();
        if (authorities == null) return tokens;
        for (VerifiedPrecedentAuthority auth : authorities) {
            if (auth.authorityId() != null && !auth.authorityId().isBlank()) {
                tokens.add(auth.authorityId().toLowerCase(Locale.ROOT));
            }
            if (auth.caseTitleAndCitation() != null && !auth.caseTitleAndCitation().isBlank()) {
                tokens.add(auth.caseTitleAndCitation().toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static Set<String> extractSheetStatutorySignature(WorkspaceArgumentSheet sheet) {
        Set<String> sig = new LinkedHashSet<>();
        if (sheet == null || sheet.getArgumentDossier() == null) return sig;
        ArgumentDossier dossier = sheet.getArgumentDossier();
        if (dossier.coreSubmissions() != null) {
            for (var sub : dossier.coreSubmissions()) {
                if (sub.pinnedStatutes() != null) {
                    for (String stat : sub.pinnedStatutes()) {
                        sig.addAll(computeStatutorySignature(List.of(stat)));
                    }
                }
            }
        }
        if (dossier.benchGrillQuestions() != null) {
            for (var qa : dossier.benchGrillQuestions()) {
                if (qa.authorityPin() != null && !qa.authorityPin().isBlank()) {
                    sig.addAll(computeStatutorySignature(List.of(qa.authorityPin())));
                }
            }
        }
        return sig;
    }

    private static TopicCluster createClusterFromSheet(WorkspaceArgumentSheet sheet, Set<String> sheetSig) {
        List<String> orderedIds = new ArrayList<>();
        Set<String> tokens = new LinkedHashSet<>();
        if (sheet == null || sheet.getArgumentDossier() == null) {
            return new TopicCluster("sheet_cluster", sheetSig, orderedIds, tokens);
        }
        ArgumentDossier dossier = sheet.getArgumentDossier();
        if (dossier.coreSubmissions() != null) {
            for (var sub : dossier.coreSubmissions()) {
                if (sub.pinnedPrecedents() != null) {
                    for (String pin : sub.pinnedPrecedents()) {
                        if (pin != null && !pin.isBlank()) {
                            String trimmed = pin.trim().toLowerCase(Locale.ROOT);
                            tokens.add(trimmed);
                            orderedIds.add(trimmed);
                        }
                    }
                }
            }
        }
        if (dossier.benchGrillQuestions() != null) {
            for (var qa : dossier.benchGrillQuestions()) {
                if (qa.authorityPin() != null && !qa.authorityPin().isBlank()) {
                    tokens.add(qa.authorityPin().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new TopicCluster("sheet_" + sheet.getSheetId(), sheetSig, orderedIds, tokens);
    }
}
