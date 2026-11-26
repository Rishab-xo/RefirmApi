package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Java Legal Reranker (Stage 2).
 * Scores each candidate in the RecallPool on a 0-100 scale across:
 *  - Relevance (0-45 pts): Min-max normalized RRF + Rank-1 Channel Anchor Floor (42 pts) + Known-Item floor boost
 *  - Statutory Specificity (0-20 pts): Tuple coverage * omnibus dampener + Headnote Prominence bonus (+6.0 pts)
 *  - Court Hierarchy & Authority (0-20 pts): Tier points (10/8/5/3) + log citation network (10 pts)
 *  - Recency (0-10 pts): Capped at 6.00 by default (up to 10.00 for modern post-2015 enactments)
 *  - Domain & Posture Alignment (0-10 pts): Zero neutral (0.00 when neither domain nor posture is passed)
 *  - Good-Law Guardrail: POTENTIALLY_OVERRULED penalty (-35 pts, bypassed on Known-Item direct search), DISTINGUISHED (-4 pts)
 *
 * Notice: Legacy +10xEnriched boost is completely eliminated.
 */
@Service
public class PrecedentRankingService {

    private static final int CURRENT_YEAR = Year.now().getValue();

    private static final Set<String> MODERN_ACTS = Set.of(
            "BNSS", "BNS", "BSA", "IBC", "CPA_2019", "RERA", "COMMERCIAL_COURTS_ACT"
    );

