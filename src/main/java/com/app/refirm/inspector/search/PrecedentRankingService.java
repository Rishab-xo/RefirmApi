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

        // Find min and max RRF scores in the pool for min-max normalization
        double minRrf = Double.MAX_VALUE;
        double maxRrf = Double.MIN_VALUE;
        for (CandidateRecallItem item : candidates) {
            double r = item.rrfScore();
            if (r < minRrf) minRrf = r;
            if (r > maxRrf) maxRrf = r;
        }

        List<RankedPrecedent> rankedList = new ArrayList<>(candidates.size());

        for (CandidateRecallItem item : candidates) {
            PrecedentIndexRecord rec = item.record();

            // 1. Relevance Points (0 - 45 pts)
            double normRrf;
            if (maxRrf > minRrf) {
                normRrf = (item.rrfScore() - minRrf) / (maxRrf - minRrf);
            } else {
                normRrf = 1.0;
            }
            double relevancePts = normRrf * 45.0;

            boolean isDirectMatch = isDirectKnownItemMatch(rec, query);
            if (query.intent() == QueryIntent.KNOWN_ITEM && isDirectMatch) {
                relevancePts = Math.max(relevancePts, 42.0);
            }

            // Rank-1 Channel Anchor Floor (Fixes Dual-Channel Runner-Up Hijacking):
            // On non-browse queries, if candidate achieved bm25Rank == 1 OR vectorRank == 1, enforce floor 42.0
            boolean isBrowseQuery = (query.rawQuery() == null || query.rawQuery().isBlank())
                    && (query.expandedTuples() == null || query.expandedTuples().isEmpty());
            if (!isBrowseQuery) {
                Integer bm25Rank = item.channelRanks() != null ? item.channelRanks().get("BM25") : null;
                Integer vectorRank = item.channelRanks() != null ? item.channelRanks().get("VECTOR") : null;
                if ((bm25Rank != null && bm25Rank == 1) || (vectorRank != null && vectorRank == 1)) {
                    relevancePts = Math.max(relevancePts, 42.0);
                }
            }

            // 2. Statutory Specificity (0 - 20 pts)
            double statutePts = 0.0;
            List<String> queryTuples = query.expandedTuples();
            if (queryTuples != null && !queryTuples.isEmpty()) {
                Set<String> candTuples = new HashSet<>(rec.actSectionTuples());
                int matched = 0;
                for (String qt : queryTuples) {
                    if (candTuples.contains(qt)) {
                        matched++;
                    }
                }

                if (matched > 0) {
                    double coverage = (double) matched / queryTuples.size();
                    int unmatched = Math.max(0, candTuples.size() - matched);
                    // Omnibus dampener: penalizes precedents reciting 40+ unrelated provisions
                    double omnibusDampener = 1.0 / Math.log(Math.E + (unmatched * 0.25));
                    statutePts = 20.0 * coverage * omnibusDampener;

                    // Core headnote / proposition statute prominence bonus (+6.0 pts)
                    if (matchesHeadnoteSectionProminence(rec, queryTuples)) {
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

            // 6. Good-Law Guardrail (-35 pts for OVERRULED, -4 for DISTINGUISHED)
            double goodLawPenalty = 0.0;
            String status = rec.goodLawStatus();
            if ("POTENTIALLY_OVERRULED".equalsIgnoreCase(status)) {
                // BYPASS RULE: If user explicitly searched for this landmark case, do not penalize
                if (query.intent() == QueryIntent.KNOWN_ITEM && isDirectMatch) {
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

    private boolean targetsModernEnactment(SanitizedPrecedentQuery query) {
        if (query == null || query.expandedTuples() == null || query.expandedTuples().isEmpty()) {
            return false;
        }
        for (String tuple : query.expandedTuples()) {
            int colonIdx = tuple.indexOf(':');
            String act = colonIdx > 0 ? tuple.substring(0, colonIdx).trim().toUpperCase(Locale.ROOT) : tuple.toUpperCase(Locale.ROOT);
            if (MODERN_ACTS.contains(act)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesHeadnoteSectionProminence(PrecedentIndexRecord rec, List<String> queryTuples) {
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
                Pattern p = Pattern.compile("(?<!\\d)" + Pattern.quote(secNum) + "(?!\\d)");
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

        // Substring token match across party names (e.g. "adm jabalpur" or "arnesh kumar")
        String[] qTokens = q.split("[^a-z0-9]+");
        int matchCount = 0;
        int nonTrivialCount = 0;
        for (String tok : qTokens) {
            if (tok.length() >= 3 && !tok.equals("state") && !tok.equals("union") && !tok.equals("india")) {
                nonTrivialCount++;
                if (title.contains(tok)) {
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
