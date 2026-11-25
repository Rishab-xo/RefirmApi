package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard PostgreSQL fallback recall adapter (Stage 1).
 * Active when refirm.search.engine=standard.
 * Uses standard pg_trgm and ts_rank_cd full-text scoring with Reciprocal Rank Fusion (RRF).
 */
@Component
@ConditionalOnProperty(name = "refirm.search.engine", havingValue = "standard")
public class StandardPostgresRecallAdapter implements PrecedentRecallPort {

    private static final Logger log = LoggerFactory.getLogger(StandardPostgresRecallAdapter.class);
    private static final double RRF_K = 20.0;
    private static final int CANDIDATE_POOL_LIMIT = 100;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final PrecedentIndexRepository precedentRepository;

    @Autowired
    public StandardPostgresRecallAdapter(
            NamedParameterJdbcTemplate namedJdbcTemplate,
            PrecedentIndexRepository precedentRepository
    ) {
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.precedentRepository = precedentRepository;
    }

    @Override
    public RecallPool recallCandidates(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
        if (query == null) {
            return RecallPool.empty();
        }

        List<String> trigramIds = recallTrigram(query, criteria);
        List<String> tupleIds = recallTuples(criteria);
        List<String> textIds = recallText(query, criteria);

        double wTrigram, wTuple, wText;
        switch (query.intent()) {
            case KNOWN_ITEM -> {
                wTrigram = 4.0;
                wText = 1.0;
                wTuple = 0.5;
            }
            case STATUTE_ONLY -> {
                wTuple = 4.0;
                wText = 0.5;
                wTrigram = 0.5;
            }
            default -> {
                wText = 1.25;
                wTuple = 1.15;
                wTrigram = 0.60;
            }
        }

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Map<String, Integer>> authorityChannelRanks = new HashMap<>();

        accumulate(trigramIds, "TRIGRAM", wTrigram, rrfScores, authorityChannelRanks);
        accumulate(tupleIds, "TUPLE", wTuple, rrfScores, authorityChannelRanks);
        accumulate(textIds, "BM25", wText, rrfScores, authorityChannelRanks);

        if (rrfScores.isEmpty()) {
            return RecallPool.empty();
        }

        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int poolSize = Math.min(sortedEntries.size(), CANDIDATE_POOL_LIMIT);
        List<String> topIds = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            topIds.add(sortedEntries.get(i).getKey());
        }

        Map<String, PrecedentIndexRecord> recordMap = batchHydrate(topIds);
        List<CandidateRecallItem> items = new ArrayList<>(poolSize);
        for (String id : topIds) {
            PrecedentIndexRecord rec = recordMap.get(id);
            if (rec != null) {
                items.add(new CandidateRecallItem(rec, rrfScores.get(id), authorityChannelRanks.getOrDefault(id, Collections.emptyMap())));
            }
        }

        return new RecallPool(items, authorityChannelRanks);
    }

    private void accumulate(
            List<String> ids,
            String channel,
            double weight,
            Map<String, Double> rrfScores,
            Map<String, Map<String, Integer>> ranks
    ) {
        if (ids == null) return;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int rank = i + 1;
            rrfScores.merge(id, weight * (1.0 / (RRF_K + rank)), Double::sum);
            ranks.computeIfAbsent(id, k -> new HashMap<>()).put(channel, rank);
        }
    }

    private List<String> recallTrigram(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
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
                   GREATEST(similarity(case_title, :rawQuery), similarity(primary_citation, :rawQuery)) AS trgm_score
            FROM legal_precedent_index
            WHERE (case_title % :rawQuery OR primary_citation % :rawQuery)
              AND (:courtFilter IS NULL OR court_code = :courtFilter)
              AND (:topicFilter IS NULL OR :topicFilter = ANY(topic_slugs))
              AND (:issueFilter IS NULL OR :issueFilter = ANY(issue_tags))
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
            log.warn("Standard adapter trigram notice: {}", e.getMessage());
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
            AND (:courtFilter IS NULL OR court_code = :courtFilter)
            LIMIT 10
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("likeCite", likeCite)
                .addValue("normCite", cleanNorm)
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null);

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Standard adapter exact citation notice: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> recallTuples(PrecedentSearchCriteria criteria) {
        if (criteria == null || criteria.expandedActSectionTuples() == null || criteria.expandedActSectionTuples().isEmpty()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT authority_id,
                   cardinality(ARRAY(SELECT UNNEST(act_section_tuples) INTERSECT SELECT UNNEST(CAST(:queryTuples AS TEXT[])))) AS match_count
            FROM legal_precedent_index
            WHERE act_section_tuples && CAST(:queryTuples AS TEXT[])
              AND (:courtFilter IS NULL OR court_code = :courtFilter)
              AND (:topicFilter IS NULL OR :topicFilter = ANY(topic_slugs))
              AND (:issueFilter IS NULL OR :issueFilter = ANY(issue_tags))
            ORDER BY match_count DESC, court_tier_weight DESC, cited_by_count DESC
            LIMIT 80
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("queryTuples", criteria.expandedActSectionTuples().toArray(new String[0]))
                .addValue("courtFilter", criteria.courtCode())
                .addValue("topicFilter", criteria.topicSlug())
                .addValue("issueFilter", criteria.issueTag());

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Standard adapter tuple notice: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> recallText(SanitizedPrecedentQuery query, PrecedentSearchCriteria criteria) {
        if (query.sanitizedLexicalQuery() == null || query.sanitizedLexicalQuery().isBlank()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT authority_id,
                   ts_rank_cd(search_vector, websearch_to_tsquery('english', :lexicalQuery)) AS rank_score
            FROM legal_precedent_index
            WHERE search_vector @@ websearch_to_tsquery('english', :lexicalQuery)
              AND (:courtFilter IS NULL OR court_code = :courtFilter)
              AND (:topicFilter IS NULL OR :topicFilter = ANY(topic_slugs))
              AND (:issueFilter IS NULL OR :issueFilter = ANY(issue_tags))
            ORDER BY rank_score DESC
            LIMIT 80
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lexicalQuery", query.sanitizedLexicalQuery().trim())
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

        try {
            return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("authority_id"));
        } catch (Exception e) {
            log.warn("Standard adapter text notice: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, PrecedentIndexRecord> batchHydrate(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        String sql = "SELECT * FROM legal_precedent_index WHERE authority_id = ANY(CAST(:ids AS TEXT[]))";
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids.toArray(new String[0]));
        try {
            List<PrecedentIndexRecord> records = namedJdbcTemplate.query(sql, params, precedentRepository.getRowMapper());
            Map<String, PrecedentIndexRecord> map = new HashMap<>();
            for (PrecedentIndexRecord r : records) map.put(r.authorityId(), r);
            return map;
        } catch (Exception e) {
            log.warn("Standard adapter hydrate notice: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