    /**
     * Reranks candidate items from Stage 1 recall into scored RankedPrecedent results.
     */
    public List<RankedPrecedent> rankCandidates(
            List<CandidateRecallItem> candidates,
            SanitizedPrecedentQuery query,
            PrecedentSearchCriteria criteria,
            RankingProfile profile
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        RankingProfile targetProfile = profile != null ? profile : RankingProfile.SEARCH_LIBRARY;

        // Find max RRF score in the pool for ratio-to-max normalization
        double maxRrf = 0.0;
        for (CandidateRecallItem item : candidates) {
            if (item.rrfScore() > maxRrf) {
                maxRrf = item.rrfScore();
            }
        }

        List<RankedPrecedent> rankedList = new ArrayList<>(candidates.size());

        boolean isBrowseQuery = (query.rawQuery() == null || query.rawQuery().isBlank())
                && (query.rawTuples() == null || query.rawTuples().isEmpty())
                && (query.expandedTuples() == null || query.expandedTuples().isEmpty());
        boolean hasRawTuples = query.rawTuples() != null && !query.rawTuples().isEmpty();

        for (CandidateRecallItem item : candidates) {
            PrecedentIndexRecord rec = item.record();

            // 1. Relevance Points (0 - 45 pts) via proportional ratio-to-max RRF
            double relevancePts = (maxRrf > 0.0 && item.rrfScore() > 0.0)
                    ? Math.min(45.0, (item.rrfScore() / maxRrf) * 45.0)
                    : 0.0;

            boolean isDirectMatch = isDirectKnownItemMatch(rec, query);
            if (isDirectMatch) {
                relevancePts = Math.max(relevancePts, 42.0);
            }

            // Modest lexical-precision bonus (+6.0, capped at 45.0) ONLY on tuple-free non-browse BM25 #1
            Integer bm25Rank = item.channelRanks() != null ? item.channelRanks().get("BM25") : null;
            if (!isBrowseQuery && !hasRawTuples && Integer.valueOf(1).equals(bm25Rank)) {
                relevancePts = Math.min(45.0, relevancePts + 6.0);
            }

            // 2. Statutory Specificity (0 - 20 pts) using concept-group coverage
            double statutePts = 0.0;
            List<Set<String>> conceptGroups = resolveConceptGroups(query);
            if (!conceptGroups.isEmpty()) {
                Set<String> candTuples = new HashSet<>(rec.actSectionTuples());
                int matchedGroups = 0;
                Set<String> unionQueryTuples = new LinkedHashSet<>();
                for (Set<String> group : conceptGroups) {
                    unionQueryTuples.addAll(group);
                    boolean groupMatched = false;
                    for (String candTuple : candTuples) {
                        if (group.contains(candTuple)) {
                            groupMatched = true;
                            break;
                        }
                    }
                    if (groupMatched) {
                        matchedGroups++;
                    }
                }

                if (matchedGroups > 0) {
                    int matchedTupleCount = 0;
                    for (String candTuple : candTuples) {
                        if (unionQueryTuples.contains(candTuple)) {
                            matchedTupleCount++;
                        }
                    }
                    double coverage = (double) matchedGroups / conceptGroups.size();
                    int unmatched = Math.max(0, candTuples.size() - matchedTupleCount);
                    // Softened omnibus dampener: avoids over-penalizing landmark guideline judgments
                    double omnibusDampener = 1.0 / Math.log(Math.E + (unmatched * 0.12));
                    statutePts = 20.0 * coverage * omnibusDampener;

                    // Core headnote / proposition statute prominence bonus (+6.0 pts)
                    if (matchesHeadnoteSectionProminence(rec, unionQueryTuples)) {
                        statutePts = Math.min(20.0, statutePts + 6.0);
                    }
                }
            }

            // 3. Court Hierarchy & Authority (0 - 20 pts)
            double tierPts;
            if ((rec.benchCount() != null && rec.benchCount() >= 5) || rec.courtTierWeight() >= 15) {
                tierPts = 10.0; // SC Constitution Bench
            } else if ("SC".equalsIgnoreCase(rec.courtCode()) || rec.courtTierWeight() >= 10) {
                tierPts = 8.0;  // Supreme Court
            } else if ("HC".equalsIgnoreCase(rec.courtCode()) || rec.courtTierWeight() >= 8) {
                tierPts = 5.0;  // High Court
            } else {
                tierPts = 3.0;  // Tribunal / District Court
            }

            double citeMetric = rec.citedByCount() + (0.25 * rec.hcCitedByCount());
            double citePts = Math.min(10.0, Math.log1p(citeMetric) * 1.8);
            double authorityPts = Math.min(20.0, tierPts + citePts);

            // 4. Recency (0 - 10 pts, capped at 6.00 by default, up to 10.00 only for modern post-2015 enactments)
            boolean isModernTarget = targetsModernEnactment(query);
            double maxRecency = isModernTarget ? 10.0 : 6.0;

            int age = Math.max(0, CURRENT_YEAR - rec.decisionYear());
            double baseRecency = maxRecency * Math.exp(-0.03 * age);
            if (isModernTarget && rec.decisionYear() >= 2016) {
                baseRecency = Math.min(10.0, baseRecency + 0.5); // Modern enactment nudge
            }
            double recencyPts = Math.min(maxRecency, baseRecency);

            // 5. Domain & Posture Alignment (0 - 10 pts, Zero neutral 0.00 when not supplied)
            double domainPts = 0.0;
            boolean hasDomainFilter = criteria != null && criteria.preferredDomain() != null && !criteria.preferredDomain().isBlank();
            if (hasDomainFilter) {
                String pref = criteria.preferredDomain().trim();
                if (rec.primaryDomain() != null && pref.equalsIgnoreCase(rec.primaryDomain().name())) {
                    domainPts = 5.0;
                } else if (rec.secondaryDomains() != null && rec.secondaryDomains().contains(pref)) {
                    domainPts = 2.5;
                }
            }

            double posturePts = 0.0;
            boolean hasPostureFilter = criteria != null && criteria.preferredPosture() != null && !criteria.preferredPosture().isBlank();
            if (hasPostureFilter) {
                String prefPosture = criteria.preferredPosture().trim();
                if (prefPosture.equalsIgnoreCase(rec.supportingPosture())) {
                    posturePts = 5.0;
                } else if ("BOTH_SIDES".equalsIgnoreCase(rec.supportingPosture())) {
                    posturePts = 3.0;
                }
            } else if (targetProfile == RankingProfile.ARGUMENT_PREP && hasDomainFilter) {
                if ("BOTH_SIDES".equalsIgnoreCase(rec.supportingPosture())) {
                    posturePts = 2.0;
                }
            }
            double domainPosturePts = Math.min(10.0, domainPts + posturePts);

            // 6. Good-Law Guardrail (-35 pts for POTENTIALLY_OVERRULED, -4 for DISTINGUISHED)
            double goodLawPenalty = 0.0;
            String status = rec.goodLawStatus();
            if ("POTENTIALLY_OVERRULED".equalsIgnoreCase(status)) {
                // BYPASS RULE: If user explicitly searched for this landmark case by cite/title, do not penalize
                if (isDirectMatch) {
                    goodLawPenalty = 0.0;
                } else {
                    goodLawPenalty = -35.0;
                }
            } else if ("DISTINGUISHED".equalsIgnoreCase(status)) {
                goodLawPenalty = -4.0;
            }

            // Total Score (0 - 100 clamped)
            double rawScore = relevancePts + statutePts + authorityPts + recencyPts + domainPosturePts + goodLawPenalty;
            double finalScore = Math.max(0.0, Math.min(100.0, Math.round(rawScore * 100.0) / 100.0));

            RankingScoreBreakdown breakdown = new RankingScoreBreakdown(
                    query.intent(),
                    item.rrfScore(),
                    round2(relevancePts),
                    round2(statutePts),
                    round2(authorityPts),
                    round2(recencyPts),
                    round2(domainPosturePts),
                    round2(goodLawPenalty),
                    finalScore,
                    item.channelRanks()
            );

            rankedList.add(new RankedPrecedent(rec, finalScore, breakdown));
        }

        // Sort descending by finalScore, then authorityPts, then decisionYear
        rankedList.sort((a, b) -> {
            int cmp = Double.compare(b.finalScore(), a.finalScore());
            if (cmp != 0) return cmp;
            int authCmp = Double.compare(b.breakdown().authorityPts(), a.breakdown().authorityPts());
            if (authCmp != 0) return authCmp;
            return Integer.compare(b.record().decisionYear(), a.record().decisionYear());
        });

        return rankedList;
    }

