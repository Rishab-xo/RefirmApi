package com.app.refirm.inspector.service;

import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.*;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.search.PrecedentHybridSearchService;
import com.app.refirm.inspector.search.RankingProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service powering the Standalone Judgments Library (Wireframe / UI:
 * Topics directory, live NARROW BY ISSUE counts, statutory Pari Materia banners, and 2-stage hybrid search).
 */
@Service
public class JudgmentLibraryService {

    private static final Logger log = LoggerFactory.getLogger(JudgmentLibraryService.class);

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?i)(?:Section|Sec\\.?|S\\.?|Article|Art\\.?)\\s*([0-9]+[A-Za-z]?(?:\\([0-9A-Za-z]+\\))*)");

    private final PariMateriaCrosswalkService crosswalkService;
    private final PrecedentIndexRepository precedentIndexRepository;
    private final CorpusPrecedentRegistryService corpusRegistry;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final PrecedentHybridSearchService precedentHybridSearchService;

    @Autowired
    public JudgmentLibraryService(
            PariMateriaCrosswalkService crosswalkService,
            @Autowired(required = false) PrecedentIndexRepository precedentIndexRepository,
            @Autowired(required = false) CorpusPrecedentRegistryService corpusRegistry,
            @Autowired(required = false) NamedParameterJdbcTemplate namedJdbcTemplate,
            @Autowired(required = false) PrecedentHybridSearchService precedentHybridSearchService
    ) {
        this.crosswalkService = crosswalkService;
        this.precedentIndexRepository = precedentIndexRepository;
        this.corpusRegistry = corpusRegistry;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.precedentHybridSearchService = precedentHybridSearchService;
    }

    /**
     * GET /api/judgments/topics:
     * Returns all taxonomy topics with live total judgment counts and Supreme Court counts.
     */
    @Transactional(readOnly = true)
    public List<TopicSummaryItem> getTopicSummaries() {
        Collection<PariMateriaCrosswalkService.TopicDefinition> topics = crosswalkService.getAllTopics();
        List<TopicSummaryItem> summaries = new ArrayList<>();

        for (PariMateriaCrosswalkService.TopicDefinition topic : topics) {
            long totalCount = 0;
            long scCount = 0;

            if (namedJdbcTemplate != null) {
                try {
                    MapSqlParameterSource params = new MapSqlParameterSource("topicSlug", topic.topicSlug());
                    Long total = namedJdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM legal_precedent_index WHERE :topicSlug = ANY(topic_slugs)",
                            params, Long.class);
                    Long sc = namedJdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM legal_precedent_index WHERE :topicSlug = ANY(topic_slugs) AND court_code = 'SC'",
                            params, Long.class);
                    totalCount = total != null ? total : 0;
                    scCount = sc != null ? sc : 0;
                } catch (Exception e) {
                    log.warn("Could not query DB topic counts for {}: {}", topic.topicSlug(), e.getMessage());
                }
            }

            // In-memory fallback if DB returned 0 or was offline
            if (totalCount == 0 && corpusRegistry != null) {
                List<CorpusPrecedentEntry> all = corpusRegistry.getAllPrecedents();
                for (CorpusPrecedentEntry entry : all) {
                    var classification = crosswalkService.classifyJudgmentTaxonomy(
                            extractEntryTuples(entry),
                            entry.summary() + " " + String.join(" ", entry.keyPropositions())
                    );
                    if (classification.topicSlugs().contains(topic.topicSlug())) {
                        totalCount++;
                        if (entry.courtName() != null && entry.courtName().toLowerCase(Locale.ROOT).contains("supreme")) {
                            scCount++;
                        }
                    }
                }
            }

            summaries.add(new TopicSummaryItem(
                    topic.topicSlug(),
                    topic.title(),
                    topic.subtitle(),
                    topic.primaryDomain(),
                    totalCount,
                    scCount
            ));
        }

        return summaries;
    }

    /**
     * GET /api/judgments/topics/{topicSlug}:
     * Returns topic metadata, issue pills with live counts, and ranked precedent cards.
     */
    @Transactional(readOnly = true)
    public TopicDetailResponse getTopicDetail(String topicSlug, String issue, String court, String q, int limit) {
        PariMateriaCrosswalkService.TopicDefinition topic = crosswalkService.getTopicBySlug(topicSlug)
                .orElseThrow(() -> new IllegalArgumentException("Precedent topic not found: " + topicSlug));

        List<IssuePillCount> pillCounts = new ArrayList<>();
        if (topic.issuePills() != null) {
            for (PariMateriaCrosswalkService.IssuePillDefinition pill : topic.issuePills()) {
                long count = 0;
                if (namedJdbcTemplate != null) {
                    try {
                        MapSqlParameterSource params = new MapSqlParameterSource()
                                .addValue("topicSlug", topicSlug)
                                .addValue("issueSlug", pill.issueSlug());
                        Long c = namedJdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM legal_precedent_index WHERE :topicSlug = ANY(topic_slugs) AND :issueSlug = ANY(issue_tags)",
                                params, Long.class);
                        count = c != null ? c : 0;
                    } catch (Exception e) {
                        log.warn("Could not query issue pill count for {}: {}", pill.issueSlug(), e.getMessage());
                    }
                }
                pillCounts.add(new IssuePillCount(pill.issueSlug(), pill.label(), count));
            }
        }

        List<JudgmentAuthorityCard> cards = new ArrayList<>();
        if (precedentHybridSearchService != null) {
            try {
                cards = precedentHybridSearchService.search(q, null, court, topicSlug, issue, limit > 0 ? limit : 20, RankingProfile.SEARCH_LIBRARY, false);
            } catch (Exception e) {
                log.warn("Hybrid search notice in getTopicDetail: {}", e.getMessage());
            }
        }

        if (cards.isEmpty() && precedentIndexRepository != null) {
            try {
                PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(
                        List.of(),
                        q,
                        null,
                        topicSlug,
                        (issue != null && !issue.isBlank()) ? issue : null,
                        (court != null && !court.isBlank()) ? court : null,
                        limit > 0 ? limit : 20
                );
                List<PrecedentIndexRecord> matches = precedentIndexRepository.searchPrecedents(criteria);
                for (PrecedentIndexRecord rec : matches) {
                    cards.add(mapRecordToCard(rec));
                }
            } catch (Exception e) {
                log.warn("Notice querying topic precedents in getTopicDetail: {}", e.getMessage());
            }
        }

        // Fallback to in-memory corpus if offline or empty
        if (cards.isEmpty() && corpusRegistry != null) {
            for (CorpusPrecedentEntry entry : corpusRegistry.getAllPrecedents()) {
                var classification = crosswalkService.classifyJudgmentTaxonomy(
                        extractEntryTuples(entry),
                        entry.summary() + " " + String.join(" ", entry.keyPropositions())
                );
                if (classification.topicSlugs().contains(topicSlug)) {
                    if (issue == null || issue.isBlank() || classification.issueTags().contains(issue)) {
                        cards.add(mapEntryToCard(entry));
                    }
                }
            }
        }

        return new TopicDetailResponse(
                topic.topicSlug(),
                topic.title(),
                topic.subtitle(),
                topic.primaryDomain(),
                topic.statutoryBannerTitle(),
                topic.statutoryBannerNote(),
                pillCounts,
                cards
        );
    }

    /**
     * GET /api/judgments/search:
     * 2-Stage Retrieval & Legal Reranking search over precedents.
     */
    @Transactional(readOnly = true)
    public JudgmentSearchResponse searchJudgments(
            String q,
            String domain,
            String court,
            String topic,
            String issue,
            int limit
    ) {
        return searchJudgments(q, domain, court, topic, issue, limit, false);
    }

    /**
     * GET /api/judgments/search?debug=true:
     * Returns ranked precedents with transparent scoring breakdown.
     */
    @Transactional(readOnly = true)
    public JudgmentSearchResponse searchJudgments(
            String q,
            String domain,
            String court,
            String topic,
            String issue,
            int limit,
            boolean debug
    ) {
        List<JudgmentAuthorityCard> results = new ArrayList<>();
        boolean hybridHandled = false;

        if (precedentHybridSearchService != null) {
            try {
                results = precedentHybridSearchService.search(q, domain, court, topic, issue, limit, RankingProfile.SEARCH_LIBRARY, debug);
                hybridHandled = true;
            } catch (Exception e) {
                log.warn("Hybrid search notice: {}", e.getMessage());
            }
        }

        // Fallback to additive SQL search ONLY when hybrid search is unavailable or errored
        if (!hybridHandled && results.isEmpty() && precedentIndexRepository != null) {
            try {
                List<String> extractedTuples = new ArrayList<>();
                if (q != null && !q.isBlank()) {
                    Matcher m = SECTION_PATTERN.matcher(q);
                    while (m.find()) {
                        String sec = m.group(1);
                        int start = Math.max(0, m.start() - 30);
                        int end = Math.min(q.length(), m.end() + 30);
                        String snippet = q.substring(start, end);

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

                PariMateriaCrosswalkService.ExpandedTupleQuery expanded = crosswalkService.expandQueryTuples(extractedTuples);

                PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(
                        expanded.allExpandedTuples(),
                        q,
                        (domain != null && !domain.isBlank()) ? domain : null,
                        (topic != null && !topic.isBlank()) ? topic : null,
                        (issue != null && !issue.isBlank()) ? issue : null,
                        (court != null && !court.isBlank()) ? court : null,
                        limit > 0 ? limit : 20
                );
                List<PrecedentIndexRecord> matches = precedentIndexRepository.searchPrecedents(criteria);
                for (PrecedentIndexRecord rec : matches) {
                    results.add(mapRecordToCard(rec));
                }
            } catch (Exception e) {
                log.warn("Notice executing additive SQL fallback: {}", e.getMessage());
            }
        }

        // Fallback to in-memory catalog ONLY when hybrid search is unavailable or errored
        if (!hybridHandled && results.isEmpty() && corpusRegistry != null) {
            for (CorpusPrecedentEntry entry : corpusRegistry.getAllPrecedents()) {
                String combined = (entry.caseTitle() + " " + entry.summary() + " " + entry.primaryCitation()).toLowerCase(Locale.ROOT);
                if (q == null || q.isBlank() || combined.contains(q.toLowerCase(Locale.ROOT).trim())) {
                    results.add(mapEntryToCard(entry));
                }
            }
        }

        return new JudgmentSearchResponse(q != null ? q : "", results.size(), results);
    }

    private JudgmentAuthorityCard mapRecordToCard(PrecedentIndexRecord rec) {
        return new JudgmentAuthorityCard(
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
                rec.decisionYear()
        );
    }

    private JudgmentAuthorityCard mapEntryToCard(CorpusPrecedentEntry entry) {
        return new JudgmentAuthorityCard(
                entry.authorityId(),
                entry.caseTitle(),
                entry.courtName(),
                entry.judgmentDate(),
                entry.primaryCitation(),
                entry.equivalentCitations(),
                "VERIFIED_CORPUS",
                null,
                null,
                null,
                entry.summary(),
                entry.keyPropositions(),
                false
        );
    }

    private List<String> extractEntryTuples(CorpusPrecedentEntry entry) {
        Set<String> tuples = new HashSet<>();
        if (entry.iracBrief() != null && entry.iracBrief().provisionsCited() != null) {
            for (String p : entry.iracBrief().provisionsCited()) {
                Matcher m = SECTION_PATTERN.matcher(p);
                while (m.find()) {
                    String sec = m.group(1);
                    String act = crosswalkService.normalizeActCode(p);
                    if (!act.isEmpty()) {
                        String t = crosswalkService.toCanonicalTuple(act, sec);
                        if (!t.isEmpty()) tuples.add(t);
                    }
                }
            }
        }
        return new ArrayList<>(tuples);
    }
}

