package com.app.refirm.inspector.search;

import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java, stateless query sanitizer and intent classifier (Stage 0).
 * Strips statutory references from the lexical query to prevent ts_rank zero-out,
 * classifies user query intent, normalizes structured Indian reporter citations,
 * and preserves raw input for trigram matching.
 */
@Component
public class PrecedentQuerySanitizer {

    private static final Pattern FORMAL_SCC_PATTERN = Pattern.compile(
            "(?i)\\(?(\\d{4})\\)?\\s+(\\d+)\\s+S\\.?C\\.?C\\.?(?:\\s*\\([A-Za-z&]+\\))?\\s+(\\d+)"
    );

    private static final Pattern FORMAL_AIR_PATTERN = Pattern.compile(
            "(?i)A\\.?I\\.?R\\.?\\s+(\\d{4})\\s+S\\.?C\\.?\\s+(\\d+)"
    );

    private static final Pattern FORMAL_SCR_PATTERN = Pattern.compile(
            "(?i)\\[?(\\d{4})\\]?\\s+(?:(SUPP\\.?)\\s*)?(?:[\\(\\[]?(\\d+)[\\)\\]]?\\s*)?S\\.?C\\.?R\\.?\\s+(\\d+)"
    );

    private static final Pattern FORMAL_INSC_PATTERN = Pattern.compile(
            "(?i)(\\d{4})\\s+I\\.?N\\.?S\\.?C\\.?\\s+(\\d+)"
    );

    private static final Pattern GENERAL_REPORTER_PATTERN = Pattern.compile(
            "\\b(?:\\(?\\d{4}\\)?\\s*\\d*\\s*(?:SCC|AIR|S\\.?C\\.?R\\.|INSC|SCALE)\\s*\\d*|\\b(?:SCC|AIR|S\\.?C\\.?R\\.|INSC)\\b)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern VS_PATTERN = Pattern.compile(
            "\\b(?:v\\.?|vs\\.?|versus)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STATUTE_PHRASE_PATTERN = Pattern.compile(
            "(?i)(?:under\\s+)?(?:section|sec\\.?|s\\.?|article|art\\.?|order|rule)\\s*[0-9]+[A-Za-z]?(?:\\s*\\([0-9A-Za-z]+\\))*(?:\\s+(?:of|read\\s+with|r/w)\\s+(?:the\\s+)?[A-Za-z\\s]+)?"
    );

    private static final Pattern ACT_NAMES_PATTERN = Pattern.compile(
            "(?i)\\b(?:cr\\.?p\\.?c\\.?|c\\.?p\\.?c\\.?|i\\.?p\\.?c\\.?|n\\.?i\\.?\\s*act|negotiable\\s+instruments\\s+act|rera|ibc|arbitration(?:\\s+and\\s+conciliation)?(?:\\s+act)?|constitution(?:\\s+of\\s+india)?|evidence\\s+act|companies\\s+act|contract\\s+act|motor\\s+vehicles\\s+act|pocso|ndps|pmla|sarfaesi)\\b"
    );

