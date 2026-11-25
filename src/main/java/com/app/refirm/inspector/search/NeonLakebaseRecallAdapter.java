package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neon Lakebase-optimized multi-channel recall adapter (Stage 1).
 * Channels:
 *  1. Trigram GIN (idx_lpi_title_trgm, idx_lpi_primary_cite_trgm, idx_lpi_equiv_cites_trgm) - Top 40
 *  2. Tuple GIN containment (idx_lpi_tuples_gin) - Top 80
 *  3. Native BM25 (lakebase_text index idx_lpi_search_bm25 via <@>) - Top 80
 *  4. Dense Vector ANN (lakebase_ann index idx_lpv_embedding_ann halfvec(512) via <=>) - Top 80
 *
 * Performs in-memory Reciprocal Rank Fusion (RRF, k=60) with intent-specific weights
 * and hydrates Top-100 candidates in a single batch query.
 */
@Component
@ConditionalOnProperty(name = "refirm.search.engine", havingValue = "lakebase", matchIfMissing = true)
public class NeonLakebaseRecallAdapter implements PrecedentRecallPort {

    private static final Logger log = LoggerFactory.getLogger(NeonLakebaseRecallAdapter.class);
    private static final double RRF_K = 20.0;
    private static final int CANDIDATE_POOL_LIMIT = 100;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final PrecedentIndexRepository precedentRepository;
    private final PrecedentQueryEmbeddingService embeddingService;

    @Autowired
    public NeonLakebaseRecallAdapter(
            NamedParameterJdbcTemplate namedJdbcTemplate,
            JdbcTemplate jdbcTemplate,
            PrecedentIndexRepository precedentRepository,
            PrecedentQueryEmbeddingService embeddingService
    ) {
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.precedentRepository = precedentRepository;
        this.embeddingService = embeddingService;
    }

    @Override
    public RecallPool recallCandidates(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
        if (query == null) {
            return RecallPool.empty();
        }

        // Early async trigger for query embedding (only needed for HYBRID_LEGAL)
        CompletableFuture<String> embeddingFuture = null;
        if (query.intent() == QueryIntent.HYBRID_LEGAL && query.rawQuery() != null && !query.rawQuery().isBlank()) {
            embeddingFuture = embeddingService.embedQueryAsync(query.rawQuery());
        }

        return executeMultiChannelRecall(query, criteria, embeddingFuture);
    }

    private RecallPool executeMultiChannelRecall(
            SanitizedPrecedentQuery query,
            PrecedentSearchCriteria criteria,
            CompletableFuture<String> embeddingFuture
    ) {
        // Individual channel result lists (ordered by channel relevance)
        List<String> trigramIds = recallChannel1Trigram(query, criteria);
        List<String> tupleIds = recallChannel2Tuples(criteria);
        List<String> bm25Ids = recallChannel3BM25(query, criteria);
        List<String> vectorIds = recallChannel4Vector(query, criteria, embeddingFuture);

        // Intent-specific RRF channel weights
        double wTrigram, wTuple, wBm25, wVector;
        switch (query.intent()) {
            case KNOWN_ITEM -> {
                wTrigram = 4.0;
                wBm25 = 1.0;
                wTuple = 0.5;
                wVector = 0.0;
            }
            case STATUTE_ONLY -> {
                wTuple = 4.0;
                wBm25 = 0.5;
                wTrigram = 0.5;
                wVector = 0.5;
            }
            case HYBRID_LEGAL -> {
                wBm25 = 1.25;
                wVector = 1.10;
                wTuple = 1.15;
                wTrigram = 0.60;
            }
            default -> {
                wBm25 = 1.0;
                wVector = 1.0;
                wTuple = 1.0;
                wTrigram = 1.0;
            }
        }

        // RRF map: authorityId -> total RRF score
        Map<String, Double> rrfScores = new HashMap<>();
        // Channel rank tracking: authorityId -> (channelName -> 1-based rank)
        Map<String, Map<String, Integer>> authorityChannelRanks = new HashMap<>();

        accumulateRRF(trigramIds, "TRIGRAM", wTrigram, rrfScores, authorityChannelRanks);
        accumulateRRF(tupleIds, "TUPLE", wTuple, rrfScores, authorityChannelRanks);
        accumulateRRF(bm25Ids, "BM25", wBm25, rrfScores, authorityChannelRanks);
        accumulateRRF(vectorIds, "VECTOR", wVector, rrfScores, authorityChannelRanks);

        // Browsing fallback: if no query text/tuples provided, recall by topic/issue/court filters
        if (rrfScores.isEmpty() && criteria != null) {
            List<String> browseIds = recallBrowseCandidates(criteria);
            accumulateRRF(browseIds, "BROWSE", 1.0, rrfScores, authorityChannelRanks);
        }

        if (rrfScores.isEmpty()) {
            return RecallPool.empty();
        }

        // Sort candidate IDs descending by RRF score, take Top 100
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int poolSize = Math.min(sortedEntries.size(), CANDIDATE_POOL_LIMIT);
        List<String> topIds = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            topIds.add(sortedEntries.get(i).getKey());
        }

