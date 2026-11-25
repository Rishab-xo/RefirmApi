package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrator service powering 2-Stage Retrieval & Legal Reranking (Milestone 2B):
 *  - Stage 0: PrecedentQuerySanitizer (intent classification & statute stripping)
 *  - Stage 1: PrecedentRecallPort (multi-channel candidate recall Top-100 via RRF)
 *  - Stage 2: PrecedentRankingService (deterministic legal reranking on 0-100 scale)
 */
@Service
public class PrecedentHybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(PrecedentHybridSearchService.class);

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?i)(?:Section|Sec\\.?|S\\.?|Article|Art\\.?)\\s*([0-9]+[A-Za-z]?(?:\\([0-9A-Za-z]+\\))*)");

    private final PrecedentQuerySanitizer querySanitizer;
    private final PrecedentRecallPort recallPort;
    private final PrecedentRankingService rankingService;
    private final PariMateriaCrosswalkService crosswalkService;
    private final CorpusPrecedentRegistryService corpusRegistry;

    @Autowired
    public PrecedentHybridSearchService(
            PrecedentQuerySanitizer querySanitizer,
            PrecedentRecallPort recallPort,
            PrecedentRankingService rankingService,
            PariMateriaCrosswalkService crosswalkService,
            @Autowired(required = false) CorpusPrecedentRegistryService corpusRegistry
    ) {
        this.querySanitizer = querySanitizer;
        this.recallPort = recallPort;
        this.rankingService = rankingService;
        this.crosswalkService = crosswalkService;
        this.corpusRegistry = corpusRegistry;
    }

    public List<JudgmentAuthorityCard> search(
            String rawQuery,
            String domain,
            String court,
            String topic,
            String issue,
            int limit,
            RankingProfile profile,
            boolean debug
    ) {
        // 1. Extract statutory references from raw query text
        List<String> extractedTuples = new ArrayList<>();
        List<String> extractedSpans = new ArrayList<>();

        if (rawQuery != null && !rawQuery.isBlank()) {
            Matcher m = SECTION_PATTERN.matcher(rawQuery);
            while (m.find()) {
                String sec = m.group(1);
                int start = Math.max(0, m.start() - 30);
                int end = Math.min(rawQuery.length(), m.end() + 30);
                String snippet = rawQuery.substring(start, end);
                extractedSpans.add(rawQuery.substring(m.start(), m.end()));

                String act = crosswalkService.normalizeActCode(snippet);
                if (act.isEmpty() && domain != null && !domain.isBlank()) {
                    act = crosswalkService.normalizeActCode(domain);
                }
                if (!act.isEmpty()) {
                    String tuple = crosswalkService.toCanonicalTuple(act, sec);
                    if (!tuple.isEmpty()) {
                        extractedTuples.add(tuple);
                    }
                }
            }
        }

        // 2. Expand tuples into twin provisions (e.g. IPC <-> BNS, CrPC <-> BNSS)
        PariMateriaCrosswalkService.ExpandedTupleQuery expanded = crosswalkService.expandQueryTuples(extractedTuples);

        // 3. Stage 0: Query Sanitization and Intent Classification
        SanitizedPrecedentQuery sanitized = querySanitizer.sanitize(rawQuery, extractedSpans, expanded.allExpandedTuples());

        // 4. Build criteria
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(
                expanded.allExpandedTuples(),
                sanitized.sanitizedLexicalQuery(),
                (domain != null && !domain.isBlank()) ? domain : null,
                (topic != null && !topic.isBlank()) ? topic : null,
                (issue != null && !issue.isBlank()) ? issue : null,
                (court != null && !court.isBlank()) ? court : null,
                limit > 0 ? limit : 20
        );

        // 5. Stage 1: Candidate Recall (Top 100 via RRF)
        RecallPool pool;
        try {
            pool = recallPort.recallCandidates(sanitized, criteria);
        } catch (Exception e) {
            log.warn("Recall failure in PrecedentHybridSearchService: {}", e.getMessage());
            pool = RecallPool.empty();
        }

        // 6. Stage 2: Deterministic Legal Reranker (0-100 scale)
        List<RankedPrecedent> ranked = rankingService.rankCandidates(pool.items(), sanitized, criteria, profile);

        int maxResults = limit > 0 ? limit : 20;
        List<JudgmentAuthorityCard> cards = new ArrayList<>();

        for (int i = 0; i < Math.min(ranked.size(), maxResults); i++) {
            RankedPrecedent rp = ranked.get(i);
            PrecedentIndexRecord rec = rp.record();
            cards.add(new JudgmentAuthorityCard(
                    rec.authorityId(),
                    rec.caseTitle(),
                    rec.courtName(),
                    rec.judgmentDate(),
                    rec.primaryCitation(),
                    rec.equivalentCitations(),
                    "VERIFIED_CORPUS",
                    null,
                    null,
                    null,
                    rec.twoLineRatio(),
                    rec.legalPropositions(),
                    false,
                    rec.citedByCount(),
                    rec.actSectionTuples(),
                    rec.decisionYear(),
                    rec.goodLawStatus(),
                    rec.overruledByAuthorityId(),
                    debug ? rp.breakdown() : null
            ));
        }

        return cards;
    }

    public List<JudgmentAuthorityCard> search(
            String rawQuery,
            String domain,
            String court,
            String topic,
            String issue,
            int limit
    ) {
        return search(rawQuery, domain, court, topic, issue, limit, RankingProfile.SEARCH_LIBRARY, false);
    }
}
