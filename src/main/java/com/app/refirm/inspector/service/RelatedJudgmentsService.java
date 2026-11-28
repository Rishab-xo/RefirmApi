package com.app.refirm.inspector.service;

import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedCitationPageResponse;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedJudgmentItem;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedJudgmentsResponse;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository.CitationSummaryCounts;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository.SimilarCandidateRow;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository.TargetContext;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService.ExpandedTupleQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Orchestrates Milestone 3 3-Tab Related Judgments API:
 * - Initial consolidated read (Cited By, Relies On, and Anti-Drift Similar Cases)
 * - Fast paginated & filtered citation traversal without re-running vector ANN.
 */
@Service
public class RelatedJudgmentsService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 50;
    private static final int SIMILAR_ANN_POOL_SIZE = 80;

    private static final Set<String> BOILERPLATE_TUPLES = Set.of(
            "CONSTITUTION:136",
            "CONSTITUTION:226",
            "CONSTITUTION:32",
            "CRPC:482",
            "BNSS:528"
    );

    private final RelatedPrecedentRepository relatedRepository;
    private final PariMateriaCrosswalkService crosswalkService;

    public RelatedJudgmentsService(
            RelatedPrecedentRepository relatedRepository,
            PariMateriaCrosswalkService crosswalkService
    ) {
        this.relatedRepository = relatedRepository;
        this.crosswalkService = crosswalkService;
    }

    /**
     * Loads true citation counts plus top {@code limit} rows for Cited By, Relies On, and Similar Cases.
     */
    @Transactional(readOnly = true)
    public RelatedJudgmentsResponse getRelatedJudgments(String authorityId, int requestedLimit) {
        TargetContext target = requireTargetContext(authorityId);
        int limit = clampLimit(requestedLimit);

        CitationSummaryCounts summary = relatedRepository.countCitationSummary(target.authorityId());
        List<RelatedJudgmentItem> citedBy = relatedRepository.findCitedBy(target.authorityId(), false, 0, limit);
        List<RelatedJudgmentItem> reliesOn = relatedRepository.findReliesOn(target.authorityId(), false, 0, limit);

        List<RelatedJudgmentItem> similarCases = List.of();
        if (target.embeddingLiteral() != null && !target.embeddingLiteral().isBlank()) {
            List<SimilarCandidateRow> pool = relatedRepository.findSimilarCandidates(
                    target.authorityId(),
                    target.embeddingLiteral(),
                    SIMILAR_ANN_POOL_SIZE
            );
            similarCases = rankSimilarCandidates(target, pool, limit);
        }

        return new RelatedJudgmentsResponse(
                target.authorityId(),
                summary.citedByCount(),
                summary.reliesOnCount(),
                summary.negativeTreatmentCount(),
                summary.reliesOnNegativeCount(),
                citedBy,
                reliesOn,
                similarCases
        );
    }

    /**
     * Returns paginated Cited By / Relies On rows filtered by ALL or NEGATIVE treatment
     * directly from the citation index without re-running ANN vector search.
     */
    @Transactional(readOnly = true)
    public RelatedCitationPageResponse getRelatedCitationsPage(
            String authorityId,
            String direction,
            String treatment,
            int offset,
            int limit
    ) {
        String normDirection = validateDirection(direction);
        String normTreatment = validateTreatment(treatment);
        int safeOffset = Math.max(0, offset);
        int safeLimit = clampLimit(limit);

        TargetContext target = requireTargetContext(authorityId);
        boolean isCitedBy = "CITED_BY".equals(normDirection);
        boolean negativeOnly = "NEGATIVE".equals(normTreatment);

        int totalCount = relatedRepository.countFilteredCitations(target.authorityId(), isCitedBy, negativeOnly);
        List<RelatedJudgmentItem> items = isCitedBy
                ? relatedRepository.findCitedBy(target.authorityId(), negativeOnly, safeOffset, safeLimit)
                : relatedRepository.findReliesOn(target.authorityId(), negativeOnly, safeOffset, safeLimit);

        boolean hasMore = (safeOffset + items.size()) < totalCount;

        return new RelatedCitationPageResponse(
                target.authorityId(),
                normDirection,
                normTreatment,
                totalCount,
                safeOffset,
                safeLimit,
                hasMore,
                items
        );
    }

    private List<RelatedJudgmentItem> rankSimilarCandidates(
            TargetContext target,
            List<SimilarCandidateRow> candidates,
            int limit
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Set<String> expandedTargetTuples = buildExpandedNonBoilerplateSet(target.actSectionTuples());
        String targetDomain = target.primaryDomain();

        record ScoredSimilar(RelatedJudgmentItem item, double score, int citedByCount) {}

        List<ScoredSimilar> scored = new ArrayList<>(candidates.size());
        for (SimilarCandidateRow cand : candidates) {
            List<String> sharedTuples = computeSharedTuples(expandedTargetTuples, cand.actSectionTuples());

            double clampedCos = clamp01(cand.cosineSim());
            double base = clampedCos * 65.0;
            double domainBonus = (targetDomain != null && !targetDomain.isBlank()
                    && cand.primaryDomain() != null
                    && targetDomain.equalsIgnoreCase(cand.primaryDomain())) ? 10.0 : 0.0;
            double tupleBonus = Math.min(25.0, sharedTuples.size() * 8.5);
            double overruledPenalty = "POTENTIALLY_OVERRULED".equalsIgnoreCase(cand.goodLawStatus()) ? -35.0 : 0.0;
            double totalScore = base + domainBonus + tupleBonus + overruledPenalty;

            double roundedSim = Math.round(clampedCos * 10000.0) / 10000.0;
            RelatedJudgmentItem item = new RelatedJudgmentItem(
                    cand.authorityId(),
                    cand.caseTitle(),
                    cand.decisionYear(),
                    cand.courtCode(),
                    cand.primaryCitation(),
                    cand.benchCount(),
                    cand.isConstitutionBench(),
                    cand.citedByCount(),
                    cand.goodLawStatus(),
                    cand.overruledByAuthorityId(),
                    "SIMILAR",
                    null,
                    null,
                    null,
                    null,
                    cand.twoLineRatio(),
                    sharedTuples,
                    roundedSim
            );
            scored.add(new ScoredSimilar(item, totalScore, cand.citedByCount()));
        }

        scored.sort((a, b) -> {
            int cmpScore = Double.compare(b.score(), a.score());
            if (cmpScore != 0) {
                return cmpScore;
            }
            int cmpCites = Integer.compare(b.citedByCount(), a.citedByCount());
            if (cmpCites != 0) {
                return cmpCites;
            }
            return Comparator.<String>nullsLast(String::compareTo)
                    .compare(a.item().authorityId(), b.item().authorityId());
        });

        int take = Math.min(limit, scored.size());
        List<RelatedJudgmentItem> top = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            top.add(scored.get(i).item());
        }
        return top;
    }

    private Set<String> buildExpandedNonBoilerplateSet(List<String> targetTuples) {
        if (targetTuples == null || targetTuples.isEmpty()) {
            return Set.of();
        }
        List<String> nonBoilerplate = new ArrayList<>();
        for (String raw : targetTuples) {
            String canonical = canonicalizeTuple(raw);
            if (!canonical.isEmpty() && !BOILERPLATE_TUPLES.contains(canonical)) {
                nonBoilerplate.add(canonical);
            }
        }
        if (nonBoilerplate.isEmpty()) {
            return Set.of();
        }

        Set<String> expandedSet = new LinkedHashSet<>(nonBoilerplate);
        if (crosswalkService != null) {
            ExpandedTupleQuery expanded = crosswalkService.expandQueryTuples(nonBoilerplate);
            if (expanded != null && expanded.allExpandedTuples() != null) {
                for (String t : expanded.allExpandedTuples()) {
                    String c = canonicalizeTuple(t);
                    if (!c.isEmpty() && !BOILERPLATE_TUPLES.contains(c)) {
                        expandedSet.add(c);
                    }
                }
            }
        }
        return expandedSet;
    }

    private List<String> computeSharedTuples(Set<String> expandedTargetTuples, List<String> candTuples) {
        if (expandedTargetTuples.isEmpty() || candTuples == null || candTuples.isEmpty()) {
            return List.of();
        }
        Set<String> matched = new TreeSet<>();
        for (String raw : candTuples) {
            String canonical = canonicalizeTuple(raw);
            if (!canonical.isEmpty() && !BOILERPLATE_TUPLES.contains(canonical) && expandedTargetTuples.contains(canonical)) {
                matched.add(canonical);
            }
        }
        return matched.isEmpty() ? List.of() : List.copyOf(matched);
    }

    private String canonicalizeTuple(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon >= trimmed.length() - 1) {
            return "";
        }
        String actPart = trimmed.substring(0, colon);
        String secPart = trimmed.substring(colon + 1);
        if (crosswalkService != null) {
            String canonical = crosswalkService.toCanonicalTuple(actPart, secPart);
            if (canonical != null && !canonical.isBlank()) {
                return canonical;
            }
        }
        return actPart.trim().toUpperCase(Locale.ROOT) + ":" + secPart.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private TargetContext requireTargetContext(String authorityId) {
        if (authorityId == null || authorityId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authority not found: " + authorityId);
        }
        return relatedRepository.findTargetContext(authorityId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authority not found: " + authorityId));
    }

    private static String validateDirection(String direction) {
        if (direction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be CITED_BY or RELIES_ON");
        }
        String norm = direction.trim().toUpperCase(Locale.ROOT);
        if (!"CITED_BY".equals(norm) && !"RELIES_ON".equals(norm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid direction: " + direction);
        }
        return norm;
    }

    private static String validateTreatment(String treatment) {
        if (treatment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "treatment must be ALL or NEGATIVE");
        }
        String norm = treatment.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(norm) && !"NEGATIVE".equals(norm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid treatment: " + treatment);
        }
        return norm;
    }

    private static int clampLimit(int limit) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