        // Batch hydrate full metadata from legal_precedent_index
        Map<String, PrecedentIndexRecord> recordMap = batchHydrateRecords(topIds);

        // Build CandidateRecallItem preserving RRF order
        List<CandidateRecallItem> items = new ArrayList<>(poolSize);
        for (String id : topIds) {
            PrecedentIndexRecord record = recordMap.get(id);
            if (record != null) {
                double rrfScore = rrfScores.getOrDefault(id, 0.0);
                Map<String, Integer> ranks = authorityChannelRanks.getOrDefault(id, Collections.emptyMap());
                items.add(new CandidateRecallItem(record, rrfScore, ranks));
            }
        }

        return new RecallPool(items, authorityChannelRanks);
    }

    private void accumulateRRF(
            List<String> ids,
            String channelName,
            double channelWeight,
            Map<String, Double> rrfScores,
            Map<String, Map<String, Integer>> authorityChannelRanks
    ) {
        if (ids == null || ids.isEmpty() || channelWeight <= 0.0) {
            return;
        }

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int rank = i + 1; // 1-based rank
            double contribution = channelWeight * (1.0 / (RRF_K + rank));

            rrfScores.merge(id, contribution, Double::sum);
            authorityChannelRanks.computeIfAbsent(id, k -> new HashMap<>()).put(channelName, rank);
        }
    }

    private List<String> recallChannel1Trigram(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
        if (query.rawQuery() == null || query.rawQuery().isBlank()) {
            return Collections.emptyList();
        }

        // Exact index-backed citation lookup when formal reporter citation detected
        if (query.isFormalCitation()) {
            List<String> exactMatches = recallExactCitation(query, criteria);
            if (!exactMatches.isEmpty()) {
                return exactMatches;
            }
        }

        String sql = """
            SELECT authority_id,
                   GREATEST(
                       similarity(case_title, :rawQuery),
                       similarity(primary_citation, :rawQuery),
                       similarity(lpi_join_citations(equivalent_citations), :rawQuery)
                   ) AS trgm_score
            FROM legal_precedent_index
            WHERE (
                case_title % :rawQuery
                OR primary_citation % :rawQuery
                OR lpi_join_citations(equivalent_citations) % :rawQuery
            )
              AND (CAST(:courtFilter AS VARCHAR) IS NULL OR court_code = CAST(:courtFilter AS VARCHAR))
              AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(topic_slugs))
              AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(issue_tags))
            ORDER BY trgm_score DESC
            LIMIT 40
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("rawQuery", query.rawQuery().trim())
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Notice in Channel 1 (Trigram): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> recallExactCitation(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
        String normCite = query.normalizedCitation() != null
                ? query.normalizedCitation().trim()
                : query.rawQuery().trim();
        String cleanNorm = normCite.replace(".", "").replaceAll("\\s+", " ").trim();

        Matcher numMatcher = Pattern.compile("\\b(\\d+)\\b").matcher(cleanNorm);
        String lastNum = null;
        while (numMatcher.find()) {
            lastNum = numMatcher.group(1);
        }
        String likeCite = (lastNum != null && !lastNum.isBlank()) ? "%" + lastNum + "%" : "%" + cleanNorm + "%";

        String sql = """
            SELECT authority_id
            FROM legal_precedent_index
            WHERE (
              primary_citation ILIKE :likeCite
              OR lpi_join_citations(equivalent_citations) ILIKE :likeCite
            )
            AND (
              lower(regexp_replace(replace(coalesce(primary_citation, ''), '.', ''), '\\s+', ' ', 'g')) = lower(:normCite)
              OR EXISTS (
                SELECT 1 FROM unnest(equivalent_citations) c
                WHERE lower(regexp_replace(replace(c, '.', ''), '\\s+', ' ', 'g')) = lower(:normCite)
              )
            )
            AND (CAST(:courtFilter AS VARCHAR) IS NULL OR court_code = CAST(:courtFilter AS VARCHAR))
            LIMIT 10
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("likeCite", likeCite)
                .addValue("normCite", cleanNorm)
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null);

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Notice in Channel 1 exact citation lookup: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> recallChannel2Tuples(PrecedentSearchCriteria criteria) {
        if (criteria == null || criteria.expandedActSectionTuples() == null || criteria.expandedActSectionTuples().isEmpty()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT authority_id,
                   cardinality(ARRAY(SELECT UNNEST(act_section_tuples) INTERSECT SELECT UNNEST(CAST(:queryTuples AS TEXT[])))) AS match_count
            FROM legal_precedent_index
            WHERE act_section_tuples && CAST(:queryTuples AS TEXT[])
              AND (CAST(:courtFilter AS VARCHAR) IS NULL OR court_code = CAST(:courtFilter AS VARCHAR))
              AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(topic_slugs))
              AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(issue_tags))
            ORDER BY match_count DESC, court_tier_weight DESC, cited_by_count DESC
            LIMIT 80
        """;

        String[] tupleArray = criteria.expandedActSectionTuples().toArray(new String[0]);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("queryTuples", tupleArray)
                .addValue("courtFilter", criteria.courtCode())
                .addValue("topicFilter", criteria.topicSlug())
                .addValue("issueFilter", criteria.issueTag());

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Notice in Channel 2 (Tuple GIN): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> recallChannel3BM25(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
        if (query.sanitizedLexicalQuery() == null || query.sanitizedLexicalQuery().isBlank()) {
            return Collections.emptyList();
        }

        String lexical = query.sanitizedLexicalQuery().trim();
        boolean hasFilter = criteria != null && (
                (criteria.courtCode() != null && !criteria.courtCode().isBlank()) ||
                (criteria.topicSlug() != null && !criteria.topicSlug().isBlank()) ||
                (criteria.issueTag() != null && !criteria.issueTag().isBlank())
        );

        try {
            if (hasFilter) {
                jdbcTemplate.execute("SET LOCAL lakebase_bm25.prefilter = on;");
            } else {
                jdbcTemplate.execute("SET LOCAL lakebase_bm25.default_limit = 100;");
            }

            String sql = """
                WITH bm25_res AS (
                    SELECT authority_id,
                           (search_vector <@> to_bm25query(to_tsvector('english', :lexicalQuery), 'idx_lpi_search_bm25'::regclass)) AS bm25_score
                    FROM legal_precedent_index
                    WHERE (CAST(:courtFilter AS VARCHAR) IS NULL OR court_code = CAST(:courtFilter AS VARCHAR))
                      AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(topic_slugs))
                      AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(issue_tags))
                    ORDER BY bm25_score ASC
                    LIMIT 80
                )
                SELECT authority_id FROM bm25_res WHERE bm25_score < 0
            """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("lexicalQuery", lexical)
                    .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                    .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                    .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Lakebase native BM25 notice (falling back to ts_rank_cd): {}", e.getMessage());
            return fallbackTsRank(lexical, criteria);
        }
    }

    private List<String> fallbackTsRank(String lexicalQuery, PrecedentSearchCriteria criteria) {
        String sql = """
            SELECT authority_id,
                   ts_rank_cd(search_vector, websearch_to_tsquery('english', :lexicalQuery)) AS rank_score
            FROM legal_precedent_index
            WHERE search_vector @@ websearch_to_tsquery('english', :lexicalQuery)
              AND (CAST(:courtFilter AS VARCHAR) IS NULL OR court_code = CAST(:courtFilter AS VARCHAR))
              AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(topic_slugs))
              AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(issue_tags))
            ORDER BY rank_score DESC
            LIMIT 80
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lexicalQuery", lexicalQuery)
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Notice in BM25 fallback: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> recallChannel4Vector(
            SanitizedPrecedentQuery query,
            PrecedentSearchCriteria criteria,
            CompletableFuture<String> embeddingFuture
    ) {
        if (query.intent() == QueryIntent.KNOWN_ITEM || embeddingFuture == null) {
            return Collections.emptyList();
        }

        String vectorLiteral;
        try {
            vectorLiteral = embeddingFuture.get(5000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Vector embedding await timed out or failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        if (vectorLiteral == null || vectorLiteral.isBlank()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT v.authority_id,
                   (v.embedding <=> CAST(:queryVecLiteral AS halfvec(512))) AS cosine_dist
            FROM legal_precedent_vectors v
            JOIN legal_precedent_index lpi ON lpi.authority_id = v.authority_id
            WHERE (CAST(:courtFilter AS VARCHAR) IS NULL OR lpi.court_code = CAST(:courtFilter AS VARCHAR))
              AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(lpi.topic_slugs))
              AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(lpi.issue_tags))
            ORDER BY cosine_dist ASC
            LIMIT 80
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("queryVecLiteral", vectorLiteral)
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Notice in Channel 4 (Vector ANN): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, PrecedentIndexRecord> batchHydrateRecords(List<String> authorityIds) {
        if (authorityIds == null || authorityIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String sql = "SELECT * FROM legal_precedent_index WHERE authority_id = ANY(CAST(:ids AS TEXT[]))";
        MapSqlParameterSource params = new MapSqlParameterSource("ids", authorityIds.toArray(new String[0]));

        try {
            List<PrecedentIndexRecord> records = namedJdbcTemplate.query(sql, params, precedentRepository.getRowMapper());
            Map<String, PrecedentIndexRecord> map = new HashMap<>();
            for (PrecedentIndexRecord r : records) {
                map.put(r.authorityId(), r);
            }
            return map;
        } catch (Exception e) {
            log.warn("Notice during batch hydration of {} records: {}", authorityIds.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<String> recallBrowseCandidates(PrecedentSearchCriteria criteria) {
        if (criteria == null) return Collections.emptyList();
        boolean hasAnyFilter = (criteria.courtCode() != null && !criteria.courtCode().isBlank())
                || (criteria.topicSlug() != null && !criteria.topicSlug().isBlank())
                || (criteria.issueTag() != null && !criteria.issueTag().isBlank());
        if (!hasAnyFilter) return Collections.emptyList();

        String sql = """
            SELECT authority_id
            FROM legal_precedent_index
            WHERE (CAST(:courtFilter AS VARCHAR) IS NULL OR court_code = CAST(:courtFilter AS VARCHAR))
              AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(topic_slugs))
              AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(issue_tags))
            ORDER BY court_tier_weight DESC, cited_by_count DESC, decision_year DESC
            LIMIT 80
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("courtFilter", criteria.courtCode())
                .addValue("topicFilter", criteria.topicSlug())
                .addValue("issueFilter", criteria.issueTag());

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Notice in Browse Channel: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
