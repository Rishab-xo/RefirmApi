package com.app.refirm.inspector.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ring 1 of Step 5 (Hybrid Judgment Engine):
 * Data-driven Pari Materia Statute Bridge, Canonical (Act, Section) Normalizer,
 * Twin-Tuple Query Expander, and Topic/Issue Taxonomy Resolver.
 *
 * Strictly adheres to Refirm Invariants:
 * - Zero hardcoded domain or section switches in Java.
 * - Sub-millisecond in-memory lookup with negligible (<100 KB) heap footprint.
 */
@Service
public class PariMateriaCrosswalkService {

    private static final Logger log = LoggerFactory.getLogger(PariMateriaCrosswalkService.class);

    private static final String CROSSWALK_RESOURCE_PATH = "okf-precedents/pari_materia_crosswalk.json";
    private static final String TAXONOMY_RESOURCE_PATH = "okf-precedents/topic_issue_taxonomy.json";

    private static final Pattern SUB_SECTION_PATTERN = Pattern.compile("^([0-9]+[A-Z]?)(\\(.*\\))$");
    private static final Pattern INLINE_TUPLE_CLEANER = Pattern.compile("^(?:SEC(?:TION)?\\.?|S\\.|ART(?:ICLE)?\\.?)\\s*", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    // Canonical lookup maps populated at startup from JSON
    private final Map<String, String> aliasToCanonicalAct = new HashMap<>();
    private final Map<String, String> canonicalActToDisplayLabel = new HashMap<>();
    // Key: Canonical Tuple (e.g. "BNSS:483" or "CRPC:439") -> List of counterpart CrosswalkEdges
    private final Map<String, List<CrosswalkEdge>> bidirectionalTupleIndex = new HashMap<>();
    private final Map<String, TopicDefinition> topicsBySlug = new LinkedHashMap<>();

    public PariMateriaCrosswalkService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadCrosswalkCatalog();
        loadTopicTaxonomy();
    }

    private void loadCrosswalkCatalog() {
        try (InputStream is = new ClassPathResource(CROSSWALK_RESOURCE_PATH).getInputStream()) {
            CrosswalkCatalogFile catalog = objectMapper.readValue(is, CrosswalkCatalogFile.class);

            if (catalog.actDisplayLabels() != null) {
                canonicalActToDisplayLabel.putAll(catalog.actDisplayLabels());
            }

            if (catalog.actAliases() != null) {
                for (Map.Entry<String, List<String>> entry : catalog.actAliases().entrySet()) {
                    String canonicalCode = entry.getKey().trim().toUpperCase(Locale.ROOT);
                    aliasToCanonicalAct.put(normalizeAliasKey(canonicalCode), canonicalCode);
                    if (entry.getValue() != null) {
                        for (String alias : entry.getValue()) {
                            aliasToCanonicalAct.put(normalizeAliasKey(alias), canonicalCode);
                        }
                    }
                }
            }

            if (catalog.crosswalkMappings() != null) {
                for (CrosswalkMappingEntry m : catalog.crosswalkMappings()) {
                    String legacyTuple = toCanonicalTuple(m.legacyAct(), m.legacySection());
                    String modernTuple = toCanonicalTuple(m.modernAct(), m.modernSection());

                    CrosswalkEdge legacyToModern = new CrosswalkEdge(
                            legacyTuple, m.legacyAct(), normalizeSectionToken(m.legacySection()),
                            modernTuple, m.modernAct(), normalizeSectionToken(m.modernSection()),
                            m.mappingType(), m.topicLabel(), false
                    );
                    CrosswalkEdge modernToLegacy = new CrosswalkEdge(
                            modernTuple, m.modernAct(), normalizeSectionToken(m.modernSection()),
                            legacyTuple, m.legacyAct(), normalizeSectionToken(m.legacySection()),
                            m.mappingType(), m.topicLabel(), true
                    );

                    indexEdgeWithBaseFallback(legacyTuple, legacyToModern);
                    indexEdgeWithBaseFallback(modernTuple, modernToLegacy);
                }
            }

            log.info("Loaded PariMateriaCrosswalkService: {} canonical acts, {} indexed tuple edges",
                    canonicalActToDisplayLabel.size(), bidirectionalTupleIndex.size());
        } catch (Exception e) {
            log.warn("Could not load {}: {}. Operating with fallback normalization.", CROSSWALK_RESOURCE_PATH, e.getMessage());
        }
    }

