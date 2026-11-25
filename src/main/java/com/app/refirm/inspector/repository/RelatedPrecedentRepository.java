package com.app.refirm.inspector.repository;

import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedJudgmentItem;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Read-only JDBC repository powering the 3-Tab Related Judgments API
 * (Cited By, Relies On, Similar Cases, and paginated/filtered citations).
 * Enforces the hard "ABSTAINED" firewall at SQL projection level.
 */
@Repository
public class RelatedPrecedentRepository {

    public record TargetContext(
            String authorityId,
            String primaryDomain,
            List<String> actSectionTuples,
            String embeddingLiteral
    ) {
        public TargetContext {
            actSectionTuples = actSectionTuples != null ? List.copyOf(actSectionTuples) : List.of();
        }
    }

    public record CitationSummaryCounts(
            int citedByCount,
            int reliesOnCount,
            int negativeTreatmentCount
    ) {}

    public record SimilarCandidateRow(
            String authorityId,
            String caseTitle,
            Integer decisionYear,
            String courtCode,
            String primaryCitation,
            Integer benchCount,
            boolean isConstitutionBench,
            int citedByCount,
            String goodLawStatus,
            String overruledByAuthorityId,
            String twoLineRatio,
            String primaryDomain,
            List<String> actSectionTuples,
            double cosineSim
    ) {
        public SimilarCandidateRow {
            actSectionTuples = actSectionTuples != null ? List.copyOf(actSectionTuples) : List.of();
        }
    }

    private static final String CITATION_PROJECTION = """
        lpi.authority_id, lpi.case_title, lpi.decision_year, lpi.court_code,
        lpi.primary_citation, lpi.bench_count,
        (COALESCE(lpi.bench_count, 0) >= 5 OR COALESCE(lpi.court_tier_weight, 0) >= 15) AS is_constitution_bench,
        COALESCE(lpi.cited_by_count, 0) AS cited_by_count,
        lpi.good_law_status, lpi.overruled_by_authority_id,
        c.treatment_type, c.treatment_snippet,
        CASE WHEN c.reason_status IN ('VERIFIED', 'CURATED') THEN c.substantive_reason ELSE NULL END AS substantive_reason,
        c.para_ref, c.anchor_quote, lpi.two_line_ratio
        """;

    private static final RowMapper<RelatedJudgmentItem> CITATION_ROW_MAPPER = RelatedPrecedentRepository::mapCitationRow;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public RelatedPrecedentRepository(NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    public Optional<TargetContext> findTargetContext(String authorityId) {
        String sql = """
            SELECT lpi.authority_id, lpi.primary_domain, lpi.act_section_tuples,
                   v.embedding::text AS embedding_literal
            FROM legal_precedent_index lpi
            LEFT JOIN legal_precedent_vectors v ON v.authority_id = lpi.authority_id
            WHERE lpi.authority_id = :id
            """;

        List<TargetContext> rows = namedJdbcTemplate.query(
                sql,
                new MapSqlParameterSource("id", authorityId),
                (rs, rowNum) -> new TargetContext(
                        rs.getString("authority_id"),
                        rs.getString("primary_domain"),
                        PrecedentIndexRepository.extractTextArray(rs, "act_section_tuples"),
                        rs.getString("embedding_literal")
                )
        );
        return rows.stream().findFirst();
    }

    public CitationSummaryCounts countCitationSummary(String authorityId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", authorityId);

        String inboundSql = """
            SELECT COUNT(*)::int AS cited_by_count,
                   COUNT(*) FILTER (
                       WHERE treatment_type IN ('OVERRULED', 'PER_INCURIAM', 'DISTINGUISHED')
                   )::int AS negative_treatment_count
            FROM legal_precedent_citations
            WHERE cited_authority_id = :id
            """;

        int[] inbound = namedJdbcTemplate.queryForObject(inboundSql, params, (rs, rowNum) -> new int[]{
                rs.getInt("cited_by_count"),
                rs.getInt("negative_treatment_count")
        });

        String outboundSql = """
            SELECT COUNT(*)::int
            FROM legal_precedent_citations
            WHERE citing_authority_id = :id
            """;
        Integer reliesOn = namedJdbcTemplate.queryForObject(outboundSql, params, Integer.class);

        int citedByCount = inbound != null ? inbound[0] : 0;
        int negativeCount = inbound != null ? inbound[1] : 0;
        int reliesOnCount = reliesOn != null ? reliesOn : 0;

        return new CitationSummaryCounts(citedByCount, reliesOnCount, negativeCount);
    }

    public List<RelatedJudgmentItem> findCitedBy(String authorityId, boolean negativeOnly, int offset, int limit) {
        String sql = "SELECT " + CITATION_PROJECTION + """
            FROM legal_precedent_citations c
            JOIN legal_precedent_index lpi ON lpi.authority_id = c.citing_authority_id
            WHERE c.cited_authority_id = :id
              AND (:negativeOnly = FALSE OR c.treatment_type IN ('OVERRULED', 'PER_INCURIAM', 'DISTINGUISHED'))
            ORDER BY
              CASE c.treatment_type
                WHEN 'OVERRULED' THEN 0
                WHEN 'PER_INCURIAM' THEN 1
                WHEN 'DISTINGUISHED' THEN 2
                ELSE 3
              END ASC,
              lpi.decision_year DESC NULLS LAST,
              lpi.court_tier_weight DESC NULLS LAST,
              COALESCE(lpi.cited_by_count, 0) DESC
            OFFSET :offset LIMIT :limit
            """;

        return namedJdbcTemplate.query(sql, buildCitationParams(authorityId, negativeOnly, offset, limit), CITATION_ROW_MAPPER);
    }

    public List<RelatedJudgmentItem> findReliesOn(String authorityId, boolean negativeOnly, int offset, int limit) {
        String sql = "SELECT " + CITATION_PROJECTION + """
            FROM legal_precedent_citations c
            JOIN legal_precedent_index lpi ON lpi.authority_id = c.cited_authority_id
            WHERE c.citing_authority_id = :id
              AND (:negativeOnly = FALSE OR c.treatment_type IN ('OVERRULED', 'PER_INCURIAM', 'DISTINGUISHED'))
            ORDER BY
              lpi.court_tier_weight DESC NULLS LAST,
              COALESCE(lpi.cited_by_count, 0) DESC,
              lpi.decision_year DESC NULLS LAST
            OFFSET :offset LIMIT :limit
            """;

        return namedJdbcTemplate.query(sql, buildCitationParams(authorityId, negativeOnly, offset, limit), CITATION_ROW_MAPPER);
    }

    public int countFilteredCitations(String authorityId, boolean isCitedBy, boolean negativeOnly) {
        String matchColumn = isCitedBy ? "cited_authority_id" : "citing_authority_id";
        String sql = """
            SELECT COUNT(*)::int
            FROM legal_precedent_citations
            WHERE """ + " " + matchColumn + " = :id " + """
              AND (:negativeOnly = FALSE OR treatment_type IN ('OVERRULED', 'PER_INCURIAM', 'DISTINGUISHED'))
            """;

        Integer count = namedJdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("id", authorityId)
                        .addValue("negativeOnly", negativeOnly),
                Integer.class
        );
        return count != null ? count : 0;
    }

