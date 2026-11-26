package com.app.refirm.inspector.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable sanitization and intent classification result for a precedent search query.
 */
public record SanitizedPrecedentQuery(
        String rawQuery,
        String sanitizedLexicalQuery,
        List<String> expandedTuples,
        QueryIntent intent,
        boolean isKnownItemDirectMatch,
        boolean isFormalCitation,
        String normalizedCitation,
        List<String> rawTuples,
        List<Set<String>> conceptGroups
) {
    public SanitizedPrecedentQuery {
        rawQuery = rawQuery != null ? rawQuery.trim().replaceAll("\\s+", " ") : "";
        sanitizedLexicalQuery = (sanitizedLexicalQuery != null && !sanitizedLexicalQuery.isBlank())
                ? sanitizedLexicalQuery.trim().replaceAll("\\s+", " ")
                : null;
        expandedTuples = expandedTuples != null ? List.copyOf(expandedTuples) : Collections.emptyList();
        intent = intent != null ? intent : QueryIntent.HYBRID_LEGAL;
        normalizedCitation = (normalizedCitation != null && !normalizedCitation.isBlank())
                ? normalizedCitation.trim().replaceAll("\\s+", " ")
                : null;
        rawTuples = rawTuples != null ? List.copyOf(rawTuples) : Collections.emptyList();

        if (conceptGroups != null && !conceptGroups.isEmpty()) {
            List<Set<String>> immutableGroups = new ArrayList<>(conceptGroups.size());
            for (Set<String> group : conceptGroups) {
                if (group != null && !group.isEmpty()) {
                    immutableGroups.add(Set.copyOf(group));
                }
            }
            conceptGroups = List.copyOf(immutableGroups);
        } else if (!expandedTuples.isEmpty()) {
            List<Set<String>> singletonGroups = new ArrayList<>(expandedTuples.size());
            for (String tuple : expandedTuples) {
                if (tuple != null && !tuple.isBlank()) {
                    singletonGroups.add(Set.of(tuple));
                }
            }
            conceptGroups = List.copyOf(singletonGroups);
        } else {
            conceptGroups = Collections.emptyList();
        }
    }

    public SanitizedPrecedentQuery(
            String rawQuery,
            String sanitizedLexicalQuery,
            List<String> expandedTuples,
            QueryIntent intent,
            boolean isKnownItemDirectMatch,
            boolean isFormalCitation,
            String normalizedCitation
    ) {
        this(rawQuery, sanitizedLexicalQuery, expandedTuples, intent,
                isKnownItemDirectMatch, isFormalCitation, normalizedCitation, null, null);
    }

    public SanitizedPrecedentQuery(
            String rawQuery,
            String sanitizedLexicalQuery,
            List<String> expandedTuples,
            QueryIntent intent,
            boolean isKnownItemDirectMatch
    ) {
        this(rawQuery, sanitizedLexicalQuery, expandedTuples, intent,
                isKnownItemDirectMatch, false, null, null, null);
    }

    public SanitizedPrecedentQuery(
            String rawQuery,
            String sanitizedLexicalQuery,
            List<String> expandedTuples,
            QueryIntent intent
    ) {
        this(rawQuery, sanitizedLexicalQuery, expandedTuples, intent,
                intent == QueryIntent.KNOWN_ITEM, false, null, null, null);
    }
}
