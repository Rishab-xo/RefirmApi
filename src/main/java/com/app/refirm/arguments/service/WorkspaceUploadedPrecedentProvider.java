package com.app.refirm.arguments.service;

import com.app.refirm.arguments.domain.VerifiedPrecedentAuthority;
import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import com.app.refirm.inspector.service.PrecedentAsyncEnrichmentService;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IngestionStatus;
import com.app.refirm.workspace.domain.IracBrief;
import com.app.refirm.workspace.domain.StructuredDocumentSummary;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concrete precedent provider that resolves verified precedent authorities from:
 * 1. Active case documents uploaded into the workspace vault where documentType == JUDGMENT_PRECEDENT, AND
 * 2. Tier 1 PostgreSQL additive scoring index (Table 1: legal_precedent_index) powered by Ring 1 Pari Materia crosswalk,
 *    with automatic fallback to CorpusPrecedentRegistryService in isolated/offline test environments.
 */
@Component
public class WorkspaceUploadedPrecedentProvider implements PrecedentAuthorityProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceUploadedPrecedentProvider.class);

    private static final Pattern INLINE_SEC_PATTERN = Pattern.compile(
            "(?i)(?:Section|Sec\\.?|S\\.?|Article|Art\\.?)\\s*([0-9]+[A-Za-z]?(?:\\([0-9A-Za-z]+\\))*)");

    private final WorkspaceDocumentRepository documentRepository;
    private final CorpusPrecedentRegistryService corpusRegistry;
    private final PrecedentIndexRepository precedentIndexRepository;
    private final PariMateriaCrosswalkService crosswalkService;
    private final PrecedentAsyncEnrichmentService asyncEnrichmentService;

    @Autowired
    public WorkspaceUploadedPrecedentProvider(
            WorkspaceDocumentRepository documentRepository,
            @Autowired(required = false) CorpusPrecedentRegistryService corpusRegistry,
            @Autowired(required = false) PrecedentIndexRepository precedentIndexRepository,
            @Autowired(required = false) PariMateriaCrosswalkService crosswalkService,
            @Autowired(required = false) PrecedentAsyncEnrichmentService asyncEnrichmentService
    ) {
        this.documentRepository = documentRepository;
        this.corpusRegistry = corpusRegistry;
        this.precedentIndexRepository = precedentIndexRepository;
        this.crosswalkService = crosswalkService;
        this.asyncEnrichmentService = asyncEnrichmentService;
    }

    public WorkspaceUploadedPrecedentProvider(
            WorkspaceDocumentRepository documentRepository,
            CorpusPrecedentRegistryService corpusRegistry,
            PrecedentIndexRepository precedentIndexRepository,
            PariMateriaCrosswalkService crosswalkService
    ) {
        this(documentRepository, corpusRegistry, precedentIndexRepository, crosswalkService, null);
    }

    public WorkspaceUploadedPrecedentProvider(
            WorkspaceDocumentRepository documentRepository,
            CorpusPrecedentRegistryService corpusRegistry
    ) {
        this(documentRepository, corpusRegistry, null, null, null);
    }

    public WorkspaceUploadedPrecedentProvider(WorkspaceDocumentRepository documentRepository) {
        this(documentRepository, null, null, null, null);
    }

    @Override
    public List<VerifiedPrecedentAuthority> resolveVerifiedPrecedents(LegalWorkspace workspace) {
        return resolveVerifiedPrecedents(workspace, "", List.of(), false);
    }

    @Override
    public List<VerifiedPrecedentAuthority> resolveVerifiedPrecedents(
            LegalWorkspace workspace,
            String activeQueryText,
            Collection<String> resolvedActSectionTuples,
            boolean isStandaloneQuery
    ) {
        List<VerifiedPrecedentAuthority> verifiedList = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // -----------------------------------------------------------------------------------------
        // PRIORITY 1: Active uploaded JUDGMENT_PRECEDENT vault documents for this specific workspace
        // (Contamination guard: only when attached to a real workspace and not standalone browsing)
        // -----------------------------------------------------------------------------------------
        if (!isStandaloneQuery && workspace != null && workspace.getWorkspaceId() != null && documentRepository != null) {
            List<WorkspaceDocument> documents = documentRepository
                    .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(
                            workspace.getWorkspaceId(),
                            workspace.getClerkUserId()
                    );

            if (documents != null && !documents.isEmpty()) {
                for (WorkspaceDocument doc : documents) {
                    if (doc.getDocumentType() == DocumentType.JUDGMENT_PRECEDENT
                            && doc.getIngestionStatus() == IngestionStatus.READY) {

                        String authId = doc.getDocumentId().toString();
                        if (seenIds.add(authId)) {
                            StructuredDocumentSummary summary = doc.getStructuredSummary();
                            if (summary != null) {
                                String title = (summary.title() != null && !summary.title().isBlank())
                                        ? summary.title()
                                        : doc.getFileName();

                                IracBrief irac = summary.iracBrief();
                                List<String> propositions = (irac != null && !irac.issuesFramed().isEmpty())
                                        ? irac.issuesFramed()
                                        : summary.keyPartiesAndRoles();

                                String ratio = (irac != null && !irac.ratioDecidendi().isBlank())
                                        ? irac.ratioDecidendi()
                                        : summary.executiveGist();

                                verifiedList.add(new VerifiedPrecedentAuthority(
                                        authId,
                                        title,
                                        propositions,
                                        ratio,
                                        "WORKSPACE_UPLOADED_JUDGMENT"
                                ));
                            } else {
                                verifiedList.add(new VerifiedPrecedentAuthority(
                                        authId,
                                        doc.getFileName(),
                                        List.of(),
                                        "",
                                        "WORKSPACE_UPLOADED_JUDGMENT"
                                ));
                            }
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 2: Tier 1 PostgreSQL Additive Search (Table 1: legal_precedent_index) via Ring 1 + 2
        // -----------------------------------------------------------------------------------------
        Set<String> collectedTuples = new LinkedHashSet<>();
        if (resolvedActSectionTuples != null) {
            for (String t : resolvedActSectionTuples) {
                if (t != null && !t.isBlank()) {
                    collectedTuples.add(t.trim());
                }
            }
        }

        // Extract additional statutory tuples from active query text if available
        if (activeQueryText != null && !activeQueryText.isBlank() && crosswalkService != null) {
            extractTuplesFromText(activeQueryText, collectedTuples);
        }

        // Extract tuples from workspace statutePack if !isStandaloneQuery
        if (!isStandaloneQuery && workspace != null && workspace.getLatestDraftingContext() != null
                && workspace.getLatestDraftingContext().statutePack() != null) {
            for (var pin : workspace.getLatestDraftingContext().statutePack()) {
                if (pin.actName() != null && pin.sectionNumber() != null && crosswalkService != null) {
                    String t = crosswalkService.toCanonicalTuple(pin.actName(), pin.sectionNumber());
                    if (!t.isEmpty()) {
                        collectedTuples.add(t);
                    }
                }
            }
        }

        PariMateriaCrosswalkService.ExpandedTupleQuery expandedQuery = null;
        if (crosswalkService != null) {
            expandedQuery = crosswalkService.expandQueryTuples(collectedTuples);
        }

        String preferredDomain = null;
        if (!isStandaloneQuery && workspace != null) {
            if (workspace.getPrimaryDomain() != null && workspace.getPrimaryDomain() != com.app.refirm.triage.domain.LegalDomain.UNKNOWN) {
                preferredDomain = workspace.getPrimaryDomain().name();
            }
        }

        boolean dbSearched = false;
        if (precedentIndexRepository != null) {
            try {
                List<String> queryTuples = (expandedQuery != null && expandedQuery.allExpandedTuples() != null)
                        ? expandedQuery.allExpandedTuples()
                        : new ArrayList<>(collectedTuples);

                String propositionText = (activeQueryText != null && !activeQueryText.isBlank())
                        ? activeQueryText.trim()
                        : (workspace != null && workspace.getTitle() != null ? workspace.getTitle() : null);

                PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(
                        queryTuples,
                        propositionText,
                        preferredDomain,
                        null,
                        null,
                        null,
                        8
                );

                List<PrecedentIndexRecord> dbMatches = precedentIndexRepository.searchPrecedents(criteria);
                if (dbMatches != null && !dbMatches.isEmpty()) {
                    dbSearched = true;
                    for (PrecedentIndexRecord rec : dbMatches) {
                        if (seenIds.add(rec.authorityId())) {
                            // Ring 4: Fire async write-behind enrichment if CATALOG_STUB
                            if ("CATALOG_STUB".equalsIgnoreCase(rec.enrichmentStatus()) && asyncEnrichmentService != null) {
                                asyncEnrichmentService.triggerAsyncEnrichment(rec, null);
                            }
                            Optional<String> badge = (crosswalkService != null && expandedQuery != null)
                                    ? crosswalkService.resolveCrosswalkBadge(expandedQuery, rec.actSectionTuples())
                                    : Optional.empty();

                            String supportingPosture = (rec.supportingPosture() != null && !rec.supportingPosture().isBlank())
                                    ? rec.supportingPosture()
                                    : "BOTH_SIDES";

                            String formattedRatio = "[Supports: " + supportingPosture + "] "
                                    + (badge.map(b -> b + " ").orElse(""))
                                    + rec.twoLineRatio();

                            String citationText = rec.caseTitle();
                            if (rec.primaryCitation() != null && !rec.primaryCitation().isBlank()) {
                                citationText += " (" + rec.primaryCitation() + ")";
                            }

                            verifiedList.add(new VerifiedPrecedentAuthority(
                                    rec.authorityId(),
                                    citationText,
                                    rec.legalPropositions(),
                                    formattedRatio,
                                    "VERIFIED_CORPUS"
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Notice querying precedent index in WorkspaceUploadedPrecedentProvider: {}. Falling back to in-memory catalog.",
                        e.getMessage());
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 3: In-Memory CorpusPrecedentRegistryService Fallback (Offline / Unit Tests)
        // -----------------------------------------------------------------------------------------
        if (!dbSearched && corpusRegistry != null) {
            List<CorpusPrecedentEntry> corpusEntries = (workspace != null)
                    ? corpusRegistry.findRelevantForWorkspace(workspace)
                    : corpusRegistry.getAllPrecedents();

            if (corpusEntries != null) {
                for (CorpusPrecedentEntry entry : corpusEntries) {
                    if (seenIds.add(entry.authorityId())) {
                        String citationText = entry.caseTitle();
                        if (entry.primaryCitation() != null && !entry.primaryCitation().isBlank()) {
                            citationText += " (" + entry.primaryCitation() + ")";
                        }

                        // Determine posture and crosswalk badge if available
                        Optional<String> badge = Optional.empty();
                        if (crosswalkService != null && expandedQuery != null) {
                            badge = crosswalkService.resolveCrosswalkBadge(expandedQuery, extractEntryTuples(entry));
                        }

                        String formattedRatio = "[Supports: BOTH_SIDES] "
                                + (badge.map(b -> b + " ").orElse(""))
                                + entry.summary();

                        verifiedList.add(new VerifiedPrecedentAuthority(
                                entry.authorityId(),
                                citationText,
                                entry.keyPropositions(),
                                formattedRatio,
                                "VERIFIED_CORPUS"
                        ));
                    }
                }
            }
        }

        return List.copyOf(verifiedList);
    }

    private void extractTuplesFromText(String text, Set<String> target) {
        if (text == null || text.isBlank() || crosswalkService == null) {
            return;
        }

        Matcher m = INLINE_SEC_PATTERN.matcher(text);
        while (m.find()) {
            String sec = m.group(1);
            int start = Math.max(0, m.start() - 40);
            int end = Math.min(text.length(), m.end() + 40);
            String snippet = text.substring(start, end);

            // Attempt to resolve Act code around the match
            String actCode = crosswalkService.normalizeActCode(snippet);
            if (!actCode.isEmpty()) {
                String tuple = crosswalkService.toCanonicalTuple(actCode, sec);
                if (!tuple.isEmpty()) {
                    target.add(tuple);
                }
            }
        }
    }

    private List<String> extractEntryTuples(CorpusPrecedentEntry entry) {
        if (crosswalkService == null) return List.of();
        Set<String> tuples = new HashSet<>();
        if (entry.iracBrief() != null && entry.iracBrief().provisionsCited() != null) {
            for (String p : entry.iracBrief().provisionsCited()) {
                extractTuplesFromText(p, tuples);
            }
        }
        if (entry.triggerKeywords() != null) {
            for (String kw : entry.triggerKeywords()) {
                extractTuplesFromText(kw, tuples);
            }
        }
        return new ArrayList<>(tuples);
    }
}