    public List<SimilarCandidateRow> findSimilarCandidates(String authorityId, String embeddingLiteral, int poolSize) {
        String sql = """
            WITH ann_pool AS MATERIALIZED (
              SELECT v.authority_id,
                     (1.0 - (v.embedding <=> CAST(:targetVec AS halfvec(512)))) AS cosine_sim
              FROM legal_precedent_vectors v
              ORDER BY v.embedding <=> CAST(:targetVec AS halfvec(512)) ASC
              LIMIT :poolSize
            )
            SELECT lpi.authority_id, lpi.case_title, lpi.decision_year, lpi.court_code,
                   lpi.primary_citation, lpi.bench_count,
                   (COALESCE(lpi.bench_count, 0) >= 5 OR COALESCE(lpi.court_tier_weight, 0) >= 15) AS is_constitution_bench,
                   COALESCE(lpi.cited_by_count, 0) AS cited_by_count,
                   lpi.good_law_status, lpi.overruled_by_authority_id,
                   lpi.two_line_ratio, lpi.primary_domain, lpi.act_section_tuples,
                   ann.cosine_sim
            FROM ann_pool ann
            JOIN legal_precedent_index lpi ON lpi.authority_id = ann.authority_id
            WHERE lpi.authority_id <> :id
              AND NOT EXISTS (
                SELECT 1 FROM legal_precedent_citations c
                WHERE (c.citing_authority_id = :id AND c.cited_authority_id = lpi.authority_id)
                   OR (c.cited_authority_id = :id AND c.citing_authority_id = lpi.authority_id)
              )
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", authorityId)
                .addValue("targetVec", embeddingLiteral)
                .addValue("poolSize", poolSize);

        return namedJdbcTemplate.query(sql, params, (rs, rowNum) -> new SimilarCandidateRow(
                rs.getString("authority_id"),
                rs.getString("case_title"),
                getNullableInt(rs, "decision_year"),
                rs.getString("court_code"),
                rs.getString("primary_citation"),
                getNullableInt(rs, "bench_count"),
                rs.getBoolean("is_constitution_bench"),
                rs.getInt("cited_by_count"),
                rs.getString("good_law_status"),
                rs.getString("overruled_by_authority_id"),
                rs.getString("two_line_ratio"),
                rs.getString("primary_domain"),
                PrecedentIndexRepository.extractTextArray(rs, "act_section_tuples"),
                rs.getDouble("cosine_sim")
        ));
    }

    private static MapSqlParameterSource buildCitationParams(String authorityId, boolean negativeOnly, int offset, int limit) {
        return new MapSqlParameterSource()
                .addValue("id", authorityId)
                .addValue("negativeOnly", negativeOnly)
                .addValue("offset", offset)
                .addValue("limit", limit);
    }

    private static RelatedJudgmentItem mapCitationRow(ResultSet rs, int rowNum) throws SQLException {
        return new RelatedJudgmentItem(
                rs.getString("authority_id"),
                rs.getString("case_title"),
                getNullableInt(rs, "decision_year"),
                rs.getString("court_code"),
                rs.getString("primary_citation"),
                getNullableInt(rs, "bench_count"),
                rs.getBoolean("is_constitution_bench"),
                rs.getInt("cited_by_count"),
                rs.getString("good_law_status"),
                rs.getString("overruled_by_authority_id"),
                rs.getString("treatment_type"),
                rs.getString("treatment_snippet"),
                rs.getString("substantive_reason"),
                rs.getString("para_ref"),
                rs.getString("anchor_quote"),
                rs.getString("two_line_ratio"),
                List.of(),
                null
        );
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int val = rs.getInt(column);
        return rs.wasNull() ? null : val;
    }
}