    private List<Set<String>> resolveConceptGroups(SanitizedPrecedentQuery query) {
        if (query == null) {
            return List.of();
        }
        if (query.conceptGroups() != null && !query.conceptGroups().isEmpty()) {
            return query.conceptGroups();
        }
        if (query.expandedTuples() != null && !query.expandedTuples().isEmpty()) {
            List<Set<String>> singletonGroups = new ArrayList<>(query.expandedTuples().size());
            for (String tuple : query.expandedTuples()) {
                if (tuple != null && !tuple.isBlank()) {
                    singletonGroups.add(Set.of(tuple));
                }
            }
            return singletonGroups;
        }
        return List.of();
    }

    private boolean targetsModernEnactment(SanitizedPrecedentQuery query) {
        if (query == null || query.rawTuples() == null || query.rawTuples().isEmpty()) {
            return false;
        }
        for (String tuple : query.rawTuples()) {
            int colonIdx = tuple.indexOf(':');
            String act = colonIdx > 0 ? tuple.substring(0, colonIdx).trim().toUpperCase(Locale.ROOT) : tuple.toUpperCase(Locale.ROOT);
            if (MODERN_ACTS.contains(act)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesHeadnoteSectionProminence(PrecedentIndexRecord rec, Collection<String> queryTuples) {
        if (queryTuples == null || queryTuples.isEmpty()) {
            return false;
        }

        StringBuilder sb = new StringBuilder();
        if (rec.twoLineRatio() != null) {
            sb.append(rec.twoLineRatio()).append(" ");
        }
        if (rec.legalPropositions() != null) {
            for (String prop : rec.legalPropositions()) {
                if (prop != null) {
                    sb.append(prop).append(" ");
                }
            }
        }
        String headnoteText = sb.toString();
        if (headnoteText.isBlank()) {
            return false;
        }

        for (String tuple : queryTuples) {
            int colonIdx = tuple.indexOf(':');
            String sec = colonIdx > 0 ? tuple.substring(colonIdx + 1).trim() : tuple.trim();
            Matcher m = Pattern.compile("^([0-9]+[A-Za-z]?)").matcher(sec);
            String secNum = m.find() ? m.group(1) : sec;

            if (!secNum.isBlank()) {
                String quoted = Pattern.quote(secNum);
                Pattern p = Pattern.compile(
                        "(?i)(?:(?:section|sec\\.?|s\\.?|article|art\\.?)\\s*" + quoted + "|(?<!\\d)" + quoted + ")(?!\\d)"
                );
                if (p.matcher(headnoteText).find()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isDirectKnownItemMatch(PrecedentIndexRecord rec, SanitizedPrecedentQuery query) {
        if (query == null) {
            return false;
        }

        // 1. Structured formal normalized citation match
        if (query.isFormalCitation() && query.normalizedCitation() != null) {
            String norm = query.normalizedCitation().replace(".", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (rec.primaryCitation() != null) {
                String prim = rec.primaryCitation().replace(".", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                if (prim.equals(norm) || prim.contains(norm) || norm.contains(prim)) {
                    return true;
                }
            }
            if (rec.equivalentCitations() != null) {
                for (String eq : rec.equivalentCitations()) {
                    String cleanEq = eq.replace(".", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                    if (cleanEq.equals(norm) || cleanEq.contains(norm) || norm.contains(cleanEq)) {
                        return true;
                    }
                }
            }
        }

        // 2. Lexical and raw query match
        String rawQuery = query.rawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }

        String q = rawQuery.toLowerCase(Locale.ROOT).trim();
        String title = rec.caseTitle() != null ? rec.caseTitle().toLowerCase(Locale.ROOT) : "";
        String primary = rec.primaryCitation() != null ? rec.primaryCitation().toLowerCase(Locale.ROOT) : "";

        if (!title.isBlank() && (title.contains(q) || q.contains(title))) {
            return true;
        }

        if (!primary.isBlank() && (primary.contains(q) || q.contains(primary))) {
            return true;
        }

        if (rec.equivalentCitations() != null) {
            for (String eq : rec.equivalentCitations()) {
                String eqLower = eq.toLowerCase(Locale.ROOT);
                if (eqLower.contains(q) || q.contains(eqLower)) {
                    return true;
                }
            }
        }

        // Whole-word token match across party names (e.g. "adm jabalpur", "a.k. gopalan", never "rajagopalan")
        Set<String> titleTokens = new HashSet<>(Arrays.asList(title.split("[^a-z0-9]+")));
        String[] qTokens = q.split("[^a-z0-9]+");
        int matchCount = 0;
        int nonTrivialCount = 0;
        for (String tok : qTokens) {
            if (tok.length() >= 3 && !tok.equals("state") && !tok.equals("union") && !tok.equals("india")) {
                nonTrivialCount++;
                if (titleTokens.contains(tok)) {
                    matchCount++;
                }
            }
        }

        return nonTrivialCount > 0 && matchCount == nonTrivialCount;
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
