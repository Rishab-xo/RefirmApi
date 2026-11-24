package com.app.refirm.inspector.service;

import com.app.refirm.inspector.domain.HydratedJudgmentDocument;
import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resilient, zero-heap HTTP client that fetches raw judgment chunks from remote
 * Tier 2 storage (vaquill/open-india-law or configured REST dataset endpoint)
 * with a strict 3.5-second circuit breaker timeout.
 */
@Service
public class Tier2VaquillReadThroughClient {

    private static final Logger log = LoggerFactory.getLogger(Tier2VaquillReadThroughClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(3500);

    public static final Pattern HOLDING_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(held|we hold|in conclusion|appeal allowed|appeal dismissed|petition allowed|petition dismissed|ordered accordingly)\\b");

    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+");

    private final boolean tier2Enabled;
    private final String baseUrl;
    private final String hfToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public Tier2VaquillReadThroughClient(
            @Value("${refirm.precedents.tier2.enabled:true}") boolean tier2Enabled,
            @Value("${refirm.precedents.tier2.base-url:https://datasets-server.huggingface.co}") String baseUrl,
            @Value("${refirm.precedents.tier2.hf-token:${HF_TOKEN:}}") String hfToken,
            ObjectMapper objectMapper
    ) {
        this(tier2Enabled, baseUrl, hfToken, objectMapper, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public Tier2VaquillReadThroughClient(
            boolean tier2Enabled,
            String baseUrl,
            ObjectMapper objectMapper
    ) {
        this(tier2Enabled, baseUrl, null, objectMapper, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public Tier2VaquillReadThroughClient(
            boolean tier2Enabled,
            String baseUrl,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this(tier2Enabled, baseUrl, null, objectMapper, httpClient);
    }

    public Tier2VaquillReadThroughClient(
            boolean tier2Enabled,
            String baseUrl,
            String hfToken,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.tier2Enabled = tier2Enabled;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.replaceAll("/+$", "") : "https://datasets-server.huggingface.co";
        this.hfToken = hfToken != null && !hfToken.isBlank() ? hfToken.trim() : null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public boolean isTier2Enabled() {
        return tier2Enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Mode A — Exact parquet_doc_id Hydration.
     * Fetches raw chunks for a specific document, sorts them strictly by chunk_index ASC,
     * stitches into full text, and splits into anchored List<DrawerPage>.
     */
    public Optional<HydratedJudgmentDocument> fetchFullDocumentByDocId(String parquetShard, String parquetDocId) {
        if (!tier2Enabled || parquetDocId == null || parquetDocId.isBlank()) {
            return Optional.empty();
        }

        String shard = (parquetShard != null && !parquetShard.isBlank()) ? parquetShard : "default";
        String encodedDocId = URLEncoder.encode(parquetDocId, StandardCharsets.UTF_8);

        // Standard HuggingFace datasets-server filter query or custom REST query
        String endpointUrl = baseUrl.contains("datasets-server.huggingface.co")
                ? baseUrl + "/filter?dataset=vaquill/open-india-law&config=" + shard + "&split=train&where=doc_id%3D%27" + encodedDocId + "%27"
                : baseUrl + "/filter?doc_id=" + encodedDocId + "&shard=" + shard;

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");
            if (hfToken != null && !hfToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + hfToken);
            }
            HttpRequest request = reqBuilder.GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                log.debug("Notice: Tier 2 remote endpoint returned status {} for doc_id {}", response.statusCode(), parquetDocId);
                return Optional.empty();
            }

            return parseDocumentFromResponse(response.body(), parquetDocId);
        } catch (HttpTimeoutException e) {
            log.warn("Tier 2 fetch timed out after 3.5s circuit breaker for doc_id: {}", parquetDocId);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tier 2 fetch interrupted for doc_id: {}", parquetDocId);
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Notice: Tier 2 Vaquill fetch unavailable or offline for doc_id {}: {}", parquetDocId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Mode B — Tier 1 Cold-Miss Search Fallback.
     * When Table 1 returns < 2 hits, queries the remote dataset, groups returned chunks by canonical dedup key,
     * and extracts deterministic citation metadata + final operative holding sentence.
     */
    public List<HydratedJudgmentDocument> searchColdMissFallback(List<String> expandedTuples, String queryText, int limit) {
        if (!tier2Enabled) {
            return List.of();
        }

        int maxResults = limit > 0 ? limit : 2;
        StringBuilder qBuilder = new StringBuilder();
        if (expandedTuples != null && !expandedTuples.isEmpty()) {
            qBuilder.append(String.join(" ", expandedTuples));
        }
        if (queryText != null && !queryText.isBlank()) {
            if (!qBuilder.isEmpty()) qBuilder.append(" ");
            qBuilder.append(queryText);
        }

        String rawQuery = qBuilder.toString().trim();
        if (rawQuery.isBlank()) {
            return List.of();
        }

        String encodedQuery = URLEncoder.encode(rawQuery, StandardCharsets.UTF_8);
        String endpointUrl = baseUrl.contains("datasets-server.huggingface.co")
                ? baseUrl + "/search?dataset=vaquill/open-india-law&config=default&split=train&query=" + encodedQuery + "&limit=" + Math.min(maxResults * 5, 50)
                : baseUrl + "/search?query=" + encodedQuery + "&limit=" + maxResults;

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");
            if (hfToken != null && !hfToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + hfToken);
            }
            HttpRequest request = reqBuilder.GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return List.of();
            }

            return parseSearchResultsFromResponse(response.body(), maxResults);
        } catch (HttpTimeoutException e) {
            log.warn("Tier 2 search cold-miss fallback timed out after 3.5s for query: {}", rawQuery);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tier 2 search interrupted for query: {}", rawQuery);
            return List.of();
        } catch (Exception e) {
            log.debug("Notice: Tier 2 search fallback offline or error: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── PARSING & SPLITTING HELPERS ───

    public Optional<HydratedJudgmentDocument> parseDocumentFromResponse(String responseJson, String expectedDocId) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            List<RawChunk> chunks = extractRawChunks(root);

            if (chunks.isEmpty()) {
                return Optional.empty();
            }

            // Filter for expected doc_id if provided
            List<RawChunk> docChunks = chunks.stream()
                    .filter(c -> expectedDocId == null || expectedDocId.isBlank() || expectedDocId.equalsIgnoreCase(c.docId))
                    .sorted(Comparator.comparingInt(c -> c.chunkIndex))
                    .toList();

            if (docChunks.isEmpty()) {
                docChunks = chunks.stream().sorted(Comparator.comparingInt(c -> c.chunkIndex)).toList();
            }

            RawChunk firstChunk = docChunks.get(0);
            String docId = (firstChunk.docId != null && !firstChunk.docId.isBlank()) ? firstChunk.docId : expectedDocId;
            String caseTitle = firstChunk.caseTitle;
            String primaryCitation = firstChunk.primaryCitation;
            String courtCode = firstChunk.courtCode;
            int decisionYear = firstChunk.decisionYear;

            String fullText = docChunks.stream()
                    .map(c -> c.text)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n\n"));

            List<DrawerPage> pages = splitIntoDrawerPages(fullText, caseTitle, primaryCitation);
            String finalHolding = extractFinalHoldingSentence(fullText);

            return Optional.of(new HydratedJudgmentDocument(
                    docId,
                    caseTitle,
                    primaryCitation,
                    courtCode,
                    decisionYear,
                    pages,
                    finalHolding,
                    fullText
            ));
        } catch (Exception e) {
            log.warn("Failed to parse judgment response from Tier 2: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<HydratedJudgmentDocument> parseSearchResultsFromResponse(String responseJson, int limit) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            List<RawChunk> chunks = extractRawChunks(root);

            if (chunks.isEmpty()) {
                return List.of();
            }

            // Group chunks by canonical dedup key
            Map<String, List<RawChunk>> grouped = new LinkedHashMap<>();
            for (RawChunk c : chunks) {
                String dedupKey = PrecedentIndexRepository.buildCanonicalDedupKey(c.caseTitle, c.decisionYear, c.courtCode);
                grouped.computeIfAbsent(dedupKey, k -> new ArrayList<>()).add(c);
            }

            List<HydratedJudgmentDocument> results = new ArrayList<>();
            for (List<RawChunk> group : grouped.values()) {
                group.sort(Comparator.comparingInt(c -> c.chunkIndex));
                RawChunk first = group.get(0);

                String fullText = group.stream()
                        .map(c -> c.text)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining("\n\n"));

                String finalHolding = extractFinalHoldingSentence(fullText);
                List<DrawerPage> pages = splitIntoDrawerPages(fullText, first.caseTitle, first.primaryCitation);

                results.add(new HydratedJudgmentDocument(
                        first.docId,
                        first.caseTitle,
                        first.primaryCitation,
                        first.courtCode,
                        first.decisionYear,
                        pages,
                        finalHolding,
                        fullText
                ));

                if (results.size() >= limit) {
                    break;
                }
            }

            return results;
        } catch (Exception e) {
            log.warn("Failed to parse search results from Tier 2: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RawChunk> extractRawChunks(JsonNode root) {
        List<RawChunk> chunks = new ArrayList<>();
        JsonNode rowsNode = root.path("rows");
        if (rowsNode.isMissingNode() || !rowsNode.isArray()) {
            if (root.isArray()) {
                rowsNode = root;
            } else {
                return chunks;
            }
        }

        for (JsonNode rowElement : rowsNode) {
            JsonNode r = rowElement.has("row") ? rowElement.path("row") : rowElement;

            String docId = getFirstField(r, "doc_id", "parquet_doc_id", "id");
            int chunkIndex = getIntField(r, 0, "chunk_index", "chunkIndex", "index");
            String text = getFirstField(r, "chunk_text", "chunkText", "text", "content");
            String caseTitle = getFirstField(r, "case_title", "caseTitle", "title");
            String primaryCitation = getFirstField(r, "primary_citation", "primaryCitation", "citation");
            String courtCode = getFirstField(r, "court_code", "courtCode", "court");
            int decisionYear = getIntField(r, 0, "decision_year", "decisionYear", "year");

            if (caseTitle.isBlank()) caseTitle = "Untitled Judicial Precedent";
            if (courtCode.isBlank()) courtCode = "SC";

            chunks.add(new RawChunk(docId, chunkIndex, text, caseTitle, primaryCitation, courtCode, decisionYear));
        }

        return chunks;
    }

    /**
     * Splits full stitched text into approx 350-500 word [PARA X] anchored DrawerPages
     * with highlighted ratio holding sentences.
     */
    public List<DrawerPage> splitIntoDrawerPages(String fullText, String caseTitle, String primaryCitation) {
        if (fullText == null || fullText.isBlank()) {
            return List.of(new DrawerPage(1, "### " + caseTitle + "\n\n*[Verbatim text not available]*", List.of()));
        }

        String[] paragraphs = fullText.split("\n\n+");
        List<DrawerPage> pages = new ArrayList<>();

        StringBuilder currentPage = new StringBuilder();
        List<String> pageRatioHighlights = new ArrayList<>();
        int currentWordCount = 0;
        int pageNumber = 1;

        if (caseTitle != null && !caseTitle.isBlank()) {
            currentPage.append("## ").append(caseTitle);
            if (primaryCitation != null && !primaryCitation.isBlank()) {
                currentPage.append(" (").append(primaryCitation).append(")");
            }
            currentPage.append("\n\n");
        }

        for (int i = 0; i < paragraphs.length; i++) {
            String p = paragraphs[i].trim();
            if (p.isBlank()) continue;

            // Anchor with paragraph indicator if not already present
            String anchoredP = p.matches("^(?i)(\\[?para(?:graph)?\\s*\\d+\\]?|\\d+\\.).*") ? p : "[PARA " + (i + 1) + "] " + p;

            // Scan paragraph for ratio holding passages
            Matcher matcher = HOLDING_KEYWORD_PATTERN.matcher(anchoredP);
            if (matcher.find()) {
                String[] sentences = SENTENCE_SPLIT_PATTERN.split(anchoredP);
                for (String s : sentences) {
                    if (HOLDING_KEYWORD_PATTERN.matcher(s).find() && s.length() > 20) {
                        pageRatioHighlights.add(s.trim());
                    }
                }
            }

            int words = countWords(anchoredP);
            if (currentWordCount + words > 450 && currentPage.length() > 0) {
                pages.add(new DrawerPage(pageNumber++, currentPage.toString().trim(), List.copyOf(pageRatioHighlights)));
                currentPage.setLength(0);
                pageRatioHighlights.clear();
                currentWordCount = 0;
            }

            currentPage.append(anchoredP).append("\n\n");
            currentWordCount += words;
        }

        if (currentPage.length() > 0) {
            pages.add(new DrawerPage(pageNumber, currentPage.toString().trim(), List.copyOf(pageRatioHighlights)));
        }

        return pages.isEmpty()
                ? List.of(new DrawerPage(1, fullText.trim(), List.of()))
                : pages;
    }

    /**
     * Extracts the final operative holding sentence from the tail of the judgment.
     */
    public String extractFinalHoldingSentence(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return "Final holding statement pending retrieval.";
        }

        String[] paragraphs = fullText.split("\n\n+");
        // Scan from the last paragraph upwards
        for (int i = paragraphs.length - 1; i >= Math.max(0, paragraphs.length - 5); i--) {
            String p = paragraphs[i].trim();
            if (HOLDING_KEYWORD_PATTERN.matcher(p).find()) {
                String[] sentences = SENTENCE_SPLIT_PATTERN.split(p);
                for (int j = sentences.length - 1; j >= 0; j--) {
                    String s = sentences[j].trim();
                    if (HOLDING_KEYWORD_PATTERN.matcher(s).find() && s.length() > 15) {
                        return s;
                    }
                }
                return p;
            }
        }

        // Fallback to the very last non-blank sentence
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String p = paragraphs[i].trim();
            if (!p.isBlank()) {
                String[] sentences = SENTENCE_SPLIT_PATTERN.split(p);
                if (sentences.length > 0) {
                    return sentences[sentences.length - 1].trim();
                }
            }
        }

        return "Judgment concluded.";
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    private static String getFirstField(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode f = node.path(name);
            if (!f.isMissingNode() && !f.isNull()) {
                return f.asText().trim();
            }
        }
        return "";
    }

    private static int getIntField(JsonNode node, int defaultVal, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode f = node.path(name);
            if (!f.isMissingNode() && !f.isNull() && f.isNumber()) {
                return f.asInt();
            } else if (!f.isMissingNode() && !f.isNull()) {
                try {
                    return Integer.parseInt(f.asText().trim());
                } catch (Exception ignored) {}
            }
        }
        return defaultVal;
    }

    private record RawChunk(
            String docId,
            int chunkIndex,
            String text,
            String caseTitle,
            String primaryCitation,
            String courtCode,
            int decisionYear
    ) {}
}
