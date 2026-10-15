package com.app.refirm.core.service;

import com.app.refirm.rules.evaluation.EvaluationDecision;
import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.rules.evaluation.RuleEvaluationStatus;
import com.app.refirm.rules.evaluation.StatuteRuleEvaluator;
import com.app.refirm.rules.model.StatuteRule;
import com.app.refirm.rules.registry.StatuteRegistry;
import com.app.refirm.triage.entities.LegalCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * High-performance, in-memory Open Knowledge Format (OKF) statute retriever.
 * Implements Three-Layer Deterministic Section Targeting:
 * - Layer 1: O(1) Statutory Manifest Lookup (index.json) when present.
 * - Layer 2: YAML-Frontmatter-aware, Sub-Clause Definition Indexed (Sec 2(7), 2(11), 2(47)),
 *            and Regulatory/Product-Liability guarded scoring.
 * - Layer 3: Mandatory Definitions (Sec 2) + Filing (Sec 35) + Reliefs (Sec 39(1)) anchoring.
 */
@Service
public class LegalRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(LegalRetrievalService.class);
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StatuteRegistry statuteRegistry;
    private final StatuteRuleEvaluator statuteRuleEvaluator;

    private final Map<LegalCategory, List<StatuteFile>> okfCache = new ConcurrentHashMap<>();
    // StatuteName -> CauseOfActionKey -> ManifestEntry
    private final Map<String, Map<String, ManifestEntry>> manifestCache = new ConcurrentHashMap<>();

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "are", "was",
            "been", "have", "has", "had", "they", "them", "their", "into", "onto",
            "said", "such", "were", "what", "when", "where", "which", "who", "whom", "act"
    );
    private static final int MAX_CONTEXT_CHARACTERS = 100000;
    private static final double MULTI_STATUTE_SCORE_RATIO = 0.40;

    private static final Pattern DEFINITION_SUBCLAUSE_PATTERN = Pattern.compile(
            "\\(([0-9A-Za-z\\-]+)\\)\\s*[\"“]([^\"”]{2,80})[\"”][^\\n.;:]{0,120}?\\b(?:means|includes)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REGULATORY_OR_ADMIN_HEADING_PATTERN = Pattern.compile(
            "measures\\s+to\\s+prevent\\s+unfair\\s+trade\\s+practices\\s+in\\s+e-commerce|" +
            "power\\s+(?:of\\s+(?:central|state)\\s+government\\s+)?to\\s+make\\s+(?:rules|regulations)|" +
            "power\\s+to\\s+remove\\s+difficulties|protection\\s+of\\s+action\\s+taken\\s+in\\s+good\\s+faith|" +
            "salaries|allowances|vacanc|resignation|annual\\s+report|accounts\\s+and\\s+audit|" +
            "qualifications?\\s+for\\s+appointment|establishment\\s+of\\s+(?:central|state|district)",
            Pattern.CASE_INSENSITIVE
    );

    public record ManifestEntry(List<String> files, List<String> pinnedClauses, List<String> triggerKeywords) {
        public ManifestEntry(List<String> files, List<String> pinnedClauses) {
            this(files, pinnedClauses, List.of());
        }
    }

    public record StatuteFile(
            String statuteName,
            String filename,
            String content,
            String searchContent,
            String sectionNumber,
            String heading,
            String cleanBodyLower,
            Map<String, String> definedTermsByClause,
            boolean isDefinitionSection,
            boolean isFilingSection,
            boolean isReliefSection,
            boolean isRegulatoryOrAdmin,
            boolean isProductLiabilitySection
    ) {
        // Backward-compatible 4-argument constructor for existing unit tests
        public StatuteFile(String statuteName, String filename, String content, String searchContent) {
            this(
                    statuteName,
                    filename,
                    content,
                    searchContent,
                    extractSectionNumberStatic(content, filename),
                    extractHeadingStatic(content),
                    stripFrontmatterStatic(content).toLowerCase(),
                    extractDefinedTermsStatic(content),
                    isDefinitionStatic(content, filename),
                    isFilingStatic(content),
                    isReliefStatic(content),
                    isRegulatoryStatic(content),
                    isProductLiabilityStatic(content)
            );
        }
    }

    public record RetrievalResult(
            String statutePack,
            List<String> assumptionCaveats,
            List<String> includedStatuteNames,
            List<String> excludedStatuteNames,
            List<StatuteFile> selectedFiles
    ) {
        public RetrievalResult(
                String statutePack,
                List<String> assumptionCaveats,
                List<String> includedStatuteNames,
                List<String> excludedStatuteNames
        ) {
            this(statutePack, assumptionCaveats, includedStatuteNames, excludedStatuteNames, List.of());
        }

        public static RetrievalResult empty() {
            return new RetrievalResult("", List.of(), List.of(), List.of(), List.of());
        }
    }

    private record InternalSelection(
            List<StatuteFile> selectedFiles,
            List<String> pinnedClauseDirectives,
            List<String> assumptionCaveats,
            List<String> includedStatuteNames,
            List<String> excludedStatuteNames
    ) {}

    public LegalRetrievalService(StatuteRegistry statuteRegistry, StatuteRuleEvaluator statuteRuleEvaluator) {
        this.statuteRegistry = statuteRegistry;
        this.statuteRuleEvaluator = statuteRuleEvaluator;
    }

    void cacheStatuteFiles(LegalCategory category, List<StatuteFile> files) {
        if (category != null && files != null) {
            okfCache.put(category, Collections.unmodifiableList(files));
        }
    }

    @PostConstruct
    public void initializeCache() {
        log.info("Initializing OKF In-Memory Cache & Statutory Manifests...");
        int totalFilesLoaded = 0;

        for (LegalCategory category : LegalCategory.values()) {
            if (category == LegalCategory.UNKNOWN) continue;

            String mdPattern = "classpath*:okf-bundles/" + category.name() + "/**/*.md";
            List<StatuteFile> filesForCategory = new ArrayList<>();

            try {
                Resource[] resources = resourceResolver.getResources(mdPattern);
                for (Resource resource : resources) {
                    try (InputStream is = resource.getInputStream()) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        String filename = resource.getFilename() != null ? resource.getFilename() : "statute.md";
                        String statuteName = extractStatuteName(resource);
                        filesForCategory.add(new StatuteFile(
                                statuteName,
                                filename,
                                content,
                                content.toLowerCase()
                        ));
                    } catch (Exception e) {
                        log.warn("Failed to read OKF file: {}", resource.getFilename(), e);
                    }
                }
                okfCache.put(category, Collections.unmodifiableList(filesForCategory));
                totalFilesLoaded += filesForCategory.size();
                log.info("Cached {} files for domain: {}", filesForCategory.size(), category.name());
            } catch (Exception e) {
                log.warn("No OKF directory found for domain: {}", category.name(), e);
                okfCache.put(category, Collections.emptyList());
            }

            // Load index.json manifests if present
            String manifestPattern = "classpath*:okf-bundles/" + category.name() + "/**/index.json";
            try {
                Resource[] manifestResources = resourceResolver.getResources(manifestPattern);
                for (Resource res : manifestResources) {
                    try (InputStream is = res.getInputStream()) {
                        String statuteName = extractStatuteName(res);
                        JsonNode root = objectMapper.readTree(is);
                        JsonNode causesNode = root.get("causes_of_action");
                        if (causesNode != null && causesNode.isObject()) {
                            Map<String, ManifestEntry> entries = new HashMap<>();
                            causesNode.fields().forEachRemaining(entry -> {
                                List<String> files = new ArrayList<>();
                                JsonNode filesNode = entry.getValue().get("files");
                                if (filesNode != null && filesNode.isArray()) {
                                    filesNode.forEach(n -> files.add(n.asText()));
                                }
                                List<String> pins = new ArrayList<>();
                                JsonNode pinsNode = entry.getValue().get("pinned_clauses");
                                if (pinsNode != null && pinsNode.isArray()) {
                                    pinsNode.forEach(n -> pins.add(n.asText()));
                                }
                                List<String> keywords = new ArrayList<>();
                                JsonNode keywordsNode = entry.getValue().get("trigger_keywords");
                                if (keywordsNode != null && keywordsNode.isArray()) {
                                    keywordsNode.forEach(n -> keywords.add(n.asText().toLowerCase().trim()));
                                }
                                entries.put(entry.getKey().toUpperCase(), new ManifestEntry(List.copyOf(files), List.copyOf(pins), List.copyOf(keywords)));
                            });
                            manifestCache.put(statuteName, Collections.unmodifiableMap(entries));
                            log.info("Loaded index.json manifest for '{}' with {} causes of action.", statuteName, entries.size());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse index.json manifest: {}", res.getURI(), e);
                    }
                }
            } catch (Exception ignored) {
                // Optional manifest files
            }
        }
        log.info("OKF Cache initialization complete. Total statutes cached: {}", totalFilesLoaded);
    }

    private static String stripFrontmatterStatic(String content) {
        if (content == null) return "";
        if (content.startsWith("---")) {
            int secondSep = content.indexOf("---", 3);
            if (secondSep != -1) {
                return content.substring(secondSep + 3).trim();
            }
        }
        return content;
    }

    private static String extractSectionNumberStatic(String content, String filename) {
        if (content != null && content.startsWith("---")) {
            int secondSep = content.indexOf("---", 3);
            if (secondSep != -1) {
                String yaml = content.substring(3, secondSep);
                Matcher m = Pattern.compile("^section\\s*:\\s*['\"]?([^'\"\\r\\n]+)['\"]?", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE).matcher(yaml);
                if (m.find()) return m.group(1).trim();
            }
        }
        if (filename != null) {
            return filename.replace("section_", "").replace(".md", "").trim();
        }
        return "";
    }

    private static String extractHeadingStatic(String content) {
        if (content == null) return "";
        if (content.startsWith("---")) {
            int secondSep = content.indexOf("---", 3);
            if (secondSep != -1) {
                String yaml = content.substring(3, secondSep);
                Matcher m = Pattern.compile("^heading\\s*:\\s*['\"]?([^'\"\\r\\n]+)['\"]?", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE).matcher(yaml);
                if (m.find()) return m.group(1).trim().toLowerCase();
            }
        }
        String body = stripFrontmatterStatic(content);
        int newlineIdx = body.indexOf('\n');
        String firstLine = (newlineIdx != -1 ? body.substring(0, newlineIdx) : body).toLowerCase();
        return firstLine.replaceAll("^#+\\s*", "").trim();
    }

    private static Map<String, String> extractDefinedTermsStatic(String content) {
        if (content == null) return Map.of();
        String heading = extractHeadingStatic(content);
        if (!heading.contains("definition") && !heading.contains("interpretation")) {
            String sec = extractSectionNumberStatic(content, "");
            if (!"2".equals(sec) && !"3".equals(sec)) {
                return Map.of();
            }
        }
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = DEFINITION_SUBCLAUSE_PATTERN.matcher(content);
        while (matcher.find()) {
            String clause = matcher.group(1).trim();
            String term = matcher.group(2).trim().toLowerCase();
            map.put(clause, term);
        }
        return Collections.unmodifiableMap(map);
    }

    private static boolean isDefinitionStatic(String content, String filename) {
        String heading = extractHeadingStatic(content);
        String sec = extractSectionNumberStatic(content, filename);
        return heading.contains("definition") || heading.contains("interpretation") || "2".equals(sec) || "3".equals(sec);
    }

    private static boolean isFilingStatic(String content) {
        String heading = extractHeadingStatic(content);
        return heading.contains("manner in which complaint shall be made")
                || heading.contains("filing of complaint")
                || heading.contains("institution of suits")
                || heading.contains("cognizance of offence");
    }

    private static boolean isReliefStatic(String content) {
        String heading = extractHeadingStatic(content);
        String bodyLower = stripFrontmatterStatic(content).toLowerCase();
        return heading.contains("findings of district commission")
                || heading.contains("reliefs")
                || heading.contains("compensation")
                || (bodyLower.contains("issue an order to the opposite party directing him to")
                    && bodyLower.contains("return to the complainant the price"));
    }

    private static boolean isRegulatoryStatic(String content) {
        String heading = extractHeadingStatic(content);
        return REGULATORY_OR_ADMIN_HEADING_PATTERN.matcher(heading).find();
    }

    private static boolean isProductLiabilityStatic(String content) {
        String heading = extractHeadingStatic(content);
        return heading.contains("product liability")
                || heading.contains("liability of product manufacturer")
                || heading.contains("liability of product service provider")
                || heading.contains("liability of product seller");
    }

    private static boolean isLimitationStatic(String content) {
        String heading = extractHeadingStatic(content);
        return heading.contains("limitation period")
                || heading.contains("bar of limitation")
                || heading.contains("limitation of actions")
                || heading.equals("limitation")
                || heading.contains("cognizance of offence");
    }

    private static boolean isJurisdictionOrVenueStatic(String content) {
        String heading = extractHeadingStatic(content);
        return heading.contains("jurisdiction of district commission")
                || heading.contains("jurisdiction")
                || heading.contains("place of suing")
                || heading.contains("territorial")
                || heading.contains("cognizance of offence");
    }

    private static boolean isOverridingOrAdditionalRemedyStatic(String content) {
        String heading = extractHeadingStatic(content);
        return heading.contains("not in derogation")
                || heading.contains("overriding effect")
                || heading.contains("act to override")
                || heading.contains("remedies in addition");
    }

    private String extractStatuteName(Resource resource) {
        try {
            String path = resource.getURI().toString().replace('\\', '/');
            int okfIdx = path.indexOf("/okf-bundles/");
            if (okfIdx != -1) {
                String sub = path.substring(okfIdx + "/okf-bundles/".length());
                String[] parts = sub.split("/");
                if (parts.length >= 2) {
                    return parts[1];
                }
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash > 0) {
                int prevSlash = path.lastIndexOf('/', lastSlash - 1);
                if (prevSlash >= 0) {
                    return path.substring(prevSlash + 1, lastSlash);
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve statute name from resource URI: {}", e.getMessage());
        }
        return "Unknown";
    }

    private InternalSelection evaluateAndSelectFiles(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        if (domain == null || domain == LegalCategory.UNKNOWN) {
            return new InternalSelection(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<StatuteFile> domainFiles = okfCache.getOrDefault(domain, Collections.emptyList());
        if (domainFiles.isEmpty()) {
            log.warn("No cached statute files found for domain: {}", domain);
            return new InternalSelection(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        ExtractedFactsDTO evaluationFacts = (facts != null) ? facts : new ExtractedFactsDTO(null, null, null, null);

        Map<String, List<StatuteFile>> filesByStatute = domainFiles.stream()
                .filter(f -> f.statuteName() != null && !f.statuteName().isBlank() && !"Unknown".equals(f.statuteName()))
                .collect(Collectors.groupingBy(StatuteFile::statuteName));

        Set<String> excludedStatuteNames = new HashSet<>();
        List<String> includedStatuteNames = new ArrayList<>();
        List<String> assumptionCaveats = new ArrayList<>();

        // Stage 1A: Rules Engine Gatekeeper Evaluation
        for (String statuteName : filesByStatute.keySet()) {
            Optional<StatuteRule> maybeRule = (statuteRegistry != null) ? statuteRegistry.getRule(statuteName) : Optional.empty();

            if (maybeRule.isPresent() && statuteRuleEvaluator != null) {
                StatuteRule rule = maybeRule.get();
                EvaluationDecision decision = statuteRuleEvaluator.evaluateStatute(rule, evaluationFacts);

                if (decision.status() == RuleEvaluationStatus.EXCLUDED) {
                    log.info("[Rules Gatekeeper] EXCLUDED statute '{}': {}", statuteName, decision.rationale());
                    excludedStatuteNames.add(statuteName);
                } else if (decision.status() == RuleEvaluationStatus.UNVERIFIED_APPLICABLE) {
                    includedStatuteNames.add(statuteName);
                    if (decision.caveats() != null) {
                        for (String caveat : decision.caveats()) {
                            if (!assumptionCaveats.contains(caveat)) {
                                assumptionCaveats.add(caveat);
                            }
                        }
                    }
                } else {
                    includedStatuteNames.add(statuteName);
                }
            } else {
                includedStatuteNames.add(statuteName);
            }
        }

        List<String> candidateStatutes = includedStatuteNames.stream()
                .filter(name -> !excludedStatuteNames.contains(name))
                .toList();

        if (candidateStatutes.isEmpty()) {
            log.warn("All candidate statutes for domain {} were excluded by the rules engine.", domain);
            return new InternalSelection(List.of(), List.of(), List.copyOf(assumptionCaveats), List.copyOf(includedStatuteNames), List.copyOf(excludedStatuteNames));
        }

        List<String> normalizedTriggers = (keyTriggers == null) ? Collections.emptyList() : keyTriggers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> searchTerms = normalizedTriggers.stream()
                .flatMap(trigger -> Arrays.stream(trigger.toLowerCase().split("[\\s_]+")))
                .map(word -> word.replaceAll("[^a-z0-9]", ""))
                .filter(word -> word.length() >= 3 && !STOP_WORDS.contains(word))
                .distinct()
                .toList();

        // Stage 1B: Macro-Statute Scoring
        Map<String, Integer> statuteScores = new HashMap<>();
        for (String statute : candidateStatutes) {
            int score = 0;
            String cleanStatuteName = statute.toLowerCase().replace('_', ' ');

            for (String term : searchTerms) {
                if (cleanStatuteName.contains(term)) {
                    score += 1000;
                }
            }

            List<StatuteFile> statuteFiles = filesByStatute.getOrDefault(statute, Collections.emptyList());
            for (StatuteFile file : statuteFiles) {
                String lowerContent = file.cleanBodyLower();
                for (String term : searchTerms) {
                    if (lowerContent.contains(term)) {
                        score += 1;
                    }
                }
            }
            statuteScores.put(statute, score);
        }

        int maxScore = statuteScores.values().stream().max(Integer::compareTo).orElse(0);
        Set<String> winningStatutes = new LinkedHashSet<>();

        if (maxScore > 0) {
            int threshold = (int) (maxScore * MULTI_STATUTE_SCORE_RATIO);
            for (Map.Entry<String, Integer> entry : statuteScores.entrySet()) {
                if (entry.getValue() >= threshold && entry.getValue() > 0) {
                    winningStatutes.add(entry.getKey());
                    log.info("[Macro-Retrieval] Retained governing statute '{}' (Score: {} vs Max: {})",
                            entry.getKey(), entry.getValue(), maxScore);
                }
            }
        } else {
            winningStatutes.addAll(candidateStatutes);
        }

        // Stage 2: Three-Layer Micro-Section Selection within Winning Statutes
        List<StatuteFile> eligibleFiles = winningStatutes.stream()
                .flatMap(statute -> filesByStatute.getOrDefault(statute, Collections.emptyList()).stream())
                .toList();

        LinkedHashSet<StatuteFile> manifestMatchedFiles = new LinkedHashSet<>();
        List<String> pinnedClauseDirectives = new ArrayList<>();

        // LAYER 1: Check index.json Manifest if loaded for the winning statute(s)
        for (String statute : winningStatutes) {
            Map<String, ManifestEntry> manifest = manifestCache.get(statute);
            if (manifest != null && !manifest.isEmpty()) {
                Map<String, StatuteFile> byFilename = filesByStatute.getOrDefault(statute, List.of()).stream()
                        .collect(Collectors.toMap(StatuteFile::filename, f -> f, (a, b) -> a));

                boolean matchedAnyInStatute = false;

                // Step 1: Exact/canonical key matching
                for (String trigger : normalizedTriggers) {
                    String normalizedKey = trigger.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
                    for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
                        boolean keyMatches = entry.getKey().equals(normalizedKey)
                                || (normalizedKey.length() >= 10 && (entry.getKey().contains(normalizedKey) || normalizedKey.contains(entry.getKey())));
                        if (keyMatches) {
                            matchedAnyInStatute = true;
                            for (String fname : entry.getValue().files()) {
                                StatuteFile f = byFilename.get(fname);
                                if (f != null) manifestMatchedFiles.add(f);
                            }
                            for (String pin : entry.getValue().pinnedClauses()) {
                                if (!pinnedClauseDirectives.contains(pin)) {
                                    pinnedClauseDirectives.add(pin);
                                }
                            }
                        }
                    }
                }

                // Step 2: Fallback token/keyword overlap matching if exact matching didn't match
                if (!matchedAnyInStatute && !searchTerms.isEmpty()) {
                    List<Map.Entry<String, ManifestEntry>> scoredEntries = new ArrayList<>();
                    Map<String, Integer> entryScores = new HashMap<>();

                    for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
                        int score = 0;
                        String[] keyTokens = entry.getKey().toLowerCase().split("_");
                        List<String> triggers = entry.getValue().triggerKeywords();

                        for (String term : searchTerms) {
                            for (String keyToken : keyTokens) {
                                if (keyToken.contains(term) || term.contains(keyToken)) {
                                    score += 2;
                                }
                            }
                            if (triggers != null) {
                                for (String kw : triggers) {
                                    if (kw.contains(term)) {
                                        score += 1;
                                    }
                                }
                            }
                        }
                        if (score > 0) {
                            entryScores.put(entry.getKey(), score);
                            scoredEntries.add(entry);
                        }
                    }

                    scoredEntries.sort((a, b) -> Integer.compare(entryScores.getOrDefault(b.getKey(), 0), entryScores.getOrDefault(a.getKey(), 0)));

                    int topCount = Math.min(2, scoredEntries.size());
                    for (int i = 0; i < topCount; i++) {
                        ManifestEntry selectedEntry = scoredEntries.get(i).getValue();
                        for (String fname : selectedEntry.files()) {
                            StatuteFile f = byFilename.get(fname);
                            if (f != null) manifestMatchedFiles.add(f);
                        }
                        for (String pin : selectedEntry.pinnedClauses()) {
                            if (!pinnedClauseDirectives.contains(pin)) {
                                pinnedClauseDirectives.add(pin);
                            }
                        }
                    }
                }
            }
        }

        // LAYER 1: Dedicated Jurisdiction, Limitation & Non-Derogation Section Pinning
        if (!manifestMatchedFiles.isEmpty()) {
            for (StatuteFile f : eligibleFiles) {
                if (manifestMatchedFiles.size() >= 8) break;
                boolean isSpecialSection = isJurisdictionOrVenueStatic(f.content())
                        || isLimitationStatic(f.content())
                        || isOverridingOrAdditionalRemedyStatic(f.content());
                if (isSpecialSection && !manifestMatchedFiles.contains(f)) {
                    manifestMatchedFiles.add(f);
                    String pin = "Section " + f.sectionNumber() + " [" + f.heading() + "]";
                    if (!pinnedClauseDirectives.contains(pin)) {
                        pinnedClauseDirectives.add(pin);
                    }
                }
            }
        }

        // LAYER 2 & 3: Frontmatter-Aware, Sub-Clause Definition Indexed & Guarded Scoring
        boolean explicitlyPleadsProductLiability = normalizedTriggers.stream()
                .anyMatch(t -> "PRODUCT_LIABILITY_ACTION".equalsIgnoreCase(t.trim()));

        Map<StatuteFile, Integer> fileScores = new HashMap<>();
        for (StatuteFile file : eligibleFiles) {
            int score = 0;

            // 1. Demote Central/State Government regulatory power sections (e.g., CPA Section 94)
            if (file.isRegulatoryOrAdmin()) {
                score -= 100;
            }

            // 2. Demote Chapter VI Product Liability (Sections 82-87) unless explicitly pled
            if (file.isProductLiabilitySection() && !explicitlyPleadsProductLiability) {
                score -= 40;
            }

            // 3. Score Sub-Clause Definitions inside section_2.md (e.g., 2(7), 2(11), 2(47))
            if (file.isDefinitionSection()) {
                score += 25; // Layer 3 Baseline Anchor for Definitions
                for (Map.Entry<String, String> defEntry : file.definedTermsByClause().entrySet()) {
                    String clauseNum = defEntry.getKey();
                    String definedTerm = defEntry.getValue();
                    for (String term : searchTerms) {
                        if (definedTerm.contains(term)) {
                            score += 25;
                            String pin = String.format("Section %s(%s) [Definition of '%s']",
                                    file.sectionNumber(), clauseNum, definedTerm);
                            if (!pinnedClauseDirectives.contains(pin)) {
                                pinnedClauseDirectives.add(pin);
                            }
                        }
                    }
                }
                // Always pin Consumer Standing 2(7) if present in Section 2 of CPA
                if ("7".equals(file.definedTermsByClause().containsKey("7") ? "7" : "")) {
                    String consumerPin = "Section 2(7)(ii) [Definition of Consumer & Beneficiary Standing]";
                    if (!pinnedClauseDirectives.contains(consumerPin)) {
                        pinnedClauseDirectives.add(0, consumerPin);
                    }
                }
            }

            // 4. Layer 3 Baseline Anchor for Complaint Filing (Sec 35) & Reliefs (Sec 39(1)) & Limitation
            if (file.isFilingSection()) {
                score += 35;
                String filingPin = "Section " + file.sectionNumber() + " [" + file.heading() + "]";
                if (!pinnedClauseDirectives.contains(filingPin)) {
                    pinnedClauseDirectives.add(filingPin);
                }
            }
            if (file.isReliefSection()) {
                score += 40;
                String reliefPin = "Section " + file.sectionNumber() + "(1) [Statutory Reliefs: Refund, Compensation & Punitive Damages]";
                if (!pinnedClauseDirectives.contains(reliefPin)) {
                    pinnedClauseDirectives.add(reliefPin);
                }
            }
            if (isLimitationStatic(file.content())) {
                score += 30;
                String limitPin = "Section " + file.sectionNumber() + " [" + file.heading() + "]";
                if (!pinnedClauseDirectives.contains(limitPin)) {
                    pinnedClauseDirectives.add(limitPin);
                }
            }
            if (isJurisdictionOrVenueStatic(file.content())) {
                score += 30;
                String venuePin = "Section " + file.sectionNumber() + " [" + file.heading() + "]";
                if (!pinnedClauseDirectives.contains(venuePin)) {
                    pinnedClauseDirectives.add(venuePin);
                }
            }
            if (isOverridingOrAdditionalRemedyStatic(file.content())) {
                score += 20;
                String remedyPin = "Section " + file.sectionNumber() + " [" + file.heading() + "]";
                if (!pinnedClauseDirectives.contains(remedyPin)) {
                    pinnedClauseDirectives.add(remedyPin);
                }
            }

            // 5. True Heading & Clean Body Match (YAML Frontmatter Stripped)
            String cleanHeading = file.heading();
            String cleanBody = file.cleanBodyLower();
            for (String term : searchTerms) {
                if (cleanHeading.contains(term)) {
                    score += 10;
                }
                if (cleanBody.contains(term)) {
                    score += 2;
                }
            }

            fileScores.put(file, score);
        }

        List<StatuteFile> selectedFiles;
        if (!manifestMatchedFiles.isEmpty()) {
            selectedFiles = List.copyOf(manifestMatchedFiles);
        } else {
            boolean hasPositiveScores = fileScores.values().stream().anyMatch(s -> s > 0);
            List<StatuteFile> rankedFiles = eligibleFiles.stream()
                    .filter(f -> !hasPositiveScores || fileScores.getOrDefault(f, 0) > 0)
                    .sorted(Comparator.comparingInt((StatuteFile f) -> fileScores.getOrDefault(f, 0)).reversed())
                    .limit(10)
                    .toList();
            selectedFiles = rankedFiles;
        }

        return new InternalSelection(
                selectedFiles,
                List.copyOf(pinnedClauseDirectives),
                List.copyOf(assumptionCaveats),
                List.copyOf(winningStatutes),
                List.copyOf(excludedStatuteNames)
        );
    }

    private List<StatuteFile> attachPinnedAnchorsToFirstFile(InternalSelection selection) {
        List<StatuteFile> files = selection.selectedFiles();
        if (files.isEmpty() || selection.pinnedClauseDirectives().isEmpty()) {
            return files;
        }

        StringBuilder sb = new StringBuilder("### PINNED STATUTORY ANCHORS (BASE SECTIONS — DRILL DOWN TO EXACT NESTED SUB-CLAUSES (e.g., (i), (ii), (a)-(n)) & EXPLANATIONS IN THE FULL TEXT BELOW)\n");
        for (String pin : selection.pinnedClauseDirectives()) {
            sb.append("- ").append(pin).append("\n");
        }
        sb.append("\n---\n\n");

        StatuteFile first = files.get(0);
        String augmentedContent = sb.toString() + first.content();

        StatuteFile augmentedFirst = new StatuteFile(
                first.statuteName(),
                first.filename(),
                augmentedContent,
                first.searchContent(),
                first.sectionNumber(),
                first.heading(),
                first.cleanBodyLower(),
                first.definedTermsByClause(),
                first.isDefinitionSection(),
                first.isFilingSection(),
                first.isReliefSection(),
                first.isRegulatoryOrAdmin(),
                first.isProductLiabilitySection()
        );

        List<StatuteFile> result = new ArrayList<>(files.size());
        result.add(augmentedFirst);
        for (int i = 1; i < files.size(); i++) {
            result.add(files.get(i));
        }
        return Collections.unmodifiableList(result);
    }

    public RetrievalResult resolveStatutePackWithEvaluation(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        InternalSelection selection = evaluateAndSelectFiles(domain, keyTriggers, facts);
        List<StatuteFile> selectedWithPins = attachPinnedAnchorsToFirstFile(selection);

        StringBuilder sb = new StringBuilder();
        if (!selection.pinnedClauseDirectives().isEmpty() && !selection.selectedFiles().isEmpty()) {
            sb.append("### PINNED STATUTORY ANCHORS (BASE SECTIONS — DRILL DOWN TO EXACT NESTED SUB-CLAUSES (e.g., (i), (ii), (a)-(n)) & EXPLANATIONS IN THE FULL TEXT BELOW)\n");
            for (String pin : selection.pinnedClauseDirectives()) {
                sb.append("- ").append(pin).append("\n");
            }
            sb.append("\n---\n\n");
        }

        for (StatuteFile file : selection.selectedFiles()) {
            String block = file.content() + "\n\n---\n\n";
            if (sb.length() + block.length() > MAX_CONTEXT_CHARACTERS) {
                int remaining = MAX_CONTEXT_CHARACTERS - sb.length();
                if (remaining > 0) {
                    sb.append(block, 0, remaining);
                }
                break;
            }
            sb.append(block);
        }

        String result = sb.toString().trim();
        String statutePack = result.length() > MAX_CONTEXT_CHARACTERS
                ? result.substring(0, MAX_CONTEXT_CHARACTERS)
                : result;

        return new RetrievalResult(
                statutePack,
                selection.assumptionCaveats(),
                selection.includedStatuteNames(),
                selection.excludedStatuteNames(),
                selectedWithPins
        );
    }

    public List<StatuteFile> resolveRawStatuteFiles(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        InternalSelection selection = evaluateAndSelectFiles(domain, keyTriggers, facts);
        return attachPinnedAnchorsToFirstFile(selection);
    }

    public String resolveStatutePack(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        return resolveStatutePackWithEvaluation(domain, keyTriggers, facts).statutePack();
    }

    public String resolveStatutePack(LegalCategory domain, List<String> keyTriggers) {
        return resolveStatutePack(domain, keyTriggers, new ExtractedFactsDTO(null, null, null, null));
    }

    public String retrieveRelevantStatutes(String caseFacts, String legalDomain, ExtractedFactsDTO facts) {
        LegalCategory category = LegalCategory.fromStringSafe(legalDomain);
        List<String> triggers = (caseFacts != null && !caseFacts.isBlank())
                ? List.of(caseFacts)
                : Collections.emptyList();

        RetrievalResult result = resolveStatutePackWithEvaluation(category, triggers, facts);
        if (result.statutePack().isBlank()) {
            log.warn("Threshold starvation: No relevant statutes resolved for domain: {}", legalDomain);
            throw new IllegalStateException("INSUFFICIENT_STATUTES");
        }
        return result.statutePack();
    }

    public String retrieveRelevantStatutes(String caseFacts, String legalDomain) {
        return retrieveRelevantStatutes(caseFacts, legalDomain, new ExtractedFactsDTO(null, null, null, null));
    }
}