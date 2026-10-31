package com.app.refirm.inspector.service;

import com.app.refirm.arguments.controller.dto.ArgumentSheetResponse;
import com.app.refirm.arguments.domain.ArgumentDossier;
import com.app.refirm.arguments.domain.ArgumentSubmission;
import com.app.refirm.arguments.domain.BenchQuestionItem;
import com.app.refirm.arguments.domain.RebuttalMatrixItem;
import com.app.refirm.arguments.domain.VerifiedPrecedentAuthority;
import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.arguments.service.ArgumentMarkdownRenderer;
import com.app.refirm.arguments.service.PrecedentAuthorityProvider;
import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.dto.InspectorDTOs.*;
import com.app.refirm.templates.controller.dto.WorkspaceDraftDTO;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateParticular;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.StructuredDocumentSummary;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceSavedAuthority;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.repository.WorkspaceSavedAuthorityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Unified service powering View 2B: 4 Right-Hand Inspector Pills (Particulars | Cited | Statutes | Relevant).
 * Also powers citation mutation (USE_IN_DRAFT, REPLACE, REMOVE) for both Drafts and Courtroom Argument Sheets.
 */
@Service
public class InspectorService {

    private static final Logger log = LoggerFactory.getLogger(InspectorService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDraftRepository draftRepository;
    private final WorkspaceArgumentSheetRepository argumentSheetRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final WorkspaceSavedAuthorityRepository savedAuthorityRepository;
    private final TemplateRegistryService registryService;
    private final PrecedentAuthorityProvider precedentProvider;
    private final AuthorityDrawerService drawerService;
    private final CorpusPrecedentRegistryService corpusRegistry;
    private final ArgumentMarkdownRenderer markdownRenderer;

    @Autowired
    public InspectorService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDraftRepository draftRepository,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            TemplateRegistryService registryService,
            PrecedentAuthorityProvider precedentProvider,
            AuthorityDrawerService drawerService,
            @Autowired(required = false) CorpusPrecedentRegistryService corpusRegistry,
            @Autowired(required = false) ArgumentMarkdownRenderer markdownRenderer
    ) {
        this.workspaceRepository = workspaceRepository;
        this.draftRepository = draftRepository;
        this.argumentSheetRepository = argumentSheetRepository;
        this.documentRepository = documentRepository;
        this.savedAuthorityRepository = savedAuthorityRepository;
        this.registryService = registryService;
        this.precedentProvider = precedentProvider;
        this.drawerService = drawerService;
        this.corpusRegistry = corpusRegistry;
        this.markdownRenderer = markdownRenderer != null ? markdownRenderer : new ArgumentMarkdownRenderer();
    }