    private void loadTopicTaxonomy() {
        try (InputStream is = new ClassPathResource(TAXONOMY_RESOURCE_PATH).getInputStream()) {
            TopicTaxonomyFile taxonomy = objectMapper.readValue(is, TopicTaxonomyFile.class);
            if (taxonomy.topics() != null) {
                for (TopicDefinition topic : taxonomy.topics()) {
                    topicsBySlug.put(topic.topicSlug(), topic);
                }
            }
            log.info("Loaded Precedent Topic Taxonomy: {} topics", topicsBySlug.size());
        } catch (Exception e) {
            log.warn("Could not load {}: {}", TAXONOMY_RESOURCE_PATH, e.getMessage());
        }
    }

    private void indexEdgeWithBaseFallback(String sourceTuple, CrosswalkEdge edge) {
        bidirectionalTupleIndex.computeIfAbsent(sourceTuple, k -> new ArrayList<>()).add(edge);
        // Also index base section if source has a sub-section (e.g. "BNS:318(4)" -> also index "BNS:318")
        Optional<String> baseTuple = extractBaseSectionTuple(sourceTuple);
        baseTuple.ifPresent(base -> {
            List<CrosswalkEdge> list = bidirectionalTupleIndex.computeIfAbsent(base, k -> new ArrayList<>());
            if (!list.contains(edge)) {
                list.add(edge);
            }
        });
    }

