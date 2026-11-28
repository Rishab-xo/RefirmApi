package com.app.refirm.inspector.service;

import com.app.refirm.arguments.service.PrecedentAuthorityProvider;
import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.domain.HydratedJudgmentDocument;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.dto.InspectorDTOs.InAppJudgmentDrawerResponse;
import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IracBrief;
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

import java.util.*;

/**
 * Service for View 2B: In-App Judgment Drawer and Authority Saving.
 * Dynamically resolves authorities from CorpusPrecedentRegistryService and workspace vault documents.
 * Completely free of hardcoded case switches.
 */
@Service
public class AuthorityDrawerService {

    private static final Logger log = LoggerFactory.getLogger(AuthorityDrawerService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final WorkspaceSavedAuthorityRepository savedAuthorityRepository;
    private final PrecedentAuthorityProvider precedentProvider;
    private final CorpusPrecedentRegistryService corpusRegistry;
    private final com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository pageRepository;
    private final PrecedentIndexRepository precedentIndexRepository;
    private final PariMateriaCrosswalkService crosswalkService;
    private final Tier2VaquillReadThroughClient tier2Client;
    private final PrecedentAsyncEnrichmentService asyncEnrichmentService;
    private final PrecedentContentStore precedentContentStore;

    @Autowired
    public AuthorityDrawerService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            PrecedentAuthorityProvider precedentProvider,
            CorpusPrecedentRegistryService corpusRegistry,
            @Autowired(required = false) com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository pageRepository,
            @Autowired(required = false) PrecedentIndexRepository precedentIndexRepository,
            @Autowired(required = false) PariMateriaCrosswalkService crosswalkService,
            @Autowired(required = false) Tier2VaquillReadThroughClient tier2Client,
            @Autowired(required = false) PrecedentAsyncEnrichmentService asyncEnrichmentService,
            @Autowired(required = false) PrecedentContentStore precedentContentStore
    ) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.savedAuthorityRepository = savedAuthorityRepository;
        this.precedentProvider = precedentProvider;
        this.corpusRegistry = corpusRegistry;
        this.pageRepository = pageRepository;
        this.precedentIndexRepository = precedentIndexRepository;
        this.crosswalkService = crosswalkService;
        this.tier2Client = tier2Client;
        this.asyncEnrichmentService = asyncEnrichmentService;
        this.precedentContentStore = precedentContentStore;
    }

    public AuthorityDrawerService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            PrecedentAuthorityProvider precedentProvider,
            CorpusPrecedentRegistryService corpusRegistry,
            com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository pageRepository,
            PrecedentIndexRepository precedentIndexRepository,
            PariMateriaCrosswalkService crosswalkService,
            Tier2VaquillReadThroughClient tier2Client,
            PrecedentAsyncEnrichmentService asyncEnrichmentService
    ) {
        this(workspaceRepository, documentRepository, savedAuthorityRepository, precedentProvider,
                corpusRegistry, pageRepository, precedentIndexRepository, crosswalkService, tier2Client, asyncEnrichmentService, null);
    }

    public AuthorityDrawerService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            PrecedentAuthorityProvider precedentProvider,
            CorpusPrecedentRegistryService corpusRegistry,
            com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository pageRepository,
            PrecedentIndexRepository precedentIndexRepository,
            PariMateriaCrosswalkService crosswalkService
    ) {
        this(workspaceRepository, documentRepository, savedAuthorityRepository, precedentProvider,
                corpusRegistry, pageRepository, precedentIndexRepository, crosswalkService, null, null);
    }

    public AuthorityDrawerService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            PrecedentAuthorityProvider precedentProvider,
            CorpusPrecedentRegistryService corpusRegistry,
            com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository pageRepository
    ) {
        this(workspaceRepository, documentRepository, savedAuthorityRepository, precedentProvider,
                corpusRegistry, pageRepository, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public InAppJudgmentDrawerResponse getDrawer(UUID workspaceId, String clerkUserId, String authorityId) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        // 1. Check if authorityId matches an uploaded judgment document
        try {
            UUID docId = UUID.fromString(authorityId);
            Optional<WorkspaceDocument> docOpt = documentRepository.findByDocumentIdAndWorkspaceIdAndClerkUserId(docId, workspaceId, clerkUserId);
            if (docOpt.isPresent()) {
                WorkspaceDocument doc = docOpt.get();
                return buildUploadedDocDrawer(doc);
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, check corpus catalog
        }

        // 2. Check dynamic corpus authorities
        return buildCorpusAuthorityDrawer(authorityId);
    }

    @Transactional
    public Map<String, Object> saveAuthority(UUID workspaceId, String clerkUserId, String authorityId) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!savedAuthorityRepository.existsByWorkspaceIdAndAuthorityIdAndClerkUserId(workspaceId, authorityId, clerkUserId)) {
            InAppJudgmentDrawerResponse drawer = getDrawer(workspaceId, clerkUserId, authorityId);
            WorkspaceSavedAuthority saved = new WorkspaceSavedAuthority(
                    workspaceId,
                    clerkUserId,
                    authorityId,
                    drawer.caseTitle(),
                    drawer.primaryCitation()
            );
            savedAuthorityRepository.save(saved);
        }

        long count = savedAuthorityRepository.countByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId);
        return Map.of("savedToMatter", true, "totalSavedAuthorities", count);
    }

    @Transactional(readOnly = true)
    public List<JudgmentAuthorityCard> getRelevantAuthoritiesForDomain(String domainStr, UUID workspaceId, String clerkUserId) {
        List<JudgmentAuthorityCard> cards = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        LegalWorkspace workspace = null;
        if (workspaceId != null && clerkUserId != null) {
            workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId).orElse(null);
        }

        // 1. First add any active uploaded JUDGMENT_PRECEDENT documents in this workspace
        if (workspace != null) {
            List<WorkspaceDocument> vaultDocs = documentRepository
                    .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId);
            if (vaultDocs != null) {
                for (WorkspaceDocument doc : vaultDocs) {
                    if (doc.getDocumentType() == DocumentType.JUDGMENT_PRECEDENT) {
                        String authId = doc.getDocumentId().toString();
                        if (seenIds.add(authId)) {
                            boolean saved = savedAuthorityRepository.existsByWorkspaceIdAndAuthorityIdAndClerkUserId(workspaceId, authId, clerkUserId);
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

                            cards.add(new JudgmentAuthorityCard(
                                    authId,
                                    title,
                                    "Court record annexed in workspace vault",
                                    "",
                                    doc.getFileName(),
                                    List.of(doc.getFileName()),
                                    "WORKSPACE_UPLOADED_JUDGMENT",
                                    doc.getDocumentId(),
                                    null,
                                    null,
                                    gist,
                                    keyProps,
                                    saved
                            ));
                        }
                    }
                }
            }
        }

        // 2. Tier 1 PostgreSQL search via PrecedentIndexRepository (falling back to in-memory CorpusPrecedentRegistryService)
        boolean dbSearchSuccess = false;
        if (precedentIndexRepository != null) {
            try {
                PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(
                        List.of(),
                        null,
                        domainStr,
                        null,
                        null,
                        null,
                        20
                );
                List<PrecedentIndexRecord> dbMatches = precedentIndexRepository.searchPrecedents(criteria);
                if (dbMatches != null && !dbMatches.isEmpty()) {
                    dbSearchSuccess = true;
                    for (PrecedentIndexRecord rec : dbMatches) {
                        if (seenIds.add(rec.authorityId())) {
                            boolean saved = workspaceId != null && clerkUserId != null
                                    && savedAuthorityRepository.existsByWorkspaceIdAndAuthorityIdAndClerkUserId(workspaceId, rec.authorityId(), clerkUserId);

                            cards.add(new JudgmentAuthorityCard(
                                    rec.authorityId(),
                                    rec.caseTitle(),
                                    rec.courtName(),
                                    rec.judgmentDate(),
                                    rec.primaryCitation(),
                                    rec.equivalentCitations(),
                                    "VERIFIED_CORPUS",
                                    null,
                                    null,
                                    null,
                                    rec.twoLineRatio(),
                                    rec.legalPropositions(),
                                    saved
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Notice querying precedent index in getRelevantAuthoritiesForDomain: {}", e.getMessage());
            }
        }

        if (!dbSearchSuccess && corpusRegistry != null) {
            List<CorpusPrecedentEntry> entries = (workspace != null)
                    ? corpusRegistry.findRelevantForWorkspace(workspace)
                    : corpusRegistry.findByDomain(domainStr);

            for (CorpusPrecedentEntry entry : entries) {
                if (seenIds.add(entry.authorityId())) {
                    boolean saved = workspaceId != null && clerkUserId != null
                            && savedAuthorityRepository.existsByWorkspaceIdAndAuthorityIdAndClerkUserId(workspaceId, entry.authorityId(), clerkUserId);

                    cards.add(new JudgmentAuthorityCard(
                            entry.authorityId(),
                            entry.caseTitle(),
                            entry.courtName(),
                            entry.judgmentDate(),
                            entry.primaryCitation(),
                            entry.equivalentCitations(),
                            "VERIFIED_CORPUS",
                            null,
                            null,
                            null,
                            entry.summary(),
                            entry.keyPropositions(),
                            saved
                    ));
                }
            }
        }

        return cards;
    }

    private InAppJudgmentDrawerResponse buildUploadedDocDrawer(WorkspaceDocument doc) {
        StructuredDocumentSummary sum = doc.getStructuredSummary();
        IracBrief irac = sum != null && sum.iracBrief() != null
                ? sum.iracBrief()
                : new IracBrief(
                "Uploaded document content extracted from " + doc.getFileName(),
                List.of("Subject matter: " + doc.getFileName()),
                List.of("Applicable legal provisions"),
                "Takeaways and legal analysis from uploaded file",
                "Status: Processed and cataloged in workspace"
        );

        List<DrawerPage> pages = new ArrayList<>();
        if (pageRepository != null) {
            List<com.app.refirm.workspace.entity.WorkspaceDocumentPage> storedPages =
                    pageRepository.findByDocumentIdOrderByPageNumberAsc(doc.getDocumentId());
            if (storedPages != null && !storedPages.isEmpty()) {
                for (com.app.refirm.workspace.entity.WorkspaceDocumentPage p : storedPages) {
                    pages.add(new DrawerPage(
                            p.getPageNumber(),
                            p.getPageMarkdown(),
                            irac.ratioDecidendi() != null ? List.of(irac.ratioDecidendi()) : List.of()
                    ));
                }
            }
        }

        if (pages.isEmpty()) {
            pages = List.of(
                    new DrawerPage(1, "# " + doc.getFileName() + "\n\n" + irac.facts() + "\n\n### Key Highlights\n" + String.join("\n- ", irac.issuesFramed()), List.of(irac.ratioDecidendi())),
                    new DrawerPage(2, "### Analysis\n\n" + irac.ratioDecidendi() + "\n\n### Summary\n\n" + irac.finalOrderHeld(), List.of(irac.ratioDecidendi()))
            );
        }

        return new InAppJudgmentDrawerResponse(
                doc.getDocumentId().toString(),
                doc.getFileName(),
                "Uploaded Vault Document · " + doc.getFileName(),
                List.of("Uploaded File: " + doc.getFileName()),
                irac,
                pages
        );
    }

    @Transactional(readOnly = true)
    public InAppJudgmentDrawerResponse getCorpusAuthorityDrawer(String authorityId) {
        return buildCorpusAuthorityDrawer(authorityId);
    }

    private InAppJudgmentDrawerResponse buildCorpusAuthorityDrawer(String authorityId) {
        // 1. Check Table 1 & Table 2 in PrecedentIndexRepository for sub-3ms lazy Split Reader
        if (precedentIndexRepository != null) {
            try {
                Optional<PrecedentIndexRecord> recOpt = precedentIndexRepository.findByAuthorityId(authorityId);
                if (recOpt.isPresent()) {
                    PrecedentIndexRecord rec = recOpt.get();
                    List<DrawerPage> pages = null;
                    if (precedentContentStore != null) {
                        pages = precedentContentStore.loadPages(authorityId, rec.r2ObjectKey()).orElse(null);
                    }
                    if (pages == null || pages.isEmpty()) {
                        pages = precedentIndexRepository.findDrawerPagesByAuthorityId(authorityId);
                    }

                    // Mode A: Lazy Hydration from Tier 2 Vaquill Read-Through
                    if ((pages == null || pages.isEmpty()) && tier2Client != null
                            && rec.parquetDocId() != null && !rec.parquetDocId().isBlank()) {
                        try {
                            Optional<HydratedJudgmentDocument> docOpt = tier2Client.fetchFullDocumentByDocId(
                                    rec.parquetShard(), rec.parquetDocId());
                            if (docOpt.isPresent() && !docOpt.get().pages().isEmpty()) {
                                pages = docOpt.get().pages();
                                precedentIndexRepository.saveDrawerPages(authorityId, pages);
                            }
                        } catch (Exception e) {
                            log.debug("Notice: Tier 2 lazy drawer hydration failed for {}: {}", authorityId, e.getMessage());
                        }
                    }

                    if (pages == null || pages.isEmpty()) {
                        // Synthesize clean fallback page for CATALOG_STUB rows
                        pages = List.of(new DrawerPage(
                                1,
                                "### Ratio Decidendi\n\n" + rec.twoLineRatio(),
                                List.of(rec.twoLineRatio())
                        ));
                    }

                    // Trigger async IRAC enrichment write-behind if record is CATALOG_STUB
                    if ("CATALOG_STUB".equalsIgnoreCase(rec.enrichmentStatus()) && asyncEnrichmentService != null) {
                        asyncEnrichmentService.triggerAsyncEnrichment(rec, pages);
                    }

                    IracBrief irac = resolveEffectiveIracBrief(rec);

                    return new InAppJudgmentDrawerResponse(
                            rec.authorityId(),
                            rec.caseTitle(),
                            rec.primaryCitation(),
                            rec.equivalentCitations(),
                            irac,
                            pages
                    );
                }
            } catch (Exception e) {
                log.warn("Notice querying precedent index drawer for {}: {}", authorityId, e.getMessage());
            }
        }

        // 2. In-Memory Corpus Catalog Fallback
        if (corpusRegistry != null) {
            Optional<CorpusPrecedentEntry> entryOpt = corpusRegistry.findById(authorityId);
            if (entryOpt.isPresent()) {
                CorpusPrecedentEntry entry = entryOpt.get();
                return new InAppJudgmentDrawerResponse(
                        entry.authorityId(),
                        entry.caseTitle(),
                        entry.primaryCitation(),
                        entry.equivalentCitations(),
                        entry.iracBrief(),
                        entry.pages()
                );
            }
        }

        log.warn("Corpus authority not found for ID: {}. Checking fallback.", authorityId);
        throw new IllegalArgumentException("Precedent authority not found in workspace vault or verified corpus: " + authorityId);
    }

    /**
     * Resolves an effective IRAC brief when stored irac_json is null or contains only empty shell fields,
     * populating ratio, legal propositions, statutory tuples, and disposal nature from the indexed record.
     */
    private static IracBrief resolveEffectiveIracBrief(PrecedentIndexRecord rec) {
        IracBrief raw = rec.iracBrief();
        String facts = raw != null && raw.facts() != null && !raw.facts().isBlank() ? raw.facts().trim() : "";
        List<String> issues = raw != null && raw.issuesFramed() != null && !raw.issuesFramed().isEmpty()
                ? raw.issuesFramed()
                : (rec.legalPropositions() != null ? rec.legalPropositions() : List.of());
        List<String> provisions = raw != null && raw.provisionsCited() != null && !raw.provisionsCited().isEmpty()
                ? raw.provisionsCited()
                : formatTuplesForIrac(rec.actSectionTuples());
        String ratio = raw != null && raw.ratioDecidendi() != null && !raw.ratioDecidendi().isBlank()
                ? raw.ratioDecidendi().trim()
                : (rec.twoLineRatio() != null ? rec.twoLineRatio().trim() : "");
        String finalOrder = raw != null && raw.finalOrderHeld() != null && !raw.finalOrderHeld().isBlank()
                ? raw.finalOrderHeld().trim()
                : (rec.disposalNature() != null ? rec.disposalNature().trim() : "");
        return new IracBrief(facts, issues, provisions, ratio, finalOrder);
    }

    private static List<String> formatTuplesForIrac(List<String> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.replace(":", " § "))
                .limit(12)
                .toList();
    }
}
