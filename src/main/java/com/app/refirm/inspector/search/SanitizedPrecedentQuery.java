package com.app.refirm.inspector.search;

import java.util.Collections;
import java.util.List;

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
        String normalizedCitation
) {
    public SanitizedPrecedentQuery {
        rawQuery = rawQuery != null ? rawQuery.trim() : "";
        sanitizedLexicalQuery = (sanitizedLexicalQuery != null && !sanitizedLexicalQuery.isBlank())
                ? sanitizedLexicalQuery.trim()
                : null;
        expandedTuples = expandedTuples != null ? List.copyOf(expandedTuples) : Collections.emptyList();
        intent = intent != null ? intent : QueryIntent.HYBRID_LEGAL;
        normalizedCitation = (normalizedCitation != null && !normalizedCitation.isBlank())
                ? normalizedCitation.trim()
                : null;
    }

    public SanitizedPrecedentQuery(
            String rawQuery,
            String sanitizedLexicalQuery,
            List<String> expandedTuples,
            QueryIntent intent,
            boolean isKnownItemDirectMatch
    ) {
        this(rawQuery, sanitizedLexicalQuery, expandedTuples, intent, isKnownItemDirectMatch, false, null);
    }

    public SanitizedPrecedentQuery(
            String rawQuery,
            String sanitizedLexicalQuery,
            List<String> expandedTuples,
            QueryIntent intent
    ) {
        this(rawQuery, sanitizedLexicalQuery, expandedTuples, intent, intent == QueryIntent.KNOWN_ITEM, false, null);
    }
}