    /**
     * Normalizes any raw Act name/acronym into its canonical uppercase code (e.g., "Cr.P.C." -> "CRPC").
     */
    public String normalizeActCode(String rawAct) {
        if (rawAct == null || rawAct.isBlank()) {
            return "";
        }
        String cleaned = normalizeAliasKey(rawAct);
        String direct = aliasToCanonicalAct.get(cleaned);
        if (direct != null) {
            return direct;
        }
        // Check if cleaned string contains a known multi-word act title
        for (Map.Entry<String, String> entry : aliasToCanonicalAct.entrySet()) {
            if (entry.getKey().length() >= 4 && cleaned.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        // Check single token matches (e.g. "IBC 2016" -> token "IBC")
        for (String token : cleaned.split("\\s+")) {
            String tokenMatch = aliasToCanonicalAct.get(token);
            if (tokenMatch != null) {
                return tokenMatch;
            }
        }
        return cleaned.replace(' ', '_');
    }

    /**
     * Normalizes a section string (e.g. "Section 438", "318 (4)", "65-B") -> "438", "318(4)", "65B".
     */
    public String normalizeSectionToken(String rawSection) {
        if (rawSection == null || rawSection.isBlank()) {
            return "";
        }
        String cleaned = INLINE_TUPLE_CLEANER.matcher(rawSection.trim()).replaceFirst("");
        cleaned = cleaned.replaceAll("\\s+", "")
                .replaceAll("(?<=\\d)-(?=[A-Za-z])", "")
                .toUpperCase(Locale.ROOT);
        return cleaned;
    }

    /**
     * Builds a canonical "ACT_CODE:SECTION" tuple (e.g., ("Cr.P.C.", "Section 439") -> "CRPC:439").
     */
    public String toCanonicalTuple(String rawAct, String rawSection) {
        String act = normalizeActCode(rawAct);
        String section = normalizeSectionToken(rawSection);
        if (act.isEmpty() || section.isEmpty()) {
            return "";
        }
        return act + ":" + section;
    }

    /**
     * Expands a collection of canonical or raw tuples into a deduplicated Twin-Tuple search set.
     * Includes:
     * 1. Exact canonical tuple (e.g. "BNSS:482")
     * 2. Base section fallback if sub-section was specified (e.g. "BNS:318(4)" -> "BNS:318")
     * 3. Pari Materia counterpart tuples (e.g. "CRPC:438") and their base sections
     */
    public ExpandedTupleQuery expandQueryTuples(Collection<String> rawOrCanonicalTuples) {
        if (rawOrCanonicalTuples == null || rawOrCanonicalTuples.isEmpty()) {
            return new ExpandedTupleQuery(List.of(), List.of(), Map.of());
        }

        Set<String> primaryCanonical = new LinkedHashSet<>();
        Set<String> allExpanded = new LinkedHashSet<>();
        Map<String, CrosswalkEdge> counterpartToOriginEdge = new LinkedHashMap<>();
        List<Set<String>> conceptGroups = new ArrayList<>();

        for (String input : rawOrCanonicalTuples) {
            if (input == null || !input.contains(":")) {
                continue;
            }
            String[] parts = input.split(":", 2);
            String canonical = toCanonicalTuple(parts[0], parts[1]);
            if (canonical.isEmpty()) {
                continue;
            }

            boolean isNewPrimary = primaryCanonical.add(canonical);
            Set<String> group = new LinkedHashSet<>();
            group.add(canonical);
            allExpanded.add(canonical);

            Optional<String> baseCanonical = extractBaseSectionTuple(canonical);
            baseCanonical.ifPresent(base -> {
                allExpanded.add(base);
                group.add(base);
            });

            // Lookup direct and base edges
            List<CrosswalkEdge> edges = new ArrayList<>(
                    bidirectionalTupleIndex.getOrDefault(canonical, List.of())
            );
            baseCanonical.ifPresent(base -> {
                for (CrosswalkEdge baseEdge : bidirectionalTupleIndex.getOrDefault(base, List.of())) {
                    if (!edges.contains(baseEdge)) {
                        edges.add(baseEdge);
                    }
                }
            });

            for (CrosswalkEdge edge : edges) {
                allExpanded.add(edge.targetTuple());
                group.add(edge.targetTuple());
                counterpartToOriginEdge.putIfAbsent(edge.targetTuple(), edge);
                extractBaseSectionTuple(edge.targetTuple()).ifPresent(targetBase -> {
                    allExpanded.add(targetBase);
                    group.add(targetBase);
                    counterpartToOriginEdge.putIfAbsent(targetBase, edge);
                });
            }

            if (isNewPrimary) {
                conceptGroups.add(Set.copyOf(group));
            }
        }

        return new ExpandedTupleQuery(
                List.copyOf(primaryCanonical),
                List.copyOf(allExpanded),
                Collections.unmodifiableMap(counterpartToOriginEdge),
                List.copyOf(conceptGroups)
        );
    }

    /**
     * Deterministically generates a Pari Materia crosswalk badge when a judgment's tuples
     * match a counterpart of the lawyer's queried tuples.
     *
     * Example output:
     * "[Decided under Sec 439 CrPC — Pari Materia to Sec 483 BNSS]"
     */
    public Optional<String> resolveCrosswalkBadge(
            ExpandedTupleQuery expandedQuery,
            Collection<String> judgmentTuples
    ) {
        if (expandedQuery == null || judgmentTuples == null || judgmentTuples.isEmpty()) {
            return Optional.empty();
        }
        // If the judgment already directly cites the primary queried tuple, no crosswalk badge needed
        for (String jTuple : judgmentTuples) {
            if (expandedQuery.primaryTuples().contains(jTuple)) {
                return Optional.empty();
            }
        }
        for (String jTuple : judgmentTuples) {
            CrosswalkEdge edge = expandedQuery.counterpartEdgesByTargetTuple().get(jTuple);
            if (edge != null) {
                String decidedActLabel = displayLabelForAct(edge.targetAct());
                String queriedActLabel = displayLabelForAct(edge.sourceAct());
                String relationLabel = switch (edge.mappingType()) {
                    case "MODIFIED_PROVISO" -> "Pari Materia (with modified proviso) to";
                    case "EXPANDED_SCOPE" -> "Pari Materia (expanded scope) to";
                    default -> "Pari Materia to";
                };
                return Optional.of(String.format(
                        "[Decided under Sec %s %s — %s Sec %s %s]",
                        edge.targetSection(), decidedActLabel,
                        relationLabel,
                        edge.sourceSection(), queriedActLabel
                ));
            }
        }
        return Optional.empty();
    }

    /**
     * Deterministically classifies a judgment's topic_slugs and issue_tags from its
     * act_section_tuples and text/propositions using topic_issue_taxonomy.json.
     */
    public TaxonomyClassification classifyJudgmentTaxonomy(
            Collection<String> actSectionTuples,
            String searchableText
    ) {
        Set<String> matchedTopics = new LinkedHashSet<>();
        Set<String> matchedIssues = new LinkedHashSet<>();

        Set<String> normalizedTuples = expandQueryTuples(actSectionTuples).allExpandedTuples() != null
                ? new LinkedHashSet<>(expandQueryTuples(actSectionTuples).allExpandedTuples())
                : Set.of();
        String lowerText = searchableText != null ? searchableText.toLowerCase(Locale.ROOT) : "";

        for (TopicDefinition topic : topicsBySlug.values()) {
            boolean matchesGoverningTuple = topic.governingTuples() != null
                    && topic.governingTuples().stream().anyMatch(normalizedTuples::contains);
            if (!matchesGoverningTuple) {
                continue;
            }
            matchedTopics.add(topic.topicSlug());

            if (topic.issuePills() != null) {
                for (IssuePillDefinition pill : topic.issuePills()) {
                    boolean tupleHit = pill.matchTuples() != null && !pill.matchTuples().isEmpty()
                            && pill.matchTuples().stream().anyMatch(normalizedTuples::contains);
                    boolean keywordHit = pill.matchKeywords() != null
                            && pill.matchKeywords().stream()
                            .anyMatch(kw -> lowerText.contains(kw.toLowerCase(Locale.ROOT)));
                    if (tupleHit || keywordHit) {
                        matchedIssues.add(pill.issueSlug());
                    }
                }
            }
        }

        return new TaxonomyClassification(List.copyOf(matchedTopics), List.copyOf(matchedIssues));
    }

    public Optional<TopicDefinition> getTopicBySlug(String topicSlug) {
        return Optional.ofNullable(topicsBySlug.get(topicSlug));
    }

    public Collection<TopicDefinition> getAllTopics() {
        return Collections.unmodifiableCollection(topicsBySlug.values());
    }

    public String displayLabelForAct(String canonicalAct) {
        return canonicalActToDisplayLabel.getOrDefault(canonicalAct, canonicalAct);
    }

    private Optional<String> extractBaseSectionTuple(String canonicalTuple) {
        int colonIdx = canonicalTuple.indexOf(':');
        if (colonIdx <= 0 || colonIdx == canonicalTuple.length() - 1) {
            return Optional.empty();
        }
        String act = canonicalTuple.substring(0, colonIdx);
        String sec = canonicalTuple.substring(colonIdx + 1);
        Matcher m = SUB_SECTION_PATTERN.matcher(sec);
        if (m.matches()) {
            return Optional.of(act + ":" + m.group(1));
        }
        return Optional.empty();
    }

    private String normalizeAliasKey(String raw) {
        return raw.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[,._()-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // --- Immutable Data Carrier Records ---

    public record ExpandedTupleQuery(
            List<String> primaryTuples,
            List<String> allExpandedTuples,
            Map<String, CrosswalkEdge> counterpartEdgesByTargetTuple,
            List<Set<String>> conceptGroups
    ) {
        public ExpandedTupleQuery(
                List<String> primaryTuples,
                List<String> allExpandedTuples,
                Map<String, CrosswalkEdge> counterpartEdgesByTargetTuple
        ) {
            this(primaryTuples, allExpandedTuples, counterpartEdgesByTargetTuple, List.of());
        }
    }

    public record CrosswalkEdge(
            String sourceTuple,
            String sourceAct,
            String sourceSection,
            String targetTuple,
            String targetAct,
            String targetSection,
            String mappingType,
            String topicLabel,
            boolean isModernToLegacy
    ) {}

    public record TaxonomyClassification(
            List<String> topicSlugs,
            List<String> issueTags
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrosswalkCatalogFile(
            String schemaVersion,
            Map<String, List<String>> actAliases,
            Map<String, String> actDisplayLabels,
            List<CrosswalkMappingEntry> crosswalkMappings
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrosswalkMappingEntry(
            String legacyAct,
            String legacySection,
            String modernAct,
            String modernSection,
            String mappingType,
            String topicLabel
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicTaxonomyFile(
            String schemaVersion,
            List<TopicDefinition> topics
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicDefinition(
            String topicSlug,
            String title,
            String subtitle,
            String primaryDomain,
            List<String> governingTuples,
            String statutoryBannerTitle,
            String statutoryBannerNote,
            List<IssuePillDefinition> issuePills
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssuePillDefinition(
            String issueSlug,
            String label,
            List<String> matchTuples,
            List<String> matchKeywords
    ) {}
}

