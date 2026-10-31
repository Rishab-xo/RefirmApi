package com.app.refirm.workspace.service;

import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.IngestionStatus;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceDocumentPage;
import com.app.refirm.workspace.repository.WorkspaceDocumentPageRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * High-performance Page-OKF section retriever.
 * Employs lightweight DocumentOkfManifest metadata to pinpoint exact 1-based page numbers
 * across case evidence, strictly enforcing a 5-page ceiling to prevent LLM context bloat.
 */
@Service
public class CaseDocumentOkfRetriever {

    private static final Logger log = LoggerFactory.getLogger(CaseDocumentOkfRetriever.class);

    public static final int MAX_PINNED_PAGES_PER_QUERY = 5;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "are", "was",
            "been", "have", "has", "had", "they", "them", "their", "into", "onto",
            "said", "such", "were", "what", "when", "where", "which", "who", "whom"
    );

    private final WorkspaceDocumentRepository documentRepository;
    private final WorkspaceDocumentPageRepository pageRepository;

    public CaseDocumentOkfRetriever(
            WorkspaceDocumentRepository documentRepository,
            WorkspaceDocumentPageRepository pageRepository
    ) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
    }

    /**
     * Retrieves the top pinned document pages relevant to the advocate's query or dispute issue.
     *
     * @param workspaceId Target workspace
     * @param clerkUserId Authenticated user tenant ID
     * @param query       Search query, dispute narrative snippet, or evidence keyword
     * @return List of exact WorkspaceDocumentPage records (up to 5 maximum)
     */
    public List<WorkspaceDocumentPage> retrievePinnedPages(UUID workspaceId, String clerkUserId, String query) {
        if (workspaceId == null || clerkUserId == null || query == null || query.isBlank()) {
            return List.of();
        }

        List<WorkspaceDocument> documents = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId)
                .stream()
                .filter(doc -> doc.getIngestionStatus() == IngestionStatus.READY)
                .toList();

        if (documents.isEmpty()) {
            log.debug("No active READY documents in workspace {}", workspaceId);
            return List.of();
        }

        List<String> queryTokens = Arrays.stream(query.toLowerCase().split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(t -> t.length() >= 3 && !STOP_WORDS.contains(t))
                .distinct()
                .toList();

        record PageCandidate(UUID documentId, int pageNumber, int score) {}
        Map<String, PageCandidate> candidateMap = new LinkedHashMap<>();

        for (WorkspaceDocument doc : documents) {
            DocumentOkfManifest manifest = doc.getOkfManifest();
            if (manifest == null) continue;

            String docNameLower = doc.getFileName() != null ? doc.getFileName().toLowerCase() : "";

            // 1. Topic Page Pins Scoring (Highest precision)
            if (manifest.topicPagePins() != null) {
                for (Map.Entry<String, List<Integer>> entry : manifest.topicPagePins().entrySet()) {
                    String topicKey = entry.getKey() != null ? entry.getKey().toLowerCase() : "";
                    List<Integer> pages = entry.getValue();
                    if (pages == null || pages.isEmpty()) continue;

                    int topicScore = 0;
                    for (String token : queryTokens) {
                        if (topicKey.contains(token)) {
                            topicScore += 10;
                        }
                    }

                    if (topicScore > 0) {
                        for (Integer pageNum : pages) {
                            String key = doc.getDocumentId() + ":" + pageNum;
                            int currentScore = candidateMap.containsKey(key) ? candidateMap.get(key).score() : 0;
                            candidateMap.put(key, new PageCandidate(doc.getDocumentId(), pageNum, currentScore + topicScore));
                        }
                    }
                }
            }

            // 2. Key Entities / Dates / Monetary Amounts Scoring
            int metaScore = 0;
            if (manifest.keyEntities() != null) {
                for (String entity : manifest.keyEntities()) {
                    String entityLower = entity.toLowerCase();
                    for (String token : queryTokens) {
                        if (entityLower.contains(token)) metaScore += 4;
                    }
                }
            }
            if (manifest.monetaryAmounts() != null) {
                for (String amt : manifest.monetaryAmounts()) {
                    for (String token : queryTokens) {
                        if (amt.contains(token)) metaScore += 3;
                    }
                }
            }
            for (String token : queryTokens) {
                if (docNameLower.contains(token)) metaScore += 2;
            }

            if (metaScore > 0) {
                String key = doc.getDocumentId() + ":1";
                int currentScore = candidateMap.containsKey(key) ? candidateMap.get(key).score() : 0;
                candidateMap.put(key, new PageCandidate(doc.getDocumentId(), 1, currentScore + metaScore));
            }
        }

        // 3. Fallback: If no topic or metadata matches scored, select Page 1 of the earliest documents
        if (candidateMap.isEmpty()) {
            for (WorkspaceDocument doc : documents) {
                String key = doc.getDocumentId() + ":1";
                candidateMap.put(key, new PageCandidate(doc.getDocumentId(), 1, 1));
                if (candidateMap.size() >= MAX_PINNED_PAGES_PER_QUERY) break;
            }
        }

        // Rank candidates by score descending, then pageNumber ascending
        List<PageCandidate> topCandidates = candidateMap.values().stream()
                .sorted(Comparator.comparingInt(PageCandidate::score).reversed()
                        .thenComparingInt(PageCandidate::pageNumber))
                .limit(MAX_PINNED_PAGES_PER_QUERY)
                .toList();

        // Batch load exact pages grouped by documentId
        Map<UUID, List<Integer>> pagesByDoc = topCandidates.stream()
                .collect(Collectors.groupingBy(
                        PageCandidate::documentId,
                        Collectors.mapping(PageCandidate::pageNumber, Collectors.toList())
                ));

        List<WorkspaceDocumentPage> fetchedPages = new ArrayList<>();
        for (Map.Entry<UUID, List<Integer>> entry : pagesByDoc.entrySet()) {
            fetchedPages.addAll(pageRepository.findByDocumentIdAndPageNumberInOrderByPageNumberAsc(
                    entry.getKey(), entry.getValue()
            ));
        }

        // Preserve original relevance ranking
        Map<String, WorkspaceDocumentPage> pageLookup = fetchedPages.stream()
                .collect(Collectors.toMap(p -> p.getDocumentId() + ":" + p.getPageNumber(), p -> p, (a, b) -> a));

        List<WorkspaceDocumentPage> result = new ArrayList<>();
        for (PageCandidate c : topCandidates) {
            WorkspaceDocumentPage page = pageLookup.get(c.documentId() + ":" + c.pageNumber());
            if (page != null) {
                result.add(page);
            }
        }

        log.info("Retrieved {} pinned pages for workspace {} (max: {})",
                result.size(), workspaceId, MAX_PINNED_PAGES_PER_QUERY);

        return result;
    }
}
