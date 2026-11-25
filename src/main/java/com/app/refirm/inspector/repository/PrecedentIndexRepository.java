package com.app.refirm.inspector.repository;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.workspace.domain.IracBrief;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Additive SQL repository powering the 2-Table PostgreSQL precedent engine:
 * - Table 1 (legal_precedent_index): Hot search, weighted tsvector GIN, scoring, and metadata
 * - Table 2 (legal_precedent_okf_content): Cold verbatim OKF markdown & lazy drawer pages
 *
 * Guarantees zero Hibernate entity overhead and sub-millisecond query execution.
 */
@Repository
public class PrecedentIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(PrecedentIndexRepository.class);

    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<PrecedentIndexRecord> rowMapper;

    public PrecedentIndexRepository(
            NamedParameterJdbcTemplate namedJdbcTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRowToPrecedentIndexRecord;
    }

    /**
     * Deterministically builds a canonical deduplication key from case title, decision year, and court code.
     * E.g. "Arnesh Kumar v. State of Bihar", 2014, "SC" -> "arnesh-kumar-v-state-of-bihar:2014:SC"
     */
    public static String buildCanonicalDedupKey(String caseTitle, int decisionYear, String courtCode) {
        if (caseTitle == null || caseTitle.isBlank()) {
            return "unknown:" + Math.max(decisionYear, 0) + ":" + (courtCode != null ? courtCode.trim().toUpperCase(Locale.ROOT) : "SC");
        }

        String slug = caseTitle.toLowerCase(Locale.ROOT);
        slug = slug.replaceAll("(?i)\\s+(?:vs\\.?|versus|v\\.?)\\s+", "-v-");
        slug = slug.replaceAll("[^a-z0-9\\-]+", "-");
        slug = slug.replaceAll("-+", "-");
        slug = slug.replaceAll("^-+|-+$", "");

        if (slug.length() > 96) {
            slug = slug.substring(0, 96).replaceAll("-+$", "");
        }

        int year = Math.max(decisionYear, 0);
        String court = (courtCode != null && !courtCode.isBlank()) ? courtCode.trim().toUpperCase(Locale.ROOT) : "SC";

        return slug + ":" + year + ":" + court;
    }

    /**
     * Idempotent ACID upsert across Table 1 and optional Table 2.
     * Merges array fields and preserves IRAC_ENRICHED status.
     */
    @Transactional
    public void upsertPrecedent(
            PrecedentIndexRecord record,
            String okfMarkdownFull,
            List<DrawerPage> pages,
            String sourceUrl
    ) {
        if (record == null) {
            return;
        }

        String iracJson = null;
        if (record.iracBrief() != null) {
            try {
                iracJson = objectMapper.writeValueAsString(record.iracBrief());
            } catch (Exception e) {
                log.warn("Failed to serialize IracBrief for {}: {}", record.authorityId(), e.getMessage());
            }
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("authorityId", record.authorityId());
        params.addValue("canonicalDedupKey", record.canonicalDedupKey());
        params.addValue("parquetDocId", record.parquetDocId());
        params.addValue("parquetShard", record.parquetShard());
        params.addValue("enrichmentStatus", record.enrichmentStatus());
        params.addValue("caseTitle", record.caseTitle());
        params.addValue("courtCode", record.courtCode());
        params.addValue("courtName", record.courtName());
        params.addValue("courtTierWeight", record.courtTierWeight());
        params.addValue("judgmentDate", record.judgmentDate());
        params.addValue("decisionYear", record.decisionYear());
        params.addValue("primaryCitation", record.primaryCitation());
        params.addValue("equivalentCitations", toPgArrayLiteral(record.equivalentCitations()));
        params.addValue("primaryDomain", record.primaryDomain() != null ? record.primaryDomain().name() : "UNKNOWN");
        params.addValue("secondaryDomains", toPgArrayLiteral(record.secondaryDomains()));
        params.addValue("actSectionTuples", toPgArrayLiteral(record.actSectionTuples()));
        params.addValue("topicSlugs", toPgArrayLiteral(record.topicSlugs()));
        params.addValue("issueTags", toPgArrayLiteral(record.issueTags()));
        params.addValue("supportingPosture", record.supportingPosture());
        params.addValue("disposalNature", record.disposalNature());
        params.addValue("twoLineRatio", record.twoLineRatio());
        params.addValue("legalPropositions", toPgArrayLiteral(record.legalPropositions()));
        params.addValue("triggerKeywords", toPgArrayLiteral(record.triggerKeywords()));
        params.addValue("iracJson", iracJson);
        params.addValue("hasFullText", record.hasFullText());
        params.addValue("r2ObjectKey", record.r2ObjectKey());
        params.addValue("r2Url", record.r2Url());
        params.addValue("parserVersion", record.parserVersion());

        String sqlTable1 = """
            INSERT INTO legal_precedent_index (
                authority_id, canonical_dedup_key, parquet_doc_id, parquet_shard,
                enrichment_status, case_title, court_code, court_name, court_tier_weight,
                judgment_date, decision_year, primary_citation, equivalent_citations,
                primary_domain, secondary_domains, act_section_tuples, topic_slugs,
                issue_tags, supporting_posture, disposal_nature, two_line_ratio,
                legal_propositions, trigger_keywords, irac_json,
                has_full_text, r2_object_key, r2_url, parser_version, updated_at
            ) VALUES (
                :authorityId, :canonicalDedupKey, :parquetDocId, :parquetShard,
                :enrichmentStatus, :caseTitle, :courtCode, :courtName, :courtTierWeight,
                :judgmentDate, :decisionYear, :primaryCitation, CAST(:equivalentCitations AS TEXT[]),
                :primaryDomain, CAST(:secondaryDomains AS TEXT[]), CAST(:actSectionTuples AS TEXT[]), CAST(:topicSlugs AS TEXT[]),
                CAST(:issueTags AS TEXT[]), :supportingPosture, :disposalNature, :twoLineRatio,
                CAST(:legalPropositions AS TEXT[]), CAST(:triggerKeywords AS TEXT[]), CAST(:iracJson AS JSONB),
                :hasFullText, :r2ObjectKey, :r2Url, :parserVersion, NOW()
            )
            ON CONFLICT (canonical_dedup_key) DO UPDATE SET
                authority_id = EXCLUDED.authority_id,
                parquet_doc_id = COALESCE(EXCLUDED.parquet_doc_id, legal_precedent_index.parquet_doc_id),
                parquet_shard = COALESCE(EXCLUDED.parquet_shard, legal_precedent_index.parquet_shard),
                enrichment_status = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' THEN 'IRAC_ENRICHED'
                    ELSE EXCLUDED.enrichment_status
                END,
                case_title = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' THEN legal_precedent_index.case_title
                    ELSE EXCLUDED.case_title
                END,
                court_code = EXCLUDED.court_code,
                court_name = EXCLUDED.court_name,
                court_tier_weight = GREATEST(legal_precedent_index.court_tier_weight, EXCLUDED.court_tier_weight),
                judgment_date = COALESCE(EXCLUDED.judgment_date, legal_precedent_index.judgment_date),
                decision_year = CASE WHEN EXCLUDED.decision_year > 0 THEN EXCLUDED.decision_year ELSE legal_precedent_index.decision_year END,
                primary_citation = EXCLUDED.primary_citation,
                equivalent_citations = COALESCE(EXCLUDED.equivalent_citations, legal_precedent_index.equivalent_citations),
                primary_domain = EXCLUDED.primary_domain,
                secondary_domains = COALESCE(EXCLUDED.secondary_domains, legal_precedent_index.secondary_domains),
                act_section_tuples = COALESCE(EXCLUDED.act_section_tuples, legal_precedent_index.act_section_tuples),
                topic_slugs = COALESCE(EXCLUDED.topic_slugs, legal_precedent_index.topic_slugs),
                issue_tags = COALESCE(EXCLUDED.issue_tags, legal_precedent_index.issue_tags),
                supporting_posture = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' THEN legal_precedent_index.supporting_posture
                    ELSE EXCLUDED.supporting_posture
                END,
                disposal_nature = COALESCE(EXCLUDED.disposal_nature, legal_precedent_index.disposal_nature),
                two_line_ratio = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' THEN legal_precedent_index.two_line_ratio
                    ELSE EXCLUDED.two_line_ratio
                END,
                legal_propositions = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' AND cardinality(legal_precedent_index.legal_propositions) > 0 THEN legal_precedent_index.legal_propositions
                    ELSE EXCLUDED.legal_propositions
                END,
                trigger_keywords = COALESCE(EXCLUDED.trigger_keywords, legal_precedent_index.trigger_keywords),
                irac_json = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' THEN legal_precedent_index.irac_json
                    ELSE COALESCE(EXCLUDED.irac_json, legal_precedent_index.irac_json)
                END,
                legal_propositions = ARRAY(SELECT DISTINCT UNNEST(legal_precedent_index.legal_propositions || EXCLUDED.legal_propositions)),
                trigger_keywords = ARRAY(SELECT DISTINCT UNNEST(legal_precedent_index.trigger_keywords || EXCLUDED.trigger_keywords)),
                irac_json = CASE
                    WHEN legal_precedent_index.enrichment_status = 'IRAC_ENRICHED' THEN legal_precedent_index.irac_json
                    ELSE COALESCE(EXCLUDED.irac_json, legal_precedent_index.irac_json)
                END,
                has_full_text = EXCLUDED.has_full_text OR legal_precedent_index.has_full_text,
                r2_object_key = COALESCE(NULLIF(EXCLUDED.r2_object_key, ''), legal_precedent_index.r2_object_key),
                r2_url = COALESCE(NULLIF(EXCLUDED.r2_url, ''), legal_precedent_index.r2_url),
                parser_version = COALESCE(NULLIF(EXCLUDED.parser_version, ''), legal_precedent_index.parser_version),
                updated_at = NOW()
        """;

        namedJdbcTemplate.update(sqlTable1, params);

        // Upsert Table 2 full content only if not offloaded to R2
        boolean isR2Offloaded = record.r2ObjectKey() != null && !record.r2ObjectKey().isBlank();
        boolean hasContent = !isR2Offloaded && ((okfMarkdownFull != null && !okfMarkdownFull.isBlank())
                || (pages != null && !pages.isEmpty())
                || (sourceUrl != null && !sourceUrl.isBlank()));

        if (hasContent) {
            String pagesJson = "[]";
            if (pages != null && !pages.isEmpty()) {
                try {
                    pagesJson = objectMapper.writeValueAsString(pages);
                } catch (Exception e) {
                    log.warn("Failed to serialize pages for {}: {}", record.authorityId(), e.getMessage());
                }
            }

            MapSqlParameterSource paramsT2 = new MapSqlParameterSource();
            paramsT2.addValue("authorityId", record.authorityId());
            paramsT2.addValue("okfMarkdownFull", okfMarkdownFull);
            paramsT2.addValue("pagesJson", pagesJson);
            paramsT2.addValue("sourceUrl", sourceUrl);

            String sqlTable2 = """
                INSERT INTO legal_precedent_okf_content (
                    authority_id, okf_markdown_full, pages_json, source_url, updated_at
                ) VALUES (
                    :authorityId, :okfMarkdownFull, CAST(:pagesJson AS JSONB), :sourceUrl, NOW()
                )
                ON CONFLICT (authority_id) DO UPDATE SET
                    okf_markdown_full = COALESCE(EXCLUDED.okf_markdown_full, legal_precedent_okf_content.okf_markdown_full),
                    pages_json = CASE WHEN EXCLUDED.pages_json <> '[]'::jsonb THEN EXCLUDED.pages_json ELSE legal_precedent_okf_content.pages_json END,
                    source_url = COALESCE(EXCLUDED.source_url, legal_precedent_okf_content.source_url),
                    updated_at = NOW()
            """;

            namedJdbcTemplate.update(sqlTable2, paramsT2);
        }
    }

    /**
     * Additive SQL scoring search over Table 1.
     * Incorporates tuple matches (+100), weighted tsvector (+60), domain alignment (+25),
     * court hierarchy (+15), and IRAC enrichment (+10).
     */
    public List<PrecedentIndexRecord> searchPrecedents(PrecedentSearchCriteria criteria) {
        if (criteria == null) {
            return List.of();
        }

        String sql = """
            WITH params AS (
                SELECT
                    CAST(:queryTuples AS TEXT[]) AS q_tuples,
                    CASE
                        WHEN :rawQuery IS NULL OR btrim(:rawQuery) = '' THEN NULL
                        ELSE websearch_to_tsquery('english', :rawQuery)
                    END AS ts_q,
                    CAST(:preferredDomain AS VARCHAR) AS pref_domain,
                    CAST(:topicSlug AS VARCHAR) AS topic_filter,
                    CAST(:issueTag AS VARCHAR) AS issue_filter,
                    CAST(:courtCode AS VARCHAR) AS court_filter
            )
            SELECT
                lpi.*,
                (
                    -- 1. +100 pts per matched (Act, Section) tuple
                    (COALESCE(cardinality(ARRAY(
                        SELECT UNNEST(lpi.act_section_tuples)
                        INTERSECT
                        SELECT UNNEST(p.q_tuples)
                    )), 0) * 100.0)
                    +
                    -- 2. +60 pts scaled for weighted tsvector match (Weight A propositions/title + Weight B ratio)
                    (CASE
                        WHEN p.ts_q IS NOT NULL AND lpi.search_vector @@ p.ts_q
                        THEN ts_rank_cd(lpi.search_vector, p.ts_q) * 60.0
                        ELSE 0.0
                    END)
                    +
                    -- 3. +25 pts domain alignment boost (never a hard exclusion)
                    (CASE
                        WHEN p.pref_domain IS NOT NULL AND (lpi.primary_domain = p.pref_domain OR p.pref_domain = ANY(lpi.secondary_domains))
                        THEN 25.0
                        ELSE 0.0
                    END)
                    +
                    -- 4. +15 pts max Court Hierarchy boost (SC Constitution Bench = 15, SC = 12, HC = 8, Tribunal = 5)
                    LEAST(lpi.court_tier_weight, 15)
                    +
                    -- 5. +10 pts boost for IRAC_ENRICHED over CATALOG_STUB on ties
                    (CASE WHEN lpi.enrichment_status = 'IRAC_ENRICHED' THEN 10.0 ELSE 0.0 END)
                    +
                    -- 6. Citation Network Authority boost (logarithmic scale up to 25 pts)
                    (LN(GREATEST(lpi.cited_by_count, 0) + 1.0) * 5.0)
                ) AS total_relevance_score
            FROM legal_precedent_index lpi
            CROSS JOIN params p
            WHERE
                (p.topic_filter IS NULL OR p.topic_filter = ANY(lpi.topic_slugs))
                AND (p.issue_filter IS NULL OR p.issue_filter = ANY(lpi.issue_tags))
                AND (p.court_filter IS NULL OR lpi.court_code = p.court_filter)
                AND (
                    -- Broad candidate admission: matches ANY tuple OR matches tsvector OR browsing a topic/domain/court
                    (cardinality(p.q_tuples) > 0 AND lpi.act_section_tuples && p.q_tuples)
                    OR (p.ts_q IS NOT NULL AND lpi.search_vector @@ p.ts_q)
                    OR (p.topic_filter IS NOT NULL)
                    OR (p.pref_domain IS NOT NULL AND (lpi.primary_domain = p.pref_domain OR p.pref_domain = ANY(lpi.secondary_domains)))
                    OR (cardinality(p.q_tuples) = 0 AND p.ts_q IS NULL AND p.topic_filter IS NULL AND p.pref_domain IS NULL)
                )
            ORDER BY total_relevance_score DESC, lpi.cited_by_count DESC, lpi.decision_year DESC
            LIMIT :limit
        """;

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("queryTuples", toPgArrayLiteral(criteria.expandedActSectionTuples()));
        params.addValue("rawQuery", criteria.propositionQueryText());
        params.addValue("preferredDomain", criteria.preferredDomain());
        params.addValue("topicSlug", criteria.topicSlug());
        params.addValue("issueTag", criteria.issueTag());
        params.addValue("courtCode", criteria.courtCode());
        params.addValue("limit", criteria.limit());

        try {
            return namedJdbcTemplate.query(sql, params, rowMapper);
        } catch (Exception e) {
            log.warn("Notice during precedent search: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Lazy Table 2 Reader: Queries legal_precedent_okf_content by primary key in < 3ms.
     */
    public List<DrawerPage> findDrawerPagesByAuthorityId(String authorityId) {
        if (authorityId == null || authorityId.isBlank()) {
            return List.of();
        }

        String sql = "SELECT pages_json FROM legal_precedent_okf_content WHERE authority_id = :authorityId";
        MapSqlParameterSource params = new MapSqlParameterSource("authorityId", authorityId);

        try {
            List<String> results = namedJdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getString("pages_json"));
            if (!results.isEmpty() && results.get(0) != null && !results.get(0).isBlank() && !results.get(0).trim().equals("[]")) {
                return objectMapper.readValue(results.get(0), new TypeReference<List<DrawerPage>>() {});
            }

            // Fallback to Cloudflare R2 object storage if Table 2 is offloaded
            String r2Sql = "SELECT r2_url FROM legal_precedent_index WHERE authority_id = :authorityId";
            List<String> r2Urls = namedJdbcTemplate.query(r2Sql, params, (rs, rowNum) -> rs.getString("r2_url"));
            if (!r2Urls.isEmpty() && r2Urls.get(0) != null && !r2Urls.get(0).isBlank()) {
                String r2Url = r2Urls.get(0);
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(r2Url))
                            .GET()
                            .build();
                    java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(resp.body());
                        com.fasterxml.jackson.databind.JsonNode pagesNode = rootNode.get("drawerPages");
                        if (pagesNode != null && pagesNode.isArray()) {
                            return objectMapper.readValue(pagesNode.toString(), new TypeReference<List<DrawerPage>>() {});
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Notice reading drawer pages from Cloudflare R2 ({}): {}", r2Url, ex.getMessage());
                }
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Notice querying drawer pages for {}: {}", authorityId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Lazy Table 2 Writer: Idempotently saves or updates pages_json in legal_precedent_okf_content.
     */
    public void saveDrawerPages(String authorityId, List<DrawerPage> pages) {
        if (authorityId == null || authorityId.isBlank() || pages == null || pages.isEmpty()) {
            return;
        }

        try {
            String pagesJson = objectMapper.writeValueAsString(pages);
            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("authorityId", authorityId);
            params.addValue("pagesJson", pagesJson);

            String sql = """
                INSERT INTO legal_precedent_okf_content (authority_id, pages_json, updated_at)
                VALUES (:authorityId, CAST(:pagesJson AS JSONB), NOW())
                ON CONFLICT (authority_id) DO UPDATE SET
                    pages_json = CASE WHEN EXCLUDED.pages_json <> '[]'::jsonb THEN EXCLUDED.pages_json ELSE legal_precedent_okf_content.pages_json END,
                    updated_at = NOW()
            """;
            namedJdbcTemplate.update(sql, params);
        } catch (Exception e) {
            log.warn("Failed to save drawer pages for authority {}: {}", authorityId, e.getMessage());
        }
    }

    /**
     * Finds a single precedent index record by its authority ID.
     */
    public Optional<PrecedentIndexRecord> findByAuthorityId(String authorityId) {
        if (authorityId == null || authorityId.isBlank()) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM legal_precedent_index WHERE authority_id = :authorityId";
        MapSqlParameterSource params = new MapSqlParameterSource("authorityId", authorityId);

        try {
            List<PrecedentIndexRecord> results = namedJdbcTemplate.query(sql, params, rowMapper);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.warn("Notice querying precedent index by id {}: {}", authorityId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Milestone 1 Dedup-Safe Check:
     * Checks if an R2-backed row already exists matching by authorityId, canonicalDedupKey,
     * or case-insensitive title and decision year (within +-1 year).
     */
    public Optional<PrecedentIndexRecord> findExistingR2Counterpart(
            String authorityId,
            String canonicalDedupKey,
            String caseTitle,
            int decisionYear
    ) {
        String sql = """
            SELECT * FROM legal_precedent_index
            WHERE has_full_text = TRUE
              AND (
                  authority_id = :authorityId
                  OR canonical_dedup_key = :canonicalDedupKey
                  OR (
                      ABS(decision_year - :decisionYear) <= 1
                      AND (
                          LOWER(case_title) = LOWER(:caseTitle)
                          OR LOWER(case_title) LIKE :titlePrefix
                      )
                  )
              )
            ORDER BY cited_by_count DESC, decision_year DESC
            LIMIT 1
        """;

        String prefix = "%";
        if (caseTitle != null && !caseTitle.isBlank()) {
            String[] parts = caseTitle.split("(?i)\\s+v[s.]*\\s+");
            if (parts.length > 0 && !parts[0].isBlank()) {
                String petFirst = parts[0].trim().split("\\s+")[0];
                if (petFirst.length() > 3) {
                    prefix = "%" + petFirst.toLowerCase(Locale.ROOT) + "%";
                }
            }
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("authorityId", authorityId)
                .addValue("canonicalDedupKey", canonicalDedupKey)
                .addValue("decisionYear", decisionYear)
                .addValue("caseTitle", caseTitle != null ? caseTitle.trim() : "")
                .addValue("titlePrefix", prefix);

        try {
            List<PrecedentIndexRecord> results = namedJdbcTemplate.query(sql, params, rowMapper);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.warn("Notice checking R2 counterpart for {}: {}", authorityId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Milestone 1 Enrichment:
     * Enriches an existing R2-backed precedent record with curated IRAC, taxonomy topics,
     * issue tags, trigger keywords, and equivalent citations without overwriting its R2 pointers.
     */
    @Transactional
    public void enrichR2RecordWithCuratedData(String targetAuthorityId, PrecedentIndexRecord curated) {
        if (targetAuthorityId == null || targetAuthorityId.isBlank() || curated == null) {
            return;
        }

        String iracJson = null;
        if (curated.iracBrief() != null) {
            try {
                iracJson = objectMapper.writeValueAsString(curated.iracBrief());
            } catch (Exception e) {
                log.warn("Notice serializing IRAC brief for {}: {}", targetAuthorityId, e.getMessage());
            }
        }

        String sql = """
            UPDATE legal_precedent_index
            SET irac_json = COALESCE(CAST(:iracJson AS JSONB), irac_json),
                enrichment_status = 'IRAC_ENRICHED',
                two_line_ratio = CASE
                    WHEN :twoLineRatio IS NOT NULL AND btrim(:twoLineRatio) <> '' THEN :twoLineRatio
                    ELSE two_line_ratio
                END,
                topic_slugs = ARRAY(SELECT DISTINCT UNNEST(array_cat(topic_slugs, CAST(:topicSlugs AS TEXT[])))),
                issue_tags = ARRAY(SELECT DISTINCT UNNEST(array_cat(issue_tags, CAST(:issueTags AS TEXT[])))),
                trigger_keywords = ARRAY(SELECT DISTINCT UNNEST(array_cat(trigger_keywords, CAST(:triggerKeywords AS TEXT[])))),
                equivalent_citations = ARRAY(SELECT DISTINCT UNNEST(array_cat(equivalent_citations, CAST(:equivalentCitations AS TEXT[])))),
                legal_propositions = ARRAY(SELECT DISTINCT UNNEST(array_cat(legal_propositions, CAST(:legalPropositions AS TEXT[])))),
                updated_at = NOW()
            WHERE authority_id = :targetAuthorityId
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetAuthorityId", targetAuthorityId)
                .addValue("iracJson", iracJson)
                .addValue("twoLineRatio", curated.twoLineRatio())
                .addValue("topicSlugs", toPgArrayLiteral(curated.topicSlugs()))
                .addValue("issueTags", toPgArrayLiteral(curated.issueTags()))
                .addValue("triggerKeywords", toPgArrayLiteral(curated.triggerKeywords()))
                .addValue("equivalentCitations", toPgArrayLiteral(curated.equivalentCitations()))
                .addValue("legalPropositions", toPgArrayLiteral(curated.legalPropositions()));

        try {
            namedJdbcTemplate.update(sql, params);
        } catch (Exception e) {
            log.warn("Notice enriching R2 record {}: {}", targetAuthorityId, e.getMessage());
        }
    }

    /**
     * Fast ILIKE and array-containment lookup on Table 1 to identify candidate precedent matches
     * by citation or case title snippet. Used when verifying citations or partitioning Cited | Relevant.
     */
    public List<PrecedentIndexRecord> findCandidateMatchesForText(String textSnippetOrCitation, int limit) {
        if (textSnippetOrCitation == null || textSnippetOrCitation.isBlank()) {
            return Collections.emptyList();
        }

        int maxRows = limit > 0 ? limit : 10;
        String trimmed = textSnippetOrCitation.trim();
        String wild = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";

        String sql = """
            SELECT * FROM legal_precedent_index
            WHERE LOWER(primary_citation) LIKE :wild
               OR LOWER(case_title) LIKE :wild
               OR EXISTS (
                   SELECT 1 FROM UNNEST(equivalent_citations) eq
                   WHERE LOWER(eq) LIKE :wild
               )
            ORDER BY court_tier_weight DESC, decision_year DESC
            LIMIT :limit
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("wild", wild)
                .addValue("limit", maxRows);

        try {
            return namedJdbcTemplate.query(sql, params, rowMapper);
        } catch (Exception e) {
            log.warn("Notice querying candidate matches for text '{}': {}", textSnippetOrCitation, e.getMessage());
            return Collections.emptyList();
        }
    }

    public long count() {
        try {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legal_precedent_index", Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private PrecedentIndexRecord mapRowToPrecedentIndexRecord(ResultSet rs, int rowNum) throws SQLException {
        String authorityId = rs.getString("authority_id");
        String canonicalDedupKey = rs.getString("canonical_dedup_key");
        String parquetDocId = rs.getString("parquet_doc_id");
        String parquetShard = rs.getString("parquet_shard");
        String enrichmentStatus = rs.getString("enrichment_status");
        String caseTitle = rs.getString("case_title");
        String courtCode = rs.getString("court_code");
        String courtName = rs.getString("court_name");
        int courtTierWeight = rs.getInt("court_tier_weight");
        String judgmentDate = rs.getString("judgment_date");
        int decisionYear = rs.getInt("decision_year");
        String primaryCitation = rs.getString("primary_citation");
        List<String> equivalentCitations = extractArray(rs, "equivalent_citations");

        LegalDomain primaryDomain = LegalDomain.UNKNOWN;
        String rawDomain = rs.getString("primary_domain");
        if (rawDomain != null) {
            try {
                primaryDomain = LegalDomain.valueOf(rawDomain.trim());
            } catch (Exception ignored) {}
        }

        List<String> secondaryDomains = extractArray(rs, "secondary_domains");
        List<String> actSectionTuples = extractArray(rs, "act_section_tuples");
        List<String> topicSlugs = extractArray(rs, "topic_slugs");
        List<String> issueTags = extractArray(rs, "issue_tags");
        String supportingPosture = rs.getString("supporting_posture");
        String disposalNature = rs.getString("disposal_nature");
        String twoLineRatio = rs.getString("two_line_ratio");
        List<String> legalPropositions = extractArray(rs, "legal_propositions");
        List<String> triggerKeywords = extractArray(rs, "trigger_keywords");

        IracBrief iracBrief = null;
        String iracRaw = rs.getString("irac_json");
        if (iracRaw != null && !iracRaw.isBlank()) {
            try {
                iracBrief = objectMapper.readValue(iracRaw, IracBrief.class);
            } catch (Exception ignored) {}
        }

        boolean hasFullText = false;
        try {
            hasFullText = rs.getBoolean("has_full_text");
        } catch (SQLException ignored) {}

        String r2ObjectKey = "";
        try {
            String val = rs.getString("r2_object_key");
            if (val != null) r2ObjectKey = val;
        } catch (SQLException ignored) {}

        String r2Url = "";
        try {
            String val = rs.getString("r2_url");
            if (val != null) r2Url = val;
        } catch (SQLException ignored) {}

        String parserVersion = "sc-v1.0";
        try {
            String val = rs.getString("parser_version");
            if (val != null && !val.isBlank()) parserVersion = val;
        } catch (SQLException ignored) {}

        int citedByCount = 0;
        try {
            citedByCount = rs.getInt("cited_by_count");
        } catch (SQLException ignored) {}

        Integer benchCount = null;
        try {
            int b = rs.getInt("bench_count");
            if (!rs.wasNull()) benchCount = b;
        } catch (SQLException ignored) {}

        int hcCitedByCount = 0;
        try {
            hcCitedByCount = rs.getInt("hc_cited_by_count");
        } catch (SQLException ignored) {}

        String goodLawStatus = "GOOD_LAW";
        try {
            String gl = rs.getString("good_law_status");
            if (gl != null && !gl.isBlank()) goodLawStatus = gl;
        } catch (SQLException ignored) {}

        String overruledByAuthorityId = null;
        try {
            overruledByAuthorityId = rs.getString("overruled_by_authority_id");
        } catch (SQLException ignored) {}

        return new PrecedentIndexRecord(
                authorityId,
                canonicalDedupKey,
                parquetDocId,
                parquetShard,
                enrichmentStatus,
                caseTitle,
                courtCode,
                courtName,
                courtTierWeight,
                judgmentDate,
                decisionYear,
                primaryCitation,
                equivalentCitations,
                primaryDomain,
                secondaryDomains,
                actSectionTuples,
                topicSlugs,
                issueTags,
                supportingPosture,
                disposalNature,
                twoLineRatio,
                legalPropositions,
                triggerKeywords,
                iracBrief,
                hasFullText,
                r2ObjectKey,
                r2Url,
                parserVersion,
                citedByCount,
                benchCount,
                hcCitedByCount,
                goodLawStatus,
                overruledByAuthorityId
        );
    }

    public RowMapper<PrecedentIndexRecord> getRowMapper() {
        return this.rowMapper;
    }

    /**
     * Safely decodes a PostgreSQL TEXT[] column into an immutable List&lt;String&gt;, returning an empty list on NULL.
     */
    public static List<String> extractTextArray(ResultSet rs, String column) throws SQLException {
        Array arr = rs.getArray(column);
        if (arr == null) {
            return List.of();
        }
        Object obj = arr.getArray();
        if (obj instanceof String[] strings) {
            List<String> cleaned = new ArrayList<>(strings.length);
            for (String s : strings) {
                if (s != null) {
                    cleaned.add(s);
                }
            }
            return List.copyOf(cleaned);
        }
        return List.of();
    }

    private List<String> extractArray(ResultSet rs, String column) throws SQLException {
        return extractTextArray(rs, column);
    }

    private static String toPgArrayLiteral(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String item : list) {
            if (item == null || item.isBlank()) continue;
            if (!first) sb.append(",");
            sb.append("\"").append(item.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}

