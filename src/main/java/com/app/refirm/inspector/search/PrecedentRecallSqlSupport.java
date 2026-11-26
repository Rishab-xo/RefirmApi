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

    static List<String> mergeTrigramHits(List<String> fullQueryHits, List<String> perTokenHits) {
        if ((fullQueryHits == null || fullQueryHits.isEmpty())
                && (perTokenHits == null || perTokenHits.isEmpty())) {
            return Collections.emptyList();
        }

        Set<String> seen = new LinkedHashSet<>();
        if (fullQueryHits != null) {
            for (String id : fullQueryHits) {
                if (id != null && seen.size() < MAX_TRIGRAM_LIMIT) {
                    seen.add(id);
                }
            }
        }
        if (perTokenHits != null) {
            for (String id : perTokenHits) {
                if (id != null && seen.size() < MAX_TRIGRAM_LIMIT) {
                    seen.add(id);
                }
            }
        }
        return List.copyOf(seen);
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

        String sql = "SELECT lpi.authority_id, "
                + "(" + String.join(" + ", groupSumTerms) + ") AS match_count "
                + "FROM legal_precedent_index lpi "
                + "WHERE lpi.act_section_tuples && CAST(:expandedTuples AS TEXT[]) "
                + "AND (CAST(:courtFilter AS VARCHAR) IS NULL OR lpi.court_code = CAST(:courtFilter AS VARCHAR)) "
                + "AND (CAST(:topicFilter AS VARCHAR) IS NULL OR CAST(:topicFilter AS VARCHAR) = ANY(lpi.topic_slugs)) "
                + "AND (CAST(:issueFilter AS VARCHAR) IS NULL OR CAST(:issueFilter AS VARCHAR) = ANY(lpi.issue_tags)) "
                + "ORDER BY match_count DESC, COALESCE(lpi.court_tier_weight, 0) DESC, COALESCE(lpi.cited_by_count, 0) DESC "
                + "LIMIT 80";

        return new SqlWithParams(sql, params);
    }
}