    public InspectorService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDraftRepository draftRepository,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            TemplateRegistryService registryService,
            PrecedentAuthorityProvider precedentProvider,
            AuthorityDrawerService drawerService
    ) {
        this(workspaceRepository, draftRepository, argumentSheetRepository, documentRepository,
                savedAuthorityRepository, registryService, precedentProvider, drawerService, null, null);
    }

    @Transactional(readOnly = true)
    public RightPaneInspectorResponse getInspector(
            UUID workspaceId,
            String clerkUserId,
            String targetType,
            UUID targetId
    ) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        CaseFactLedger ledger = workspace.getFactLedger() != null ? workspace.getFactLedger() : CaseFactLedger.empty();

        List<ParticularFieldInspectorItem> particularsTab = new ArrayList<>();
        List<JudgmentAuthorityCard> citedTab = new ArrayList<>();
        List<JudgmentAuthorityCard> relevantTab = new ArrayList<>();
        List<StatuteProvisionInspectorItem> statuteProvisions = new ArrayList<>();
        Map<String, Set<String>> actToSections = new LinkedHashMap<>();
        int missingCount = 0;

        String renderedMarkdown = "";
        ArgumentDossier dossier = null;

        if ("DRAFT".equalsIgnoreCase(targetType) && targetId != null) {
            WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(targetId, workspaceId, clerkUserId)
                    .orElse(null);

            if (draft != null) {
                renderedMarkdown = draft.getRenderedMarkdown() != null ? draft.getRenderedMarkdown() : "";
                missingCount = draft.getMissingParticularKeys() != null ? draft.getMissingParticularKeys().size() : 0;
                TemplateManifest template = null;
                try {
                    template = registryService.getTemplateOrThrow(draft.getTemplateId());
                } catch (Exception ignored) {}

                // 1. Build Particulars Tab
                if (template != null && template.particulars() != null) {
                    for (TemplateParticular p : template.particulars()) {
                        String key = p.key();
                        String label = p.label() != null ? p.label() : key;
                        String val = draft.getFilledParticulars() != null ? draft.getFilledParticulars().getOrDefault(key, "") : "";
                        boolean isLocked = ledger.isLocked(key) || (p.ledgerSourceKey() != null && ledger.isLocked(p.ledgerSourceKey()));

                        String group = "BODY";
                        if (isLocked || p.ledgerSourceKey() != null) {
                            group = "LOCKED_LEDGER";
                        } else if (key.toLowerCase().contains("verification") || key.toLowerCase().contains("deponent") || key.toLowerCase().contains("place")) {
                            group = "VERIFICATION";
                        }

                        particularsTab.add(new ParticularFieldInspectorItem(
                                key,
                                label,
                                group,
                                val,
                                isLocked,
                                p.required()
                        ));
                    }
                }

                // 2. Build Statutes Tab
                if (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null && !workspace.getLatestDraftingContext().statutePack().isEmpty()) {
                    Set<ArgumentMarkdownRenderer.ActSectionRef> citedRefs = ArgumentMarkdownRenderer.extractCitedActSectionRefs(null, renderedMarkdown);
                    List<StatutePin> activePack = ArgumentMarkdownRenderer.filterToActivelyCitedStatutes(
                            workspace.getLatestDraftingContext().statutePack(),
                            citedRefs
                    );
                    populateStatutesFromPack(activePack, Collections.emptyMap(), statuteProvisions, actToSections);
                } else if (template != null && template.mandatoryStatutoryIngredients() != null) {
                    String actTitle = humanizeActTitle(template.legalBasis() != null ? template.legalBasis() : "The Consumer Protection Act, 2019");
                    for (String statStr : template.mandatoryStatutoryIngredients()) {
                        String baseSec = extractBaseSection(statStr);
                        String header = humanizeSectionNumber(statStr);
                        statuteProvisions.add(new StatuteProvisionInspectorItem(
                                "stat-sec" + (baseSec.isBlank() ? statStr.replaceAll("\\s+", "_") : baseSec),
                                header,
                                actTitle,
                                "Statutory provisions governing " + header
                        ));
                        actToSections.computeIfAbsent(actTitle, k -> new LinkedHashSet<>()).add(header);
                    }
                }
            }
        } else if ("ARGUMENT".equalsIgnoreCase(targetType) && targetId != null) {
            WorkspaceArgumentSheet sheet = argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(targetId, workspaceId, clerkUserId)
                    .orElse(null);

            if (sheet != null) {
                renderedMarkdown = sheet.getRenderedMarkdown() != null ? sheet.getRenderedMarkdown() : "";
                dossier = sheet.getArgumentDossier();

                if (dossier != null) {
                    Map<String, Set<String>> subClausesBySection = extractSubClausesFromArgumentDossier(dossier);
                    if (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null && !workspace.getLatestDraftingContext().statutePack().isEmpty()) {
                        Set<ArgumentMarkdownRenderer.ActSectionRef> citedRefs = ArgumentMarkdownRenderer.extractCitedActSectionRefs(dossier, renderedMarkdown);
                        List<StatutePin> activePack = ArgumentMarkdownRenderer.filterToActivelyCitedStatutes(
                                workspace.getLatestDraftingContext().statutePack(),
                                citedRefs
                        );
                        populateStatutesFromPack(activePack, subClausesBySection, statuteProvisions, actToSections);
                    }
                }
            }
        }

        // Fallback default statutes if empty
        if (statuteProvisions.isEmpty()) {
            String domain = (workspace.getPrimaryDomain() != null && workspace.getPrimaryDomain() != com.app.refirm.triage.domain.LegalDomain.UNKNOWN)
                    ? workspace.getPrimaryDomain().name()
                    : "The Consumer Protection Act, 2019";
            String actTitle = humanizeActTitle(domain);
            statuteProvisions.add(new StatuteProvisionInspectorItem(
                    "stat-sec-primary",
                    "Governing Statutory Provisions",
                    actTitle,
                    "Statutory anchors derived from the governing legislative framework."
            ));
            actToSections.computeIfAbsent(actTitle, k -> new LinkedHashSet<>())
                    .add("Governing Statutory Provisions");
        }

        List<ActSummaryItem> actsSummary = actToSections.entrySet().stream()
                .map(e -> new ActSummaryItem(e.getKey(), new ArrayList<>(e.getValue())))
                .toList();

        // 3. Universal Cited vs Relevant Partitioning across both Drafts and Argument Sheets
        partitionCitedAndRelevantAuthorities(workspace, targetType, renderedMarkdown, dossier, clerkUserId, citedTab, relevantTab);

        CountsHeader countsHeader = new CountsHeader(
                citedTab.size(),
                statuteProvisions.size(),
                missingCount
        );

        return new RightPaneInspectorResponse(
                countsHeader,
                particularsTab,
                citedTab,
                new StatutesTabDTO(statuteProvisions, actsSummary),
                relevantTab
        );
    }

    private record CandidateAuthority(
            String authorityId,
            String caseTitle,
            String courtName,
            String judgmentDate,
            String primaryCitation,
            List<String> equivalentCitations,
            String sourceType,
            UUID sourceDocId,
            String summary,
            List<String> keyPropositions
    ) {}

    private void partitionCitedAndRelevantAuthorities(
            LegalWorkspace workspace,
            String targetType,
            String renderedMarkdown,
            ArgumentDossier argumentDossier,
            String clerkUserId,
            List<JudgmentAuthorityCard> citedTab,
            List<JudgmentAuthorityCard> relevantTab
    ) {
        UUID workspaceId = workspace.getWorkspaceId();
        Set<String> seenAuthorityIds = new HashSet<>();
        List<CandidateAuthority> candidates = new ArrayList<>();

        // 1. Uploaded JUDGMENT_PRECEDENT documents in workspace vault
        List<WorkspaceDocument> vaultDocs = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId);
        if (vaultDocs != null) {
            for (WorkspaceDocument doc : vaultDocs) {
                if (doc.getDocumentType() == DocumentType.JUDGMENT_PRECEDENT) {
                    String authId = doc.getDocumentId().toString();
                    if (seenAuthorityIds.add(authId)) {
                        StructuredDocumentSummary summary = doc.getStructuredSummary();
                        String title = summary != null && summary.title() != null && !summary.title().isBlank()
                                ? summary.title()
                                : doc.getFileName();
                        String gist = summary != null && summary.executiveGist() != null
                                ? summary.executiveGist()
                                : "Uploaded judgment in workspace vault.";
                        List<String> keyProps = summary != null && summary.keyPartiesAndRoles() != null
                                ? summary.keyPartiesAndRoles()
                                : List.of("Vault Document: " + doc.getFileName());

                        candidates.add(new CandidateAuthority(
                                authId,
                                title,
                                "Court record annexed in workspace vault",
                                "",
                                doc.getFileName(),
                                List.of(doc.getFileName()),
                                "WORKSPACE_UPLOADED_JUDGMENT",
                                doc.getDocumentId(),
                                gist,
                                keyProps
                        ));
                    }
                }
            }
        }

        // 2. Domain-matched verified precedents from CorpusPrecedentRegistryService
        List<CorpusPrecedentEntry> corpusEntries = corpusRegistry != null
                ? corpusRegistry.findRelevantForWorkspace(workspace)
                : Collections.emptyList();

        for (CorpusPrecedentEntry entry : corpusEntries) {
            if (seenAuthorityIds.add(entry.authorityId())) {
                candidates.add(new CandidateAuthority(
                        entry.authorityId(),
                        entry.caseTitle(),
                        entry.courtName(),
                        entry.judgmentDate(),
                        entry.primaryCitation(),
                        entry.equivalentCitations(),
                        "VERIFIED_CORPUS",
                        null,
                        entry.summary(),
                        entry.keyPropositions()
                ));
            }
        }

        // 3. Any saved authorities in matter that were not in candidates
        List<WorkspaceSavedAuthority> savedList = savedAuthorityRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtDesc(workspaceId, clerkUserId);
        if (savedList != null) {
            for (WorkspaceSavedAuthority sa : savedList) {
                if (seenAuthorityIds.add(sa.getAuthorityId())) {
                    Optional<CorpusPrecedentEntry> entryOpt = corpusRegistry != null ? corpusRegistry.findById(sa.getAuthorityId()) : Optional.empty();
                    if (entryOpt.isPresent()) {
                        CorpusPrecedentEntry entry = entryOpt.get();
                        candidates.add(new CandidateAuthority(
                                entry.authorityId(),
                                entry.caseTitle(),
                                entry.courtName(),
                                entry.judgmentDate(),
                                entry.primaryCitation(),
                                entry.equivalentCitations(),
                                "VERIFIED_CORPUS",
                                null,
                                entry.summary(),
                                entry.keyPropositions()
                        ));
                    }
                }
            }
        }

        // 3B. Any catalog precedents cited in renderedMarkdown or argumentDossier
        if (corpusRegistry != null) {
            List<String> pinnedPrecs = new ArrayList<>();
            if (argumentDossier != null) {
                if (argumentDossier.coreSubmissions() != null) {
                    for (ArgumentSubmission sub : argumentDossier.coreSubmissions()) {
                        if (sub.pinnedPrecedents() != null) pinnedPrecs.addAll(sub.pinnedPrecedents());
                        if (sub.legalProposition() != null) pinnedPrecs.add(sub.legalProposition());
                    }
                }
                if (argumentDossier.rebuttalMatrix() != null) {
                    for (RebuttalMatrixItem reb : argumentDossier.rebuttalMatrix()) {
                        if (reb.statutoryOrEvidentiaryAnchor() != null) pinnedPrecs.add(reb.statutoryOrEvidentiaryAnchor());
                        if (reb.ourCounterRebuttal() != null) pinnedPrecs.add(reb.ourCounterRebuttal());
                    }
                }
                if (argumentDossier.benchGrillQuestions() != null) {
                    for (BenchQuestionItem qa : argumentDossier.benchGrillQuestions()) {
                        if (qa.authorityPin() != null) pinnedPrecs.add(qa.authorityPin());
                    }
                }
            }
            for (CorpusPrecedentEntry entry : corpusRegistry.getAllPrecedents()) {
                if (!seenAuthorityIds.contains(entry.authorityId())) {
                    if (isAuthorityCitedInText(entry, renderedMarkdown, pinnedPrecs)) {
                        seenAuthorityIds.add(entry.authorityId());
                        candidates.add(new CandidateAuthority(
                                entry.authorityId(),
                                entry.caseTitle(),
                                entry.courtName(),
                                entry.judgmentDate(),
                                entry.primaryCitation(),
                                entry.equivalentCitations(),
                                "VERIFIED_CORPUS",
                                null,
                                entry.summary(),
                                entry.keyPropositions()
                        ));
                    }
                }
            }
        }

        // 4. Partition into Cited vs Relevant
        int paragraphIndex = 1;
        for (CandidateAuthority cand : candidates) {
            boolean isSaved = savedAuthorityRepository.existsByWorkspaceIdAndAuthorityIdAndClerkUserId(workspaceId, cand.authorityId, clerkUserId);

            CitationMatch match = findCitationInDocument(cand, renderedMarkdown, argumentDossier);

            if (match.isCited()) {
                citedTab.add(new JudgmentAuthorityCard(
                        cand.authorityId,
                        cand.caseTitle,
                        cand.courtName,
                        cand.judgmentDate,
                        cand.primaryCitation,
                        cand.equivalentCitations,
                        cand.sourceType,
                        cand.sourceDocId,
                        match.snippet(),
                        paragraphIndex++,
                        cand.summary,
                        cand.keyPropositions,
                        isSaved
                ));
            } else {
                relevantTab.add(new JudgmentAuthorityCard(
                        cand.authorityId,
                        cand.caseTitle,
                        cand.courtName,
                        cand.judgmentDate,
                        cand.primaryCitation,
                        cand.equivalentCitations,
                        cand.sourceType,
                        cand.sourceDocId,
                        null,
                        null,
                        cand.summary,
                        cand.keyPropositions,
                        isSaved
                ));
            }
        }
    }

    private record CitationMatch(boolean isCited, String snippet) {}

    public static String normalizeCitationText(String text) {
        if (text == null || text.isBlank()) return "";
        return text.toLowerCase()
                .replaceAll("(?i)\\b(?:versus|vs\\.?)\\b", "v.")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean isAuthorityCitedInText(
            String caseTitle,
            String primaryCitation,
            List<String> equivalentCitations,
            String authorityId,
            String fullText,
            List<String> pinnedPrecedentStrings
    ) {
        String normFullText = normalizeCitationText(fullText);
        List<String> normPinned = (pinnedPrecedentStrings != null)
                ? pinnedPrecedentStrings.stream().map(InspectorService::normalizeCitationText).filter(s -> !s.isBlank()).toList()
                : List.of();

        java.util.function.Predicate<String> containsNeedle = needle -> {
            if (needle == null || needle.isBlank()) return false;
            if (normFullText.contains(needle)) return true;
            for (String p : normPinned) {
                if (p.contains(needle)) return true;
            }
            return false;
        };

        // 1. Check primary citation (e.g. "2018 scc online del 12215" or "2022 insc 804")
        String normPrimary = normalizeCitationText(primaryCitation);
        if (!normPrimary.isBlank()) {
            if (containsNeedle.test(normPrimary)) return true;
            String strippedPrimary = normPrimary.replaceAll("[()]", "").trim();
            if (!strippedPrimary.isBlank() && containsNeedle.test(strippedPrimary)) return true;
        }

        // 2. Check equivalent citations
        if (equivalentCitations != null) {
            for (String eq : equivalentCitations) {
                String normEq = normalizeCitationText(eq);
                if (!normEq.isBlank()) {
                    if (containsNeedle.test(normEq)) return true;
                    String strippedEq = normEq.replaceAll("[()]", "").trim();
                    if (!strippedEq.isBlank() && containsNeedle.test(strippedEq)) return true;
                }
            }
        }

        // 3. Check primary party name prefix before " v." (if >= 5 characters)
        String normTitle = normalizeCitationText(caseTitle);
        if (!normTitle.isBlank()) {
            if (containsNeedle.test(normTitle)) {
                return true;
            }
            Matcher m = Pattern.compile("(?i)^(.*?)\\s+v\\.\\s+").matcher(normTitle);
            if (m.find()) {
                String prefix = m.group(1).trim();
                if (prefix.length() >= 5 && containsNeedle.test(prefix)) {
                    return true;
                }
                String cleanPrefix = prefix.replaceAll("(?i)\\s+(?:sas|ltd\\.?|pvt\\.?|p\\.?\\s*ltd\\.?|inc\\.?|corp\\.?|llp)$", "").trim();
                if (cleanPrefix.length() >= 5 && !cleanPrefix.equalsIgnoreCase(prefix) && containsNeedle.test(cleanPrefix)) {
                    return true;
                }
            }
        }

        // 4. Check authorityId
        if (authorityId != null && !authorityId.isBlank() && authorityId.length() >= 5) {
            String normAuthId = authorityId.toLowerCase().trim();
            if (containsNeedle.test(normAuthId)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isAuthorityCitedInText(
            CorpusPrecedentEntry entry,
            String fullText,
            List<String> pinnedPrecedentStrings
    ) {
        if (entry == null) return false;
        return isAuthorityCitedInText(
                entry.caseTitle(),
                entry.primaryCitation(),
                entry.equivalentCitations(),
                entry.authorityId(),
                fullText,
                pinnedPrecedentStrings
        );
    }

    public static boolean isAuthorityCitedInText(
            CandidateAuthority cand,
            String fullText,
            List<String> pinnedPrecedentStrings
    ) {
        if (cand == null) return false;
        return isAuthorityCitedInText(
                cand.caseTitle,
                cand.primaryCitation,
                cand.equivalentCitations,
                cand.authorityId,
                fullText,
                pinnedPrecedentStrings
        );
    }

    public static boolean isAuthorityCitedInText(
            JudgmentAuthorityCard card,
            String fullText,
            List<String> pinnedPrecedentStrings
    ) {
        if (card == null) return false;
        return isAuthorityCitedInText(
                card.caseTitle(),
                card.primaryCitation(),
                card.equivalentCitations(),
                card.authorityId(),
                fullText,
                pinnedPrecedentStrings
        );
    }

    private CitationMatch findCitationInDocument(
            CandidateAuthority cand,
            String rawMarkdown,
            ArgumentDossier dossier
    ) {
        List<String> pinnedStrings = new ArrayList<>();
        if (dossier != null) {
            if (dossier.coreSubmissions() != null) {
                for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                    if (sub.pinnedPrecedents() != null) {
                        pinnedStrings.addAll(sub.pinnedPrecedents());
                    }
                    if (sub.legalProposition() != null) {
                        pinnedStrings.add(sub.legalProposition());
                    }
                }
            }
            if (dossier.rebuttalMatrix() != null) {
                for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                    if (reb.statutoryOrEvidentiaryAnchor() != null) {
                        pinnedStrings.add(reb.statutoryOrEvidentiaryAnchor());
                    }
                    if (reb.ourCounterRebuttal() != null) {
                        pinnedStrings.add(reb.ourCounterRebuttal());
                    }
                }
            }
            if (dossier.benchGrillQuestions() != null) {
                for (BenchQuestionItem qa : dossier.benchGrillQuestions()) {
                    if (qa.authorityPin() != null) {
                        pinnedStrings.add(qa.authorityPin());
                    }
                    if (qa.recommendedCourtroomAnswer() != null) {
                        pinnedStrings.add(qa.recommendedCourtroomAnswer());
                    }
                }
            }
        }

        boolean cited = isAuthorityCitedInText(cand, rawMarkdown, pinnedStrings);
        if (!cited) {
            return new CitationMatch(false, null);
        }

        // Extract snippet from rawMarkdown if present
        if (rawMarkdown != null && !rawMarkdown.isBlank()) {
            List<String> searchTokens = new ArrayList<>();
            if (cand.primaryCitation != null && !cand.primaryCitation.isBlank()) {
                searchTokens.add(cand.primaryCitation);
            }
            if (cand.equivalentCitations != null) {
                searchTokens.addAll(cand.equivalentCitations);
            }
            if (cand.caseTitle != null && !cand.caseTitle.isBlank()) {
                searchTokens.add(cand.caseTitle);
                Matcher m = Pattern.compile("(?i)^(.*?)\\s+(?:v\\.?|versus|vs\\.?)\\s+").matcher(cand.caseTitle);
                if (m.find()) {
                    String prefix = m.group(1).trim();
                    if (prefix.length() >= 5) {
                        searchTokens.add(prefix);
                    }
                    String cleanPrefix = prefix.replaceAll("(?i)\\s+(?:sas|ltd\\.?|pvt\\.?|p\\.?\\s*ltd\\.?|inc\\.?|corp\\.?|llp)$", "").trim();
                    if (cleanPrefix.length() >= 5) {
                        searchTokens.add(cleanPrefix);
                    }
                }
            }
            if (cand.authorityId != null && cand.authorityId.length() >= 5) {
                searchTokens.add(cand.authorityId);
            }

            for (String tok : searchTokens) {
                if (tok == null || tok.isBlank()) continue;
                Pattern p = Pattern.compile("(?i)" + Pattern.quote(tok));
                Matcher m = p.matcher(rawMarkdown);
                if (m.find()) {
                    String snippet = extractSnippetAround(rawMarkdown, m.start(), m.end() - m.start());
                    return new CitationMatch(true, snippet);
                }
            }
        }

        // If not found in rawMarkdown but cited in dossier pinned precedents
        if (dossier != null) {
            if (dossier.coreSubmissions() != null) {
                for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                    if (sub.pinnedPrecedents() != null) {
                        for (String prec : sub.pinnedPrecedents()) {
                            if (isAuthorityCitedInText(cand, prec, List.of())) {
                                return new CitationMatch(true, "Pinned in Submission (" + sub.submissionId() + "): " + cand.caseTitle);
                            }
                        }
                    }
                }
            }
            if (dossier.rebuttalMatrix() != null) {
                for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                    String anchor = reb.statutoryOrEvidentiaryAnchor();
                    if (anchor != null && isAuthorityCitedInText(cand, anchor, List.of())) {
                        return new CitationMatch(true, "Pinned in Rebuttal (" + reb.rebuttalId() + "): " + cand.caseTitle);
                    }
                }
            }
        }

        return new CitationMatch(true, "Cited in document: " + cand.caseTitle + " (" + cand.primaryCitation + ")");
    }

    private String extractSnippetAround(String text, int matchIdx, int matchLen) {
        if (text == null || text.isBlank() || matchIdx < 0 || matchIdx >= text.length()) {
            return "Cited in document text";
        }
        int start = Math.max(0, matchIdx - 70);
        for (int i = matchIdx; i >= Math.max(0, matchIdx - 100); i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == '>' || c == '#') {
                start = i + 1;
                break;
            }
        }
        int end = Math.min(text.length(), matchIdx + matchLen + 70);
        for (int i = matchIdx + matchLen; i < Math.min(text.length(), matchIdx + matchLen + 100); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == ';') {
                end = (c == '.') ? i + 1 : i;
                break;
            }
        }

        String snippet = text.substring(start, end).replaceAll("\\s+", " ").replace(">", "").trim();
        if (snippet.length() > 200) {
            snippet = snippet.substring(0, 197) + "...";
        }
        return snippet.isBlank() ? "Cited in document text" : snippet;
    }

    @Transactional
    public Map<String, Object> mutateDraftCitation(
            UUID workspaceId,
            UUID draftId,
            String clerkUserId,
            MutateDraftCitationRequest request
    ) {
        WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        if (request == null || request.action() == null) {
            throw new IllegalArgumentException("Mutation action is required");
        }

        String action = request.action().toUpperCase();
        String currentMarkdown = draft.getRenderedMarkdown() != null ? draft.getRenderedMarkdown() : "";
        String changeSummary = "v" + (draft.getVersion() + 1);

        switch (action) {
            case "USE_IN_DRAFT" -> {
                InAppJudgmentDrawerResponse drawer = drawerService.getDrawer(workspaceId, clerkUserId, request.targetAuthorityId());
                String citationSentence = "\n\n> **Authority Pinned:** The Complainant additionally places reliance on *"
                        + drawer.caseTitle() + "* (" + drawer.primaryCitation() + ") where it was held:\n> *"
                        + drawer.iracBrief().ratioDecidendi() + "*\n";

                currentMarkdown = currentMarkdown + citationSentence;
                changeSummary += " · Added citation '" + drawer.caseTitle() + "'";
            }
            case "REPLACE" -> {
                if (request.replacementAuthorityId() != null) {
                    InAppJudgmentDrawerResponse replDrawer = drawerService.getDrawer(workspaceId, clerkUserId, request.replacementAuthorityId());
                    InAppJudgmentDrawerResponse targetDrawer = drawerService.getDrawer(workspaceId, clerkUserId, request.targetAuthorityId());
                    currentMarkdown = currentMarkdown.replace(targetDrawer.caseTitle(), replDrawer.caseTitle())
                            .replace(targetDrawer.primaryCitation(), replDrawer.primaryCitation());
                    changeSummary += " · Replaced '" + targetDrawer.caseTitle() + "' with '" + replDrawer.caseTitle() + "'";
                }
            }
            case "REMOVE" -> {
                InAppJudgmentDrawerResponse targetDrawer = drawerService.getDrawer(workspaceId, clerkUserId, request.targetAuthorityId());
                currentMarkdown = currentMarkdown.replace(targetDrawer.caseTitle(), "")
                        .replace(targetDrawer.primaryCitation(), "");
                changeSummary += " · Removed citation '" + targetDrawer.caseTitle() + "'";
            }
            default -> throw new IllegalArgumentException("Unknown citation action: " + action);
        }

        draft.setRenderedMarkdown(currentMarkdown);
        draft.setVersion(draft.getVersion() + 1);
        draft.setUpdatedAt(Instant.now());
        WorkspaceDraft savedDraft = draftRepository.save(draft);

        WorkspaceDraftDTO draftDTO = WorkspaceDraftDTO.from(savedDraft, changeSummary, List.of("citations"));
        RightPaneInspectorResponse inspector = getInspector(workspaceId, clerkUserId, "DRAFT", draftId);

        return Map.of(
                "draft", draftDTO,
                "inspector", inspector
        );
    }

    @Transactional
    public Map<String, Object> mutateArgumentCitation(
            UUID workspaceId,
            UUID sheetId,
            String clerkUserId,
            MutateDraftCitationRequest request
    ) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        WorkspaceArgumentSheet sheet = argumentSheetRepository.findBySheetIdAndWorkspaceIdAndClerkUserId(sheetId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Argument sheet not found: " + sheetId));

        if (request == null || request.action() == null) {
            throw new IllegalArgumentException("Mutation action is required");
        }

        String action = request.action().toUpperCase();
        ArgumentDossier dossier = sheet.getArgumentDossier();
        if (dossier == null) {
            dossier = new ArgumentDossier("", List.of(), new ArrayList<>(), new ArrayList<>(), List.of(), List.of());
        }

        List<ArgumentSubmission> updatedSubmissions = new ArrayList<>();
        if (dossier.coreSubmissions() != null) {
            updatedSubmissions.addAll(dossier.coreSubmissions());
        }

        List<RebuttalMatrixItem> updatedRebuttals = new ArrayList<>();
        if (dossier.rebuttalMatrix() != null) {
            updatedRebuttals.addAll(dossier.rebuttalMatrix());
        }

        switch (action) {
            case "USE_IN_DRAFT" -> {
                InAppJudgmentDrawerResponse drawer = drawerService.getDrawer(workspaceId, clerkUserId, request.targetAuthorityId());
                String citationString = drawer.caseTitle() + (!drawer.primaryCitation().isBlank() ? " (" + drawer.primaryCitation() + ")" : "");

                if (!updatedSubmissions.isEmpty()) {
                    ArgumentSubmission firstSub = updatedSubmissions.get(0);
                    List<String> precs = new ArrayList<>(firstSub.pinnedPrecedents() != null ? firstSub.pinnedPrecedents() : List.of());
                    if (!precs.contains(citationString) && !precs.contains(drawer.caseTitle())) {
                        precs.add(citationString);
                    }
                    updatedSubmissions.set(0, new ArgumentSubmission(
                            firstSub.submissionId(),
                            firstSub.title(),
                            firstSub.legalProposition() + "\n\n> **Authority Pinned:** *" + drawer.caseTitle() + "* (" + drawer.primaryCitation() + "): " + drawer.iracBrief().ratioDecidendi(),
                            firstSub.pinnedStatutes(),
                            precs,
                            firstSub.verifiedEvidenceAnchor(),
                            firstSub.evidentiaryGapWarning()
                    ));
                } else {
                    updatedSubmissions.add(new ArgumentSubmission(
                            "SUB_1",
                            "Legal Ground & Precedent Anchor",
                            "The Complainant places heavy reliance on *" + drawer.caseTitle() + "* (" + drawer.primaryCitation() + ") where it was held:\n> *" + drawer.iracBrief().ratioDecidendi() + "*",
                            List.of(),
                            List.of(citationString),
                            "NONE_UPLOADED",
                            ""
                    ));
                }
            }
            case "REPLACE" -> {
                if (request.replacementAuthorityId() != null) {
                    InAppJudgmentDrawerResponse targetDrawer = drawerService.getDrawer(workspaceId, clerkUserId, request.targetAuthorityId());
                    InAppJudgmentDrawerResponse replDrawer = drawerService.getDrawer(workspaceId, clerkUserId, request.replacementAuthorityId());

                    String targetCit = targetDrawer.caseTitle();
                    String replCit = replDrawer.caseTitle() + (!replDrawer.primaryCitation().isBlank() ? " (" + replDrawer.primaryCitation() + ")" : "");

                    for (int i = 0; i < updatedSubmissions.size(); i++) {
                        ArgumentSubmission sub = updatedSubmissions.get(i);
                        List<String> precs = new ArrayList<>();
                        if (sub.pinnedPrecedents() != null) {
                            for (String p : sub.pinnedPrecedents()) {
                                if (p.contains(targetDrawer.caseTitle()) || p.contains(targetDrawer.primaryCitation()) || p.contains(request.targetAuthorityId())) {
                                    precs.add(replCit);
                                } else {
                                    precs.add(p);
                                }
                            }
                        }
                        String prop = sub.legalProposition() != null
                                ? sub.legalProposition().replace(targetDrawer.caseTitle(), replDrawer.caseTitle()).replace(targetDrawer.primaryCitation(), replDrawer.primaryCitation())
                                : "";

                        updatedSubmissions.set(i, new ArgumentSubmission(
                                sub.submissionId(),
                                sub.title(),
                                prop,
                                sub.pinnedStatutes(),
                                precs,
                                sub.verifiedEvidenceAnchor(),
                                sub.evidentiaryGapWarning()
                        ));
                    }

                    for (int i = 0; i < updatedRebuttals.size(); i++) {
                        RebuttalMatrixItem reb = updatedRebuttals.get(i);
                        String anchor = reb.statutoryOrEvidentiaryAnchor();
                        if (anchor != null && (anchor.contains(targetDrawer.caseTitle()) || anchor.contains(targetDrawer.primaryCitation()))) {
                            anchor = anchor.replace(targetDrawer.caseTitle(), replDrawer.caseTitle()).replace(targetDrawer.primaryCitation(), replDrawer.primaryCitation());
                            updatedRebuttals.set(i, new RebuttalMatrixItem(
                                    reb.rebuttalId(),
                                    reb.anticipatedOppositeArgument(),
                                    reb.ourCounterRebuttal().replace(targetDrawer.caseTitle(), replDrawer.caseTitle()),
                                    anchor
                            ));
                        }
                    }
                }
            }
            case "REMOVE" -> {
                InAppJudgmentDrawerResponse targetDrawer = drawerService.getDrawer(workspaceId, clerkUserId, request.targetAuthorityId());
                for (int i = 0; i < updatedSubmissions.size(); i++) {
                    ArgumentSubmission sub = updatedSubmissions.get(i);
                    List<String> precs = new ArrayList<>();
                    if (sub.pinnedPrecedents() != null) {
                        for (String p : sub.pinnedPrecedents()) {
                            if (!p.contains(targetDrawer.caseTitle()) && !p.contains(targetDrawer.primaryCitation()) && !p.contains(request.targetAuthorityId())) {
                                precs.add(p);
                            }
                        }
                    }
                    String prop = sub.legalProposition() != null
                            ? sub.legalProposition().replace(targetDrawer.caseTitle(), "").replace(targetDrawer.primaryCitation(), "")
                            : "";

                    updatedSubmissions.set(i, new ArgumentSubmission(
                            sub.submissionId(),
                            sub.title(),
                            prop,
                            sub.pinnedStatutes(),
                            precs,
                            sub.verifiedEvidenceAnchor(),
                            sub.evidentiaryGapWarning()
                    ));
                }
            }
            default -> throw new IllegalArgumentException("Unknown citation action: " + action);
        }

        ArgumentDossier updatedDossier = new ArgumentDossier(
                dossier.openingPitch60Seconds(),
                dossier.framedIssues(),
                updatedSubmissions,
                updatedRebuttals,
                dossier.benchGrillQuestions(),
                dossier.preHearingEvidenceChecklist()
        );

        sheet.setArgumentDossier(updatedDossier);
        List<StatutePin> statutePack = (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null)
                ? workspace.getLatestDraftingContext().statutePack()
                : List.of();
        String reRendered = markdownRenderer.renderToMarkdown(sheet.getTitle(), sheet.getPartyPerspective(), updatedDossier, sheet.isStaleComparedTo(workspace), statutePack);
        sheet.setRenderedMarkdown(reRendered);
        sheet.setVersion(sheet.getVersion() + 1);
        sheet.setUpdatedAt(Instant.now());

        WorkspaceArgumentSheet savedSheet = argumentSheetRepository.save(sheet);
        RightPaneInspectorResponse inspector = getInspector(workspaceId, clerkUserId, "ARGUMENT", sheetId);

        return Map.of(
                "argumentSheet", ArgumentSheetResponse.from(savedSheet, workspace),
                "sheet", ArgumentSheetResponse.from(savedSheet, workspace),
                "inspector", inspector
        );
    }

    public record ParsedStatuteMetadata(
            String parentActTitle,
            String sectionNumber,
            String sectionHeading,
            String fullSectionHeader,
            String cleanBareActText,
            Set<String> subClauseTags
    ) {}

    private static final Map<String, String> COMMON_SECTION_TITLES = Map.ofEntries(
            Map.entry("2", "Definitions"),
            Map.entry("34", "Jurisdiction of District Commission"),
            Map.entry("35", "Manner in which complaint shall be made"),
            Map.entry("36", "Proceedings before District Commission"),
            Map.entry("37", "Reference to mediation"),
            Map.entry("38", "Procedure on admission of complaint"),
            Map.entry("39", "Findings of District Commission"),
            Map.entry("69", "Limitation period"),
            Map.entry("84", "Liability of product manufacturer"),
            Map.entry("85", "Liability of product service provider"),
            Map.entry("86", "Liability of product sellers"),
            Map.entry("100", "Act not in derogation of any other law"),
            Map.entry("21", "Power of Central Authority to issue directions against misleading advertisements")
    );

    public static String toTitleCase(String input) {
        if (input == null || input.isBlank()) return "";
        String[] words = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        Set<String> lowerWords = Set.of("of", "and", "in", "the", "for", "to", "on", "under");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;
            String cleanWord = word.replaceAll("[^a-zA-Z0-9]", "");
            if (i > 0 && lowerWords.contains(cleanWord.toLowerCase())) {
                sb.append(word.toLowerCase());
            } else if (Character.isDigit(word.charAt(0))) {
                sb.append(word);
            } else if (word.length() == 1) {
                sb.append(Character.toUpperCase(word.charAt(0)));
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
            if (i < words.length - 1) sb.append(" ");
        }
        return sb.toString();
    }

    public static String humanizeActTitle(String rawAct) {
        if (rawAct == null || rawAct.isBlank()) {
            return "Governing Statutory Code";
        }
        String s = rawAct.trim();
        if (s.equalsIgnoreCase("Relevant Legal Code") || s.equalsIgnoreCase("Statutory Code")) {
            return "The Consumer Protection Act, 2019";
        }
        if (s.equalsIgnoreCase("The_Consumer_Protection_Act_2019") ||
                s.equalsIgnoreCase("consumer-protection-act-2019") ||
                s.equalsIgnoreCase("consumer-protection-act") ||
                s.equalsIgnoreCase("Consumer_Protection_Act_2019") ||
                s.equalsIgnoreCase("Consumer Protection Act 2019") ||
                s.equalsIgnoreCase("The Consumer Protection Act 2019")) {
            return "The Consumer Protection Act, 2019";
        }
        s = s.replace('_', ' ').replace('-', ' ').trim();
        s = s.replaceAll("\\s+", " ");
        s = s.replaceAll("(?i)\\bAct\\s+(\\d{4})\\b", "Act, $1");
        return toTitleCase(s);
    }

    public static String extractBaseSection(String rawSec) {
        if (rawSec == null || rawSec.isBlank()) return "";
        Matcher m = Pattern.compile("(?i)(?:section[ _-])?(\\d+)").matcher(rawSec);
        if (m.find()) {
            return m.group(1);
        }
        return rawSec.replaceAll("(?i)section[ _-]*", "").trim();
    }

    public static String humanizeSectionNumber(String rawSec) {
        return humanizeSectionNumber(rawSec, Collections.emptySet());
    }

    public static String humanizeSectionNumber(String rawSec, Set<String> citedSubClauses) {
        if (rawSec == null || rawSec.isBlank()) return "Applicable Section";
        String baseNum = extractBaseSection(rawSec);
        String title = COMMON_SECTION_TITLES.get(baseNum);
        String mainHeader;
        if (title != null && !title.isBlank()) {
            mainHeader = "Section " + baseNum + " — " + title;
        } else {
            mainHeader = "Section " + baseNum;
        }

        if (citedSubClauses != null && !citedSubClauses.isEmpty()) {
            List<String> sortedTags = new ArrayList<>(citedSubClauses);
            Collections.sort(sortedTags);
            return mainHeader + " (" + String.join(", ", sortedTags) + ")";
        }
        return mainHeader;
    }

    public static String formatSectionHeader(String sectionNumber, String heading, Set<String> subClauses) {
        String baseHeader = (sectionNumber != null && !sectionNumber.isBlank())
                ? "Section " + sectionNumber
                : "Applicable Section";

        if (heading != null && !heading.isBlank()) {
            baseHeader += " — " + heading;
        }

        if (subClauses != null && !subClauses.isEmpty()) {
            List<String> sortedTags = new ArrayList<>(subClauses);
            Collections.sort(sortedTags);
            baseHeader += " (" + String.join(", ", sortedTags) + ")";
        }
        return baseHeader;
    }

    public static ParsedStatuteMetadata parseStatutePin(StatutePin pin) {
        if (pin == null) {
            return new ParsedStatuteMetadata("Governing Statutory Code", "", "", "Applicable Section", "", Collections.emptySet());
        }

        String rawContent = pin.operativeText() != null ? pin.operativeText() : "";

        // 1. Act Title Resolution
        String resolvedAct = null;
        Matcher actMatcher = Pattern.compile("(?m)^act\\s*:\\s*['\"]?([^'\"\\r\\n]+)['\"]?").matcher(rawContent);
        if (actMatcher.find()) {
            resolvedAct = actMatcher.group(1).trim();
        }
        if (resolvedAct == null || resolvedAct.isBlank()) {
            resolvedAct = humanizeActTitle(pin.actName());
        }

        // 2. Section Number Resolution
        String secNum = null;
        Matcher secYamlMatcher = Pattern.compile("(?m)^(?:section|section_number)\\s*:\\s*['\"]?([^'\"\\r\\n]+)['\"]?").matcher(rawContent);
        if (secYamlMatcher.find()) {
            secNum = secYamlMatcher.group(1).trim();
        }
        if (secNum == null || secNum.isBlank()) {
            secNum = extractBaseSection(pin.sectionNumber());
        }
        if (secNum == null || secNum.isBlank()) {
            secNum = pin.sectionNumber() != null ? pin.sectionNumber().replaceAll("(?i)section[ _-]*", "").trim() : "";
        }

        // 3. Descriptive Heading Resolution
        String heading = null;
        Matcher headingYamlMatcher = Pattern.compile("(?m)^(?:heading|title)\\s*:\\s*['\"]?([^'\"\\r\\n]+)['\"]?").matcher(rawContent);
        if (headingYamlMatcher.find()) {
            String candidate = headingYamlMatcher.group(1).trim();
            if (!candidate.equalsIgnoreCase("null") && !candidate.equalsIgnoreCase("none")) {
                heading = candidate;
            }
        }

        if (heading == null || heading.isBlank()) {
            String strippedForHeading = sanitizeStatuteMarkdownForDisplay(rawContent);
            Matcher mdHeadingMatcher = Pattern.compile("(?m)^#{1,3}\\s+(?:Section\\s+[^.—\\n]+[.—\\s]+)?([^\\r\\n]+)").matcher(strippedForHeading);
            if (mdHeadingMatcher.find()) {
                String candidate = mdHeadingMatcher.group(1).trim();
                if (!candidate.equalsIgnoreCase("null") && !candidate.equalsIgnoreCase("none")) {
                    heading = candidate;
                }
            }
        }

        if (heading == null || heading.isBlank()) {
            if (rawContent.contains("unless the context otherwise requires") || "2".equals(secNum) || "3".equals(secNum)) {
                heading = "Definitions";
            } else if (pin.parentContext() != null && !pin.parentContext().isBlank()) {
                heading = pin.parentContext().trim();
            } else {
                heading = "";
            }
        }

        // 4. Sub-clauses extraction from ### PINNED STATUTORY ANCHORS
        Set<String> subClauseTags = new TreeSet<>();
        if (rawContent.contains("### PINNED STATUTORY ANCHORS")) {
            Matcher pinAnchorMatcher = Pattern.compile("(?i)(?:Section\\s+)?(\\d+\\([0-9a-zA-Z\\(\\)-]+\\))").matcher(rawContent);
            while (pinAnchorMatcher.find()) {
                subClauseTags.add(pinAnchorMatcher.group(1).trim());
            }
        }

        // 5. Full Section Header
        String fullHeader = formatSectionHeader(secNum, heading, subClauseTags);

        // 6. Clean Bare Act Text
        String cleanBareActText = sanitizeStatuteMarkdownForDisplay(rawContent);

        return new ParsedStatuteMetadata(
                resolvedAct,
                secNum,
                heading,
                fullHeader,
                cleanBareActText,
                subClauseTags
        );
    }

    public static String sanitizeStatuteMarkdownForDisplay(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        String text = rawContent;

        // 1. Strip internal LLM prompt prepend block: "### PINNED STATUTORY ANCHORS ... ---"
        if (text.contains("### PINNED STATUTORY ANCHORS")) {
            text = text.replaceAll("(?s)### PINNED STATUTORY ANCHORS.*?(?:\\r?\\n---\\s*\\r?\\n|(?=\\r?\\n?---)|(?=\\r?\\n?#\\s+Section)|(?=\\r?\\n?#\\s+\\d))", "");
        }

        // 2. Strip YAML frontmatter block
        text = text.replaceAll("(?s)---[\\r\\n]+(?:domain|act|act_slug|section|heading|status|source|section_number):.*?[\\r\\n]+---[\\r\\n]*", "");
        text = text.replaceAll("(?s)^\\s*---[\\r\\n]+.*?[\\r\\n]+---[\\r\\n]*", "");

        // 3. Strip stray delimiter lines (---)
        text = text.replaceAll("^(?:\\s*---+\\s*\\r?\\n)+", "");

        return text.trim();
    }

    private void populateStatutesFromPack(
            List<StatutePin> statutePack,
            Map<String, Set<String>> subClausesBySection,
            List<StatuteProvisionInspectorItem> statuteProvisions,
            Map<String, Set<String>> actToSections
    ) {
        if (statutePack == null) return;
        Map<String, StatuteProvisionInspectorItem> deduplicatedMap = new LinkedHashMap<>();

        for (StatutePin pin : statutePack) {
            ParsedStatuteMetadata parsed = parseStatutePin(pin);
            String actTitle = parsed.parentActTitle();
            String secNum = parsed.sectionNumber();

            Set<String> subClauses = new TreeSet<>(parsed.subClauseTags());
            if (subClausesBySection != null) {
                for (Map.Entry<String, Set<String>> entry : subClausesBySection.entrySet()) {
                    String entryKey = entry.getKey();
                    String[] parts = entryKey.split("::", 2);
                    String entryAct = parts.length > 1 ? parts[0] : "";
                    String entrySec = parts.length > 1 ? parts[1] : parts[0];

                    if (entrySec.equalsIgnoreCase(secNum)) {
                        if (entryAct.isBlank() || ArgumentMarkdownRenderer.isActMatch(entryAct, actTitle)) {
                            subClauses.addAll(entry.getValue());
                        }
                    }
                }
            }

            String sectionHeader = formatSectionHeader(secNum, parsed.sectionHeading(), subClauses);
            String dedupeKey = actTitle + "::" + secNum;

            if (deduplicatedMap.containsKey(dedupeKey)) {
                StatuteProvisionInspectorItem existing = deduplicatedMap.get(dedupeKey);
                if (sectionHeader.length() > existing.sectionHeader().length()) {
                    deduplicatedMap.put(dedupeKey, new StatuteProvisionInspectorItem(
                            existing.statutePinId(),
                            sectionHeader,
                            actTitle,
                            existing.cleanBareActText()
                    ));
                }
            } else {
                String pinId = "stat-" + (secNum.isBlank() ? "sec" : secNum);
                deduplicatedMap.put(dedupeKey, new StatuteProvisionInspectorItem(
                        pinId,
                        sectionHeader,
                        actTitle,
                        parsed.cleanBareActText()
                ));
            }

            actToSections.computeIfAbsent(actTitle, k -> new LinkedHashSet<>()).add(sectionHeader);
        }

        statuteProvisions.addAll(deduplicatedMap.values());
    }

    private Map<String, Set<String>> extractSubClausesFromArgumentDossier(ArgumentDossier dossier) {
        if (dossier == null) return Collections.emptyMap();
        Map<String, Set<String>> subClausesByActAndSection = new LinkedHashMap<>();

        Pattern subClausePattern = Pattern.compile("(?i)(?:Section\\s+)?(\\d+[A-Za-z]?)\\s*\\(([0-9a-zA-Z\\(\\)-]+)\\)");

        if (dossier.coreSubmissions() != null) {
            for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                if (sub.pinnedStatutes() != null) {
                    for (String stat : sub.pinnedStatutes()) {
                        String actInStat = extractActFromStatuteString(stat);
                        String actKey = actInStat != null ? actInStat.trim().toLowerCase() : "";
                        Matcher m = subClausePattern.matcher(stat);
                        while (m.find()) {
                            String sec = m.group(1);
                            String subClause = sec + "(" + m.group(2) + ")";
                            String key = actKey + "::" + sec.toLowerCase();
                            subClausesByActAndSection.computeIfAbsent(key, k -> new TreeSet<>()).add(subClause);
                        }
                    }
                }
            }
        }

        if (dossier.rebuttalMatrix() != null) {
            for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                if (reb.statutoryOrEvidentiaryAnchor() != null) {
                    String stat = reb.statutoryOrEvidentiaryAnchor();
                    String actInStat = extractActFromStatuteString(stat);
                    String actKey = actInStat != null ? actInStat.trim().toLowerCase() : "";
                    Matcher m = subClausePattern.matcher(stat);
                    while (m.find()) {
                        String sec = m.group(1);
                        String subClause = sec + "(" + m.group(2) + ")";
                        String key = actKey + "::" + sec.toLowerCase();
                        subClausesByActAndSection.computeIfAbsent(key, k -> new TreeSet<>()).add(subClause);
                    }
                }
            }
        }

        return subClausesByActAndSection;
    }

    private static String extractActFromStatuteString(String s) {
        if (s == null || s.isBlank()) return "";
        Pattern pLead = Pattern.compile("(?i)^(.+?)(?:,|\\s)+\\b(?:sections?|sec\\.?|articles?|art\\.?)\\b");
        Matcher mLead = pLead.matcher(s);
        if (mLead.find()) {
            return mLead.group(1).trim();
        }
        Pattern pOf = Pattern.compile("(?i)\\b(?:of|under|in|read\\s+with|r/w)\\s+(?:the\\s+)?([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Sanhita|Adhiniyam)(?:,\\s*\\d{4})?)");
        Matcher mOf = pOf.matcher(s);
        if (mOf.find()) {
            return mOf.group(1).trim();
        }
        return "";
    }
}
