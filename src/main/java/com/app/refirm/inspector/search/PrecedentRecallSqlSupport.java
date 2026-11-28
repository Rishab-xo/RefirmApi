package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.*;

/**
 * Shared parameterized SQL builders and result-merging helpers for Stage 1 recall adapters
 * (both NeonLakebaseRecallAdapter and StandardPostgresRecallAdapter).
 */
final class PrecedentRecallSqlSupport {

    static final int MAX_TRIGRAM_LIMIT = 40;
    static final int MAX_PER_TOKEN_LIMIT = 40;
    static final int MAX_CONCEPT_GROUPS = 10;

    private PrecedentRecallSqlSupport() {}

    record SqlWithParams(String sql, MapSqlParameterSource params) {}

    static SqlWithParams buildPerTokenTrigramQuery(
            List<String> tokens,
            PrecedentSearchCriteria criteria
    ) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }

        int count = Math.min(tokens.size(), 5);
        List<String> predicates = new ArrayList<>(count);
        List<String> sumTerms = new ArrayList<>(count);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

        for (int i = 0; i < count; i++) {
            String paramName = "t" + i;
            params.addValue(paramName, tokens.get(i));
            predicates.add(":" + paramName + " <% lpi.case_title");
            sumTerms.add("(CASE WHEN :" + paramName + " <% lpi.case_title THEN 1 ELSE 0 END)");
        }

        String sql = "SELECT lpi.authority_id FROM legal_precedent_index lpi "
                + "WHERE (" + String.join(" OR ", predicates) + ") "
                + "AND (CAST(:courtFilter AS VARCHAR) IS NULL OR lpi.court_code = CAST(:courtFilter AS VARCHAR)) "
                + "AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(lpi.topic_slugs)) "
                + "AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(lpi.issue_tags)) "
                + "ORDER BY (" + String.join(" + ", sumTerms) + ") DESC, "
                + "COALESCE(lpi.cited_by_count, 0) DESC, "
                + "COALESCE(lpi.court_tier_weight, 0) DESC "
                + "LIMIT " + MAX_PER_TOKEN_LIMIT;

        return new SqlWithParams(sql, params);
    }

    static boolean isTrueBrowseQuery(SanitizedPrecedentQuery query) {
        return query != null
                && (query.rawQuery() == null || query.rawQuery().isBlank())
                && (query.expandedTuples() == null || query.expandedTuples().isEmpty());
    }

    static List<String> mergeTrigramHits(List<String> fullQueryHits, List<String> perTokenHits) {
        if ((fullQueryHits == null || fullQueryHits.isEmpty())
                && (perTokenHits == null || perTokenHits.isEmpty())) {
            return Collections.emptyList();
        }

        List<String> dedupPerToken = deduplicateIds(perTokenHits);
        List<String> dedupFull = deduplicateIds(fullQueryHits);

        Map<String, Integer> fullRankById = new HashMap<>(dedupFull.size());
        for (int i = 0; i < dedupFull.size(); i++) {
            fullRankById.put(dedupFull.get(i), i);
        }

        // 1. Intersection first: ordered by perTokenHits rank (tie-break fullQueryHits rank)
        Set<String> seen = new LinkedHashSet<>();
        for (String id : dedupPerToken) {
            if (fullRankById.containsKey(id) && seen.size() < MAX_TRIGRAM_LIMIT) {
                seen.add(id);
            }
        }

        // 2. Remaining hits: alternate 1:1 between perTokenHits and fullQueryHits
        List<String> remPerToken = new ArrayList<>(dedupPerToken.size());
        for (String id : dedupPerToken) {
            if (!seen.contains(id)) {
                remPerToken.add(id);
            }
        }
        List<String> remFull = new ArrayList<>(dedupFull.size());
        for (String id : dedupFull) {
            if (!seen.contains(id)) {
                remFull.add(id);
            }
        }

        int maxRem = Math.max(remPerToken.size(), remFull.size());
        for (int i = 0; i < maxRem && seen.size() < MAX_TRIGRAM_LIMIT; i++) {
            if (i < remPerToken.size()) {
                seen.add(remPerToken.get(i));
            }
            if (i < remFull.size() && seen.size() < MAX_TRIGRAM_LIMIT) {
                seen.add(remFull.get(i));
            }
        }
        return List.copyOf(seen);
    }

    private static List<String> deduplicateIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> dedup = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                dedup.add(id);
            }
        }
        return List.copyOf(dedup);
    }

    static List<Set<String>> resolveConceptGroups(
            SanitizedPrecedentQuery query,
            PrecedentSearchCriteria criteria
    ) {
        if (query != null && query.conceptGroups() != null && !query.conceptGroups().isEmpty()) {
            return query.conceptGroups();
        }
        if (criteria != null && criteria.expandedActSectionTuples() != null && !criteria.expandedActSectionTuples().isEmpty()) {
            List<Set<String>> singletonGroups = new ArrayList<>(criteria.expandedActSectionTuples().size());
            for (String t : criteria.expandedActSectionTuples()) {
                if (t != null && !t.isBlank()) {
                    singletonGroups.add(Set.of(t));
                }
            }
            return singletonGroups;
        }
        return List.of();
    }

    static SqlWithParams buildConceptGroupTupleQuery(
            SanitizedPrecedentQuery query,
            PrecedentSearchCriteria criteria
    ) {
        List<Set<String>> groups = resolveConceptGroups(query, criteria);
        if (groups.isEmpty()) {
            return null;
        }

        int groupCount = Math.min(groups.size(), MAX_CONCEPT_GROUPS);
        Set<String> unionTuples = new LinkedHashSet<>();
        if (criteria != null && criteria.expandedActSectionTuples() != null) {
            unionTuples.addAll(criteria.expandedActSectionTuples());
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("courtFilter", criteria != null ? criteria.courtCode() : null)
                .addValue("topicFilter", criteria != null ? criteria.topicSlug() : null)
                .addValue("issueFilter", criteria != null ? criteria.issueTag() : null);

        List<String> groupSumTerms = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            Set<String> group = groups.get(i);
            unionTuples.addAll(group);
            String paramName = "g" + i;
            params.addValue(paramName, group.toArray(new String[0]));
            groupSumTerms.add("(CASE WHEN lpi.act_section_tuples && CAST(:" + paramName + " AS TEXT[]) THEN 1 ELSE 0 END)");
        }

        if (unionTuples.isEmpty()) {
            return null;
        }
        params.addValue("expandedTuples", unionTuples.toArray(new String[0]));

        List<String> rawTuples = (query != null && query.rawTuples() != null) ? query.rawTuples() : List.of();
        Set<String> dedupRaw = new LinkedHashSet<>();
        for (String r : rawTuples) {
            if (r != null && !r.isBlank() && dedupRaw.size() < MAX_CONCEPT_GROUPS) {
                dedupRaw.add(r.trim());
            }
        }
        String rawSumExpr;
        if (dedupRaw.isEmpty()) {
            rawSumExpr = "0";
        } else {
            List<String> rawTerms = new ArrayList<>(dedupRaw.size());
            int rIdx = 0;
            for (String raw : dedupRaw) {
                String pName = "r" + rIdx++;
                params.addValue(pName, raw);
                rawTerms.add("(CASE WHEN CAST(:" + pName + " AS TEXT) = ANY(lpi.act_section_tuples) THEN 1 ELSE 0 END)");
            }
            rawSumExpr = "(" + String.join(" + ", rawTerms) + ")";
        }

        String sql = "SELECT lpi.authority_id, "
                + "(" + String.join(" + ", groupSumTerms) + ") AS concept_match_count, "
                + rawSumExpr + " AS raw_exact_match_count "
                + "FROM legal_precedent_index lpi "
                + "WHERE lpi.act_section_tuples && CAST(:expandedTuples AS TEXT[]) "
                + "AND (CAST(:courtFilter AS VARCHAR) IS NULL OR lpi.court_code = CAST(:courtFilter AS VARCHAR)) "
                + "AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(lpi.topic_slugs)) "
                + "AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(lpi.issue_tags)) "
                + "ORDER BY concept_match_count DESC, raw_exact_match_count DESC, COALESCE(lpi.court_tier_weight, 0) DESC, COALESCE(lpi.cited_by_count, 0) DESC "
                + "LIMIT 80";

        return new SqlWithParams(sql, params);
    }

    static SqlWithParams buildBrowseQuery(PrecedentSearchCriteria criteria) {
        if (criteria == null) {
            return null;
        }
        boolean hasAnyFilter = (criteria.courtCode() != null && !criteria.courtCode().isBlank())
                || (criteria.topicSlug() != null && !criteria.topicSlug().isBlank())
                || (criteria.issueTag() != null && !criteria.issueTag().isBlank());
        if (!hasAnyFilter) {
            return null;
        }

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

        return new SqlWithParams(sql, params);
    }
}