    private static final Set<String> GENERIC_LEGAL_TERMS = Set.of(
            "can", "whether", "bail", "quashing", "anticipatory", "guidelines",
            "procedure", "limitation", "maintainability", "interim", "stay",
            "writ", "appeal", "revision", "validity", "grounds", "scope",
            "mandatory", "directory", "delay", "condonation", "default",
            "discharge", "framing", "charge", "cognizance", "arrest", "custody"
    );

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "for", "on", "to", "by", "with",
            "at", "from", "is", "are", "was", "were", "be", "been", "under", "as", "it",
            "its", "that", "this", "which", "no", "not"
    );

    private static final Set<String> INSTITUTIONAL_LITIGANT_NOUNS = Set.of(
            "state", "union", "india", "government", "bank", "insurance", "corporation",
            "board", "commission", "authority", "company", "limited", "municipal",
            "police", "court", "council", "ministry", "department"
    );

    private static final int MAX_PER_TOKEN_TITLE_QUERY_WORDS = 7;
    private static final int MAX_DISTINCTIVE_TITLE_TOKENS = 5;
    private static final int MIN_DISTINCTIVE_TOKEN_LENGTH = 5;

    /**
     * Sanitizes raw query and classifies query intent into KNOWN_ITEM, STATUTE_ONLY, or HYBRID_LEGAL,
     * preserving user-typed rawTuples and crosswalk conceptGroups separately.
     */
    public SanitizedPrecedentQuery sanitize(
            String rawQuery,
            List<String> extractedStatuteSpans,
            PariMateriaCrosswalkService.ExpandedTupleQuery expandedTupleQuery
    ) {
        List<String> rawTuples = (expandedTupleQuery != null && expandedTupleQuery.primaryTuples() != null)
                ? expandedTupleQuery.primaryTuples()
                : Collections.emptyList();
        List<String> expandedTuples = (expandedTupleQuery != null && expandedTupleQuery.allExpandedTuples() != null)
                ? expandedTupleQuery.allExpandedTuples()
                : Collections.emptyList();
        List<Set<String>> conceptGroups = (expandedTupleQuery != null && expandedTupleQuery.conceptGroups() != null)
                ? expandedTupleQuery.conceptGroups()
                : Collections.emptyList();
        return sanitizeInternal(rawQuery, extractedStatuteSpans, expandedTuples, rawTuples, conceptGroups);
    }

    /**
     * Backward-compatible overload treating supplied tuples directly as both raw and singleton concept groups.
     */
    public SanitizedPrecedentQuery sanitize(
            String rawQuery,
            List<String> extractedStatuteSpans,
            List<String> expandedActSectionTuples
    ) {
        List<String> tuples = expandedActSectionTuples != null ? expandedActSectionTuples : Collections.emptyList();
        return sanitizeInternal(rawQuery, extractedStatuteSpans, tuples, tuples, null);
    }

    private SanitizedPrecedentQuery sanitizeInternal(
            String rawQuery,
            List<String> extractedStatuteSpans,
            List<String> expandedActSectionTuples,
            List<String> rawTuples,
            List<Set<String>> conceptGroups
    ) {
        String query = rawQuery != null ? rawQuery.trim().replaceAll("\\s+", " ") : "";
        List<String> tuples = expandedActSectionTuples != null ? expandedActSectionTuples : Collections.emptyList();
        List<String> userTuples = rawTuples != null ? rawTuples : Collections.emptyList();
        boolean hasTuples = !tuples.isEmpty() || !userTuples.isEmpty()
                || (extractedStatuteSpans != null && !extractedStatuteSpans.isEmpty());

        if (query.isEmpty()) {
            QueryIntent intent = hasTuples ? QueryIntent.STATUTE_ONLY : QueryIntent.HYBRID_LEGAL;
            return new SanitizedPrecedentQuery("", null, tuples, intent, false, false, null, userTuples, conceptGroups);
        }

        // 1. Structured Formal Reporter Citation Detection & Normalization
        FormalCitationMatch formalMatch = detectFormalCitation(query);
        if (formalMatch.isFormal()) {
            // Suppress lexical query in BM25 to avoid number collision; set intent to KNOWN_ITEM
            return new SanitizedPrecedentQuery(
                    query,
                    null,
                    tuples,
                    QueryIntent.KNOWN_ITEM,
                    true,
                    true,
                    formalMatch.normalizedCitation(),
                    userTuples,
                    conceptGroups
            );
        }

        // 2. Explicit KNOWN_ITEM intent ONLY on structural markers (reporter cues or v./vs./versus)
        boolean matchesReporter = GENERAL_REPORTER_PATTERN.matcher(query).find();
        boolean matchesVs = VS_PATTERN.matcher(query).find();
        boolean isKnownItem = matchesReporter || matchesVs;

        // 3. Sanitize lexical query by stripping statutory tokens
        String stripped = stripStatutoryPhrases(query, tuples, extractedStatuteSpans);
        String cleanedLexical = cleanLexicalTokens(stripped);

        // 4. Check STATUTE_ONLY intent
        if (!isKnownItem && hasTuples) {
            if (cleanedLexical == null || isOnlyGenericWords(cleanedLexical)) {
                return new SanitizedPrecedentQuery(query, null, tuples, QueryIntent.STATUTE_ONLY,
                        false, false, null, userTuples, conceptGroups);
            }
        }

        if (isKnownItem) {
            return new SanitizedPrecedentQuery(query, cleanedLexical, tuples, QueryIntent.KNOWN_ITEM,
                    true, false, null, userTuples, conceptGroups);
        }

        return new SanitizedPrecedentQuery(query, cleanedLexical, tuples, QueryIntent.HYBRID_LEGAL,
                false, false, null, userTuples, conceptGroups);
    }

    /**
     * Extracts up to 5 distinctive non-numeric title tokens (len >= 5) strictly from
     * sanitizedLexicalQuery when the query is not a formal citation and has <= 7 words.
     */
    public static List<String> extractDistinctiveTitleTokens(SanitizedPrecedentQuery query) {
        if (query == null || query.isFormalCitation()) {
            return List.of();
        }
        String canonicalRaw = query.rawQuery();
        if (canonicalRaw == null || canonicalRaw.isBlank()) {
            return List.of();
        }
        if (canonicalRaw.split("\\s+").length > MAX_PER_TOKEN_TITLE_QUERY_WORDS) {
            return List.of();
        }

        String lexical = query.sanitizedLexicalQuery();
        if (lexical == null || lexical.isBlank()) {
            return List.of();
        }

        String[] rawTokens = lexical.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        Set<String> distinctive = new LinkedHashSet<>();
        for (String tok : rawTokens) {
            if (tok.length() < MIN_DISTINCTIVE_TOKEN_LENGTH) {
                continue;
            }
            boolean hasLetter = false;
            for (int i = 0; i < tok.length(); i++) {
                if (Character.isLetter(tok.charAt(i))) {
                    hasLetter = true;
                    break;
                }
            }
            if (!hasLetter) {
                continue;
            }
            if (STOPWORDS.contains(tok)
                    || GENERIC_LEGAL_TERMS.contains(tok)
                    || INSTITUTIONAL_LITIGANT_NOUNS.contains(tok)) {
                continue;
            }
            distinctive.add(tok);
            if (distinctive.size() >= MAX_DISTINCTIVE_TITLE_TOKENS) {
                break;
            }
        }
        return distinctive.isEmpty() ? List.of() : List.copyOf(distinctive);
    }

    private FormalCitationMatch detectFormalCitation(String query) {
        if (query == null || query.isBlank()) {
            return new FormalCitationMatch(false, null);
        }

        // SCC: e.g. (2014) 8 SCC 273 or 2014 8 S.C.C. 273
        Matcher mScc = FORMAL_SCC_PATTERN.matcher(query);
        if (mScc.find()) {
            String norm = String.format("(%s) %s SCC %s", mScc.group(1), mScc.group(2), mScc.group(3));
            return new FormalCitationMatch(true, norm);
        }

        // AIR: e.g. AIR 1992 SC 604 or A.I.R. 1992 S.C. 604
        Matcher mAir = FORMAL_AIR_PATTERN.matcher(query);
        if (mAir.find()) {
            String norm = String.format("AIR %s SC %s", mAir.group(1), mAir.group(2));
            return new FormalCitationMatch(true, norm);
        }

        // S.C.R.: e.g. [2014] 8 SCR 128 or [1995] SUPP. 6 S.C.R. 556
        Matcher mScr = FORMAL_SCR_PATTERN.matcher(query);
        if (mScr.find()) {
            String supp = mScr.group(2) != null ? "SUPP. " : "";
            String vol = mScr.group(3) != null ? mScr.group(3) + " " : "";
            String norm = String.format("[%s] %s%sSCR %s", mScr.group(1), supp, vol, mScr.group(4)).replaceAll("\\s+", " ").trim();
            return new FormalCitationMatch(true, norm);
        }

        // INSC: e.g. 2021 INSC 284 or 2014 I.N.S.C. 463
        Matcher mInsc = FORMAL_INSC_PATTERN.matcher(query);
        if (mInsc.find()) {
            String norm = String.format("%s INSC %s", mInsc.group(1), mInsc.group(2));
            return new FormalCitationMatch(true, norm);
        }

        return new FormalCitationMatch(false, null);
    }

    private boolean isOnlyGenericWords(String text) {
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        int meaningfulCount = 0;
        for (String token : tokens) {
            if (token.isBlank() || STOPWORDS.contains(token)) {
                continue;
            }
            if (!GENERIC_LEGAL_TERMS.contains(token)) {
                meaningfulCount++;
            }
        }
        return meaningfulCount == 0;
    }

    private String stripStatutoryPhrases(String query, List<String> tuples, List<String> statuteSpans) {
        String text = query;

        // Strip extracted statute spans if provided
        if (statuteSpans != null) {
            for (String span : statuteSpans) {
                if (span != null && !span.isBlank()) {
                    text = text.replace(span, " ");
                }
            }
        }

        // Strip regex statutory patterns
        text = STATUTE_PHRASE_PATTERN.matcher(text).replaceAll(" ");
        text = ACT_NAMES_PATTERN.matcher(text).replaceAll(" ");

        // Strip bare section numbers that correspond to known tuples (e.g. "438")
        if (tuples != null) {
            for (String tuple : tuples) {
                int colonIdx = tuple.indexOf(':');
                if (colonIdx > 0 && colonIdx < tuple.length() - 1) {
                    String sec = tuple.substring(colonIdx + 1).trim();
                    if (!sec.isEmpty()) {
                        text = text.replaceAll("(?i)\\b" + Pattern.quote(sec) + "\\b", " ");
                    }
                }
            }
        }

        return text;
    }

    private String cleanLexicalTokens(String text) {
        if (text == null) {
            return null;
        }

        // Remove special punctuation, normalize whitespace
        String cleaned = text.replaceAll("[^a-zA-Z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) {
            return null;
        }

        // Count non-stopword characters
        String[] tokens = cleaned.split("\\s+");
        int nonStopwordCharCount = 0;
        int nonStopwordCount = 0;

        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (!STOPWORDS.contains(lower) && lower.length() >= 2) {
                nonStopwordCharCount += lower.length();
                nonStopwordCount++;
            }
        }

        if (nonStopwordCharCount < 2 || nonStopwordCount == 0) {
            return null;
        }

        return cleaned;
    }

    public static record FormalCitationMatch(boolean isFormal, String normalizedCitation) {}
}
