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
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.domain.StatuteType;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import com.app.refirm.rules.model.MaintainabilityBarRule;
import com.app.refirm.rules.model.DateWindowTrigger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * High-performance, in-memory Open Knowledge Format (OKF) statute retriever.
 * Implements Three-Layer Deterministic Section Targeting and Universal 3-Bucket Cross-Check:
 * - Bucket 1: Affirmative Claim Sections matching cause of action & reliefs.
 * - Bucket 2: Adversarial Bar & Defeating Sections from master_rules_engine.json (with date-window triggers).
 * - Bucket 3: Universal Cross-Cutting Backbone (Limitation Act 1963, BSA 2023 / Evidence Act).
 */
@Service
public class LegalRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(LegalRetrievalService.class);
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StatuteRegistry statuteRegistry;
    private final StatuteRuleEvaluator statuteRuleEvaluator;

    private final Map<LegalCategory, List<StatuteFile>> okfCache = new ConcurrentHashMap<>();
    // Universal cross-cutting backbone cache (Limitation Act, BSA/Evidence Act)
    private final List<StatuteFile> crossCuttingFiles = new CopyOnWriteArrayList<>();
    // StatuteName -> CauseOfActionKey -> ManifestEntry
    private final Map<String, Map<String, ManifestEntry>> manifestCache = new ConcurrentHashMap<>();
    // Lazy, collision-safe multi-map catalog of all 159 enactments across domain folders
    private final Map<String, List<Resource>> actFolderCatalog = new ConcurrentHashMap<>();
    private volatile boolean catalogInitialized = false;

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
            List<StatuteFile> selectedFiles,
            List<String> triggeredBarDirectives
    ) {
        public RetrievalResult(
                String statutePack,
                List<String> assumptionCaveats,
                List<String> includedStatuteNames,
                List<String> excludedStatuteNames,
                List<StatuteFile> selectedFiles
        ) {
            this(statutePack, assumptionCaveats, includedStatuteNames, excludedStatuteNames, selectedFiles, List.of());
        }

        public RetrievalResult(
                String statutePack,
                List<String> assumptionCaveats,
                List<String> includedStatuteNames,
                List<String> excludedStatuteNames
        ) {
            this(statutePack, assumptionCaveats, includedStatuteNames, excludedStatuteNames, List.of(), List.of());
        }

        public List<StatutePin> toStatutePins() {
            if (selectedFiles == null) return List.of();
            return selectedFiles.stream()
                    .map(f -> new StatutePin(
                            f.statuteName(),
                            f.sectionNumber() != null && !f.sectionNumber().isBlank() ? "Section " + f.sectionNumber() : f.filename(),
                            StatuteType.OPERATIVE,
                            f.heading(),
                            f.content()
                    ))
                    .toList();
        }

        public static RetrievalResult empty() {
            return new RetrievalResult("", List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private record InternalSelection(
            List<StatuteFile> selectedFiles,
            List<String> pinnedClauseDirectives,
            List<String> assumptionCaveats,
            List<String> includedStatuteNames,
            List<String> excludedStatuteNames,
            List<String> triggeredBarDirectives
    ) {
        InternalSelection(
                List<StatuteFile> selectedFiles,
                List<String> pinnedClauseDirectives,
                List<String> assumptionCaveats,
                List<String> includedStatuteNames,
                List<String> excludedStatuteNames
        ) {
            this(selectedFiles, pinnedClauseDirectives, assumptionCaveats, includedStatuteNames, excludedStatuteNames, List.of());
        }
    }

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

        // Load universal cross-cutting OKF bundle (Limitation Act, BSA/Evidence Act)
        String crossMdPattern = "classpath*:okf-bundles/CROSS_CUTTING/**/*.md";
        try {
            Resource[] crossResources = resourceResolver.getResources(crossMdPattern);
            for (Resource res : crossResources) {
                try (InputStream is = res.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String filename = res.getFilename() != null ? res.getFilename() : "statute.md";
                    String statuteName = extractStatuteName(res);
                    crossCuttingFiles.add(new StatuteFile(
                            statuteName,
                            filename,
                            content,
                            content.toLowerCase()
                    ));
                    totalFilesLoaded++;
                } catch (Exception e) {
                    log.warn("Failed to read cross-cutting OKF file: {}", res.getFilename(), e);
                }
            }
            log.info("Cached {} cross-cutting OKF files.", crossCuttingFiles.size());
        } catch (Exception e) {
            log.warn("No CROSS_CUTTING directory found in okf-bundles: {}", e.getMessage());
        }

        // Load index.json manifests for CROSS_CUTTING
        String crossManifestPattern = "classpath*:okf-bundles/CROSS_CUTTING/**/index.json";
        try {
            Resource[] crossManifestResources = resourceResolver.getResources(crossManifestPattern);
            for (Resource res : crossManifestResources) {
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
                        log.info("Loaded CROSS_CUTTING index.json manifest for '{}' with {} causes of action.", statuteName, entries.size());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse CROSS_CUTTING index.json: {}", res.getURI(), e);
                }
            }
        } catch (Exception ignored) {}

        ensureClasspathCatalogInitialized();
        log.info("OKF Cache initialization complete. Total statutes cached: {}", totalFilesLoaded);
    }

    public void ensureClasspathCatalogInitialized() {
        if (!catalogInitialized) {
            synchronized (this) {
                if (!catalogInitialized) {
                    initClasspathCatalog();
                    catalogInitialized = true;
                }
            }
        }
    }

    private void initClasspathCatalog() {
        try {
            Resource[] resources = resourceResolver.getResources("classpath*:okf-bundles/*/*");
            if (resources != null && resources.length > 0) {
                for (Resource res : resources) {
                    registerCatalogResource(res);
                }
            }
            if (actFolderCatalog.isEmpty()) {
                Resource[] mdResources = resourceResolver.getResources("classpath*:okf-bundles/*/*/*.md");
                if (mdResources != null) {
                    for (Resource res : mdResources) {
                        registerCatalogResource(res);
                    }
                }
            }
            log.info("Classpath Act Catalog initialized with {} indexed keys.", actFolderCatalog.size());
        } catch (Exception e) {
            log.warn("Failed to initialize Classpath Act Catalog: {}", e.getMessage());
        }
    }

    private void registerCatalogResource(Resource res) {
        try {
            String uriStr = res.getURI().toString().replace('\\', '/');
            int idx = uriStr.indexOf("/okf-bundles/");
            if (idx == -1) return;
            String sub = uriStr.substring(idx + "/okf-bundles/".length());
            String[] parts = sub.split("/");
            if (parts.length >= 2) {
                String actSlug = parts[1];
                if (actSlug.isBlank() || actSlug.endsWith(".md") || actSlug.endsWith(".json")) return;

                Resource folderRes = res;
                if (uriStr.endsWith(".md") || uriStr.endsWith(".json")) {
                    try {
                        folderRes = res.createRelative(".");
                    } catch (Exception ignored) {}
                }

                String normSlug = normalizeActKey(actSlug);
                String humanTitle = com.app.refirm.inspector.service.InspectorService.humanizeActTitle(actSlug);
                String normTitle = normalizeActKey(humanTitle);

                indexCatalogFolder(normSlug, folderRes);
                indexCatalogFolder(normTitle, folderRes);
                indexCatalogFolder(actSlug.toLowerCase(), folderRes);
                indexCatalogFolder(actSlug, folderRes);
            }
        } catch (Exception ignored) {}
    }

    private void indexCatalogFolder(String key, Resource folderRes) {
        if (key == null || key.isBlank() || folderRes == null) return;
        List<Resource> list = actFolderCatalog.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        boolean alreadyHas = list.stream().anyMatch(r -> {
            try {
                return r.getURI().equals(folderRes.getURI());
            } catch (Exception e) {
                return false;
            }
        });
        if (!alreadyHas) {
            list.add(folderRes);
        }
    }

    private static String normalizeActKey(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public List<Resource> getCandidateFolders(String actName, String primaryDomain) {
        ensureClasspathCatalogInitialized();
        if (actName == null || actName.isBlank()) return List.of();

        String norm = normalizeActKey(actName);
        List<Resource> candidates = actFolderCatalog.get(norm);
        if (candidates == null || candidates.isEmpty()) {
            candidates = actFolderCatalog.get(actName.toLowerCase());
        }
        if (candidates == null || candidates.isEmpty()) {
            candidates = actFolderCatalog.get(actName);
        }
        if (candidates == null || candidates.isEmpty()) {
            for (Map.Entry<String, List<Resource>> entry : actFolderCatalog.entrySet()) {
                if (entry.getKey().contains(norm) || norm.contains(entry.getKey())) {
                    candidates = entry.getValue();
                    break;
                }
            }
        }

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Resource> unique = new LinkedHashMap<>();
        for (Resource r : candidates) {
            try {
                unique.putIfAbsent(r.getURI().toString().replace('\\', '/'), r);
            } catch (Exception e) {
                unique.putIfAbsent(r.toString(), r);
            }
        }
        List<Resource> result = new ArrayList<>(unique.values());

        String targetDomain = (primaryDomain != null) ? primaryDomain.toUpperCase().trim() : "";
        result.sort((r1, r2) -> {
            int p1 = folderPriority(r1, targetDomain);
            int p2 = folderPriority(r2, targetDomain);
            return Integer.compare(p1, p2);
        });

        return result;
    }

    private int folderPriority(Resource r, String targetDomain) {
        try {
            String uri = r.getURI().toString().replace('\\', '/').toUpperCase();
            if (!targetDomain.isBlank() && uri.contains("/" + targetDomain + "/")) {
                return 1;
            }
            if (uri.contains("/CROSS_CUTTING/")) {
                return 2;
            }
        } catch (Exception ignored) {}
        return 3;
    }

    public Optional<StatuteFile> resolveFromCatalog(String actName, String sectionId, String primaryDomain) {
        if (actName == null || actName.isBlank() || sectionId == null || sectionId.isBlank()) {
            return Optional.empty();
        }

        List<Resource> candidateFolders = getCandidateFolders(actName, primaryDomain);
        String cleanSec = sectionId.trim().toLowerCase();
        String rawSecNum = cleanSec.replace("section_", "").replace(".md", "");

        for (Resource folderRes : candidateFolders) {
            Optional<StatuteFile> opt = loadSectionFromFolder(folderRes, cleanSec, rawSecNum);
            if (opt.isPresent()) {
                return opt;
            }
        }

        return Optional.empty();
    }

    private Optional<StatuteFile> loadSectionFromFolder(Resource folderRes, String cleanSec, String rawSecNum) {
        try {
            List<String> candidateFilenames = List.of(
                    cleanSec,
                    cleanSec.endsWith(".md") ? cleanSec : cleanSec + ".md",
                    "section_" + rawSecNum + ".md",
                    "section_" + rawSecNum.toUpperCase() + ".md",
                    "section_" + rawSecNum + ".MD"
            );

            for (String fname : candidateFilenames) {
                try {
                    Resource secRes = folderRes.createRelative(fname);
                    if (secRes != null && secRes.isReadable() && secRes.contentLength() > 0) {
                        return Optional.of(createStatuteFile(secRes));
                    }
                } catch (Exception ignored) {}
            }

            try {
                java.io.File folderFile = folderRes.getFile();
                if (folderFile.isDirectory()) {
                    java.io.File[] files = folderFile.listFiles((dir, name) -> name.endsWith(".md"));
                    if (files != null) {
                        for (java.io.File f : files) {
                            String name = f.getName().toLowerCase();
                            String normName = name.replace(".md", "").replaceAll("[^a-z0-9]", "");
                            String normRaw = rawSecNum.replaceAll("[^a-z0-9]", "");
                            if (name.equalsIgnoreCase(cleanSec)
                                    || name.equalsIgnoreCase("section_" + rawSecNum + ".md")
                                    || normName.equals("section" + normRaw)
                                    || normName.equals(normRaw)
                                    || (normName.startsWith("section" + normRaw) && normName.length() <= normRaw.length() + 10)) {
                                String content = java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8);
                                String statuteName = extractStatuteName(folderRes);
                                return Optional.of(new StatuteFile(statuteName, f.getName(), content, content.toLowerCase()));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private StatuteFile createStatuteFile(Resource res) throws Exception {
        try (InputStream is = res.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String filename = res.getFilename() != null ? res.getFilename() : "statute.md";
            String statuteName = extractStatuteName(res);
            return new StatuteFile(statuteName, filename, content, content.toLowerCase());
        }
    }

    private List<MaintainabilityBarRule> loadFallbackRules(LegalCategory domain) {
        List<MaintainabilityBarRule> rules = new ArrayList<>();
        try (InputStream is = resourceResolver.getResource("classpath:master_rules_engine.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            if (domain != null && domain != LegalCategory.UNKNOWN) {
                JsonNode domainNode = root.get(domain.name());
                if (domainNode != null && domainNode.has("maintainability_and_bar_checks")) {
                    List<MaintainabilityBarRule> dRules = objectMapper.convertValue(
                            domainNode.get("maintainability_and_bar_checks"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<MaintainabilityBarRule>>() {}
                    );
                    if (dRules != null) rules.addAll(dRules);
                }
            }
            JsonNode crossNode = root.get("CROSS_CUTTING");
            if (crossNode != null && crossNode.has("maintainability_and_bar_checks")) {
                List<MaintainabilityBarRule> cRules = objectMapper.convertValue(
                        crossNode.get("maintainability_and_bar_checks"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<MaintainabilityBarRule>>() {}
                );
                if (cRules != null) rules.addAll(cRules);
            }
        } catch (Exception e) {
            log.warn("Could not load fallback rules from master_rules_engine.json: {}", e.getMessage());
        }
        return rules;
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
        String prompt = (keyTriggers != null && !keyTriggers.isEmpty()) ? String.join(" ", keyTriggers) : "";
        return evaluateAndSelectFiles(domain, keyTriggers, facts, prompt);
    }

    private InternalSelection evaluateAndSelectFiles(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts, String rawPrompt) {
        if (domain == null || domain == LegalCategory.UNKNOWN) {
            return new InternalSelection(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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
                String def7 = file.definedTermsByClause().get("7");
                if (def7 != null && def7.toLowerCase().contains("consumer")) {
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

        List<StatuteFile> selectedFiles = new ArrayList<>();
        if (!manifestMatchedFiles.isEmpty()) {
            selectedFiles.addAll(manifestMatchedFiles);
        } else {
            boolean hasPositiveScores = fileScores.values().stream().anyMatch(s -> s > 0);
            List<StatuteFile> rankedFiles = eligibleFiles.stream()
                    .filter(f -> !hasPositiveScores || fileScores.getOrDefault(f, 0) > 0)
                    .sorted(Comparator.comparingInt((StatuteFile f) -> fileScores.getOrDefault(f, 0)).reversed())
                    .limit(10)
                    .toList();
            selectedFiles.addAll(rankedFiles);
        }

        List<String> triggeredBarDirectives = new ArrayList<>();
        String combinedQuery = ((keyTriggers != null ? String.join(" ", keyTriggers) : "") + " " + (rawPrompt != null ? rawPrompt : "")).toLowerCase();
        List<LocalDate> parsedDates = parseDatesFromText(combinedQuery);

        // ═════════════════════════════════════════════════════════════════════
        // BUCKET 2 & 3: ADVERSARIAL BAR, MAINTAINABILITY & CROSS-CUTTING SECTIONS
        // Evaluates active domain & universal cross-cutting rules purely from master_rules_engine.json
        // ═════════════════════════════════════════════════════════════════════
        List<MaintainabilityBarRule> rulesToEvaluate = new ArrayList<>();
        if (statuteRegistry != null) {
            if (domain != null && domain != LegalCategory.UNKNOWN) {
                rulesToEvaluate.addAll(statuteRegistry.getMaintainabilityBarRules(domain.name()));
            }
            rulesToEvaluate.addAll(statuteRegistry.getCrossCuttingBarRules());
        }
        if (rulesToEvaluate.isEmpty()) {
            rulesToEvaluate.addAll(loadFallbackRules(domain));
        }

        for (MaintainabilityBarRule barRule : rulesToEvaluate) {
            boolean dateMatched = false;
            if (barRule.dateWindowTrigger() != null) {
                DateWindowTrigger dwt = barRule.dateWindowTrigger();
                if (dwt.startDate() != null && dwt.endDate() != null) {
                    try {
                        LocalDate start = LocalDate.parse(dwt.startDate().trim());
                        LocalDate end = LocalDate.parse(dwt.endDate().trim());
                        for (LocalDate d : parsedDates) {
                            if (!d.isBefore(start) && !d.isAfter(end)) {
                                dateMatched = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            boolean keywordMatched = false;
            if (barRule.triggerKeywords() != null) {
                for (String kw : barRule.triggerKeywords()) {
                    if (kw != null && !kw.isBlank() && combinedQuery.contains(kw.toLowerCase().trim())) {
                        keywordMatched = true;
                        break;
                    }
                }
            }

            if (dateMatched || keywordMatched) {
                String directive = barRule.resolveEffectiveDirective();
                if (!directive.isBlank() && !triggeredBarDirectives.contains(directive)) {
                    triggeredBarDirectives.add(directive);
                }

                for (String secId : barRule.sectionIds()) {
                    Optional<StatuteFile> optFile = resolveFromCatalog(barRule.actName(), secId, domain != null ? domain.name() : null);
                    if (optFile.isEmpty()) {
                        optFile = findStatuteFile(barRule.actName(), secId, domainFiles);
                    }
                    if (optFile.isPresent()) {
                        StatuteFile barFile = optFile.get();
                        boolean alreadyPresent = selectedFiles.stream().anyMatch(f ->
                                f.filename().equalsIgnoreCase(barFile.filename())
                                        && f.statuteName().equalsIgnoreCase(barFile.statuteName()));
                        if (!alreadyPresent) {
                            selectedFiles.add(barFile);
                            if (!winningStatutes.contains(barFile.statuteName())) {
                                winningStatutes.add(barFile.statuteName());
                            }
                            String pinNotice = !directive.isBlank() ? directive : "Section " + barFile.sectionNumber() + " [" + barFile.heading() + "]";
                            if (!pinnedClauseDirectives.contains(pinNotice)) {
                                pinnedClauseDirectives.add(pinNotice);
                            }
                        }
                    }
                }
            }
        }

        return new InternalSelection(
                List.copyOf(selectedFiles),
                List.copyOf(pinnedClauseDirectives),
                List.copyOf(assumptionCaveats),
                List.copyOf(winningStatutes),
                List.copyOf(excludedStatuteNames),
                List.copyOf(triggeredBarDirectives)
        );
    }

    private void addCrossDomainStatutePin(
            String actName,
            String sectionFile,
            String directive,
            List<StatuteFile> domainFiles,
            List<StatuteFile> selectedFiles,
            List<String> pinnedClauseDirectives,
            Set<String> winningStatutes
    ) {
        findStatuteFile(actName, sectionFile, domainFiles).ifPresent(f -> {
            boolean exists = selectedFiles.stream().anyMatch(sf ->
                    sf.filename().equalsIgnoreCase(f.filename()) && sf.statuteName().equalsIgnoreCase(f.statuteName()));
            if (!exists) {
                selectedFiles.add(f);
                if (directive != null && !directive.isBlank() && !pinnedClauseDirectives.contains(directive)) {
                    pinnedClauseDirectives.add(directive);
                }
                if (winningStatutes != null && f.statuteName() != null && !winningStatutes.contains(f.statuteName())) {
                    winningStatutes.add(f.statuteName());
                }
            }
        });
    }

    public Optional<StatuteFile> findStatuteFile(String actName, String sectionId, List<StatuteFile> preferredFiles) {
        if (sectionId == null || sectionId.isBlank()) return Optional.empty();
        String cleanSec = sectionId.trim().toLowerCase();
        String rawSecNum = cleanSec.replace("section_", "").replace(".md", "");

        // 1. Search in preferred files (domain files)
        if (preferredFiles != null) {
            for (StatuteFile f : preferredFiles) {
                if (matchesFile(f, actName, cleanSec, rawSecNum)) {
                    return Optional.of(f);
                }
            }
        }

        // 2. Search via lazy classpath multi-folder catalog
        Optional<StatuteFile> catalogMatch = resolveFromCatalog(actName, sectionId, null);
        if (catalogMatch.isPresent()) {
            return catalogMatch;
        }

        // 3. Search in crossCuttingFiles
        for (StatuteFile f : crossCuttingFiles) {
            if (matchesFile(f, actName, cleanSec, rawSecNum)) {
                return Optional.of(f);
            }
        }

        // 4. Search across all okfCache
        for (List<StatuteFile> list : okfCache.values()) {
            for (StatuteFile f : list) {
                if (matchesFile(f, actName, cleanSec, rawSecNum)) {
                    return Optional.of(f);
                }
            }
        }

        return Optional.empty();
    }

    public Optional<StatuteFile> findStatuteFile(String actName, String sectionId) {
        return findStatuteFile(actName, sectionId, null);
    }

    private boolean matchesFile(StatuteFile f, String actName, String cleanSec, String rawSecNum) {
        String fname = f.filename() != null ? f.filename().toLowerCase() : "";
        String fsec = f.sectionNumber() != null ? f.sectionNumber().toLowerCase() : "";
        String fact = f.statuteName() != null ? f.statuteName().toLowerCase() : "";

        String normFact = fact.replaceAll("[^a-z0-9]", "");
        String normAct = (actName != null) ? actName.toLowerCase().replaceAll("[^a-z0-9]", "") : "";

        boolean actMatches = (actName == null || actName.isBlank())
                || normFact.contains(normAct)
                || normAct.contains(normFact)
                || fact.contains(actName.toLowerCase().replace("_", " "))
                || fact.contains(actName.toLowerCase());

        String normFname = fname.replace(".md", "").replaceAll("[^a-z0-9]", "");
        String normSec = cleanSec.replace(".md", "").replaceAll("[^a-z0-9]", "");
        String normRawSec = rawSecNum.replaceAll("[^a-z0-9]", "");

        boolean secMatches = fname.equals(cleanSec)
                || fname.equals("section_" + rawSecNum + ".md")
                || fname.startsWith("section_" + rawSecNum + "_")
                || fsec.equalsIgnoreCase(rawSecNum)
                || normFname.equals(normSec)
                || normFname.equals("section" + normRawSec)
                || (fname.contains(rawSecNum) && fname.endsWith(".md"));

        return actMatches && secMatches;
    }

    private static final Pattern DATE_PATTERN_DMY = Pattern.compile("\\b(\\d{1,2})[.-](\\d{1,2})[.-](\\d{4})\\b");
    private static final Pattern DATE_PATTERN_SLASH = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern DATE_PATTERN_ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern DATE_PATTERN_TEXTUAL = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[.,]?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static List<LocalDate> parseDatesFromText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<LocalDate> dates = new ArrayList<>();

        // 1. DD.MM.YYYY or DD-MM-YYYY
        Matcher m1 = DATE_PATTERN_DMY.matcher(text);
        while (m1.find()) {
            try {
                int day = Integer.parseInt(m1.group(1));
                int month = Integer.parseInt(m1.group(2));
                int year = Integer.parseInt(m1.group(3));
                dates.add(LocalDate.of(year, month, day));
            } catch (DateTimeException ignored) {}
        }

        // 2. DD/MM/YYYY
        Matcher m2 = DATE_PATTERN_SLASH.matcher(text);
        while (m2.find()) {
            try {
                int day = Integer.parseInt(m2.group(1));
                int month = Integer.parseInt(m2.group(2));
                int year = Integer.parseInt(m2.group(3));
                dates.add(LocalDate.of(year, month, day));
            } catch (DateTimeException ignored) {}
        }

        // 3. YYYY-MM-DD
        Matcher m3 = DATE_PATTERN_ISO.matcher(text);
        while (m3.find()) {
            try {
                int year = Integer.parseInt(m3.group(1));
                int month = Integer.parseInt(m3.group(2));
                int day = Integer.parseInt(m3.group(3));
                dates.add(LocalDate.of(year, month, day));
            } catch (DateTimeException ignored) {}
        }

        // 4. DD Month YYYY
        Matcher m4 = DATE_PATTERN_TEXTUAL.matcher(text);
        while (m4.find()) {
            try {
                int day = Integer.parseInt(m4.group(1));
                String monthStr = m4.group(2).toLowerCase();
                int year = Integer.parseInt(m4.group(3));
                int month = resolveMonthNumber(monthStr);
                if (month > 0) {
                    dates.add(LocalDate.of(year, month, day));
                }
            } catch (DateTimeException ignored) {}
        }

        return Collections.unmodifiableList(dates);
    }

    private static int resolveMonthNumber(String monthStr) {
        if (monthStr.startsWith("jan")) return 1;
        if (monthStr.startsWith("feb")) return 2;
        if (monthStr.startsWith("mar")) return 3;
        if (monthStr.startsWith("apr")) return 4;
        if (monthStr.startsWith("may")) return 5;
        if (monthStr.startsWith("jun")) return 6;
        if (monthStr.startsWith("jul")) return 7;
        if (monthStr.startsWith("aug")) return 8;
        if (monthStr.startsWith("sep")) return 9;
        if (monthStr.startsWith("oct")) return 10;
        if (monthStr.startsWith("nov")) return 11;
        if (monthStr.startsWith("dec")) return 12;
        return -1;
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

    public RetrievalResult resolveStatutePackWithEvaluation(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts, String rawPrompt) {
        InternalSelection selection = evaluateAndSelectFiles(domain, keyTriggers, facts, rawPrompt);
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
                selectedWithPins,
                selection.triggeredBarDirectives()
        );
    }

    public RetrievalResult resolveStatutePackWithEvaluation(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        String prompt = (keyTriggers != null && !keyTriggers.isEmpty()) ? String.join(" ", keyTriggers) : "";
        return resolveStatutePackWithEvaluation(domain, keyTriggers, facts, prompt);
    }

    public List<StatuteFile> resolveRawStatuteFiles(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts, String rawPrompt) {
        InternalSelection selection = evaluateAndSelectFiles(domain, keyTriggers, facts, rawPrompt);
        return attachPinnedAnchorsToFirstFile(selection);
    }

    public List<StatuteFile> resolveRawStatuteFiles(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        String prompt = (keyTriggers != null && !keyTriggers.isEmpty()) ? String.join(" ", keyTriggers) : "";
        return resolveRawStatuteFiles(domain, keyTriggers, facts, prompt);
    }

    public String resolveStatutePack(LegalCategory domain, List<String> keyTriggers, ExtractedFactsDTO facts) {
        return resolveStatutePackWithEvaluation(domain, keyTriggers, facts).statutePack();
    }

    public String resolveStatutePack(LegalCategory domain, List<String> keyTriggers) {
        return resolveStatutePack(domain, keyTriggers, new ExtractedFactsDTO(null, null, null, null));
    }

    public RetrievalResult retrieveUniversal3BucketStatutes(
            String legalDomain,
            String userPrompt,
            List<String> keyTriggers,
            ExtractedFactsDTO facts
    ) {
        LegalCategory domainCat = LegalCategory.fromStringSafe(legalDomain);
        List<String> triggers = new ArrayList<>();
        if (keyTriggers != null) triggers.addAll(keyTriggers);
        if (userPrompt != null && !userPrompt.isBlank() && !triggers.contains(userPrompt)) {
            triggers.add(userPrompt);
        }
        return resolveStatutePackWithEvaluation(domainCat, triggers, facts, userPrompt);
    }

    public RetrievalResult retrieveUniversal3BucketStatutes(
            String query,
            LegalDomain domain,
            String factLedgerContext,
            int maxTokens
    ) {
        String domainStr = domain != null ? domain.name() : "";
        ExtractedFactsDTO facts = new ExtractedFactsDTO(null, null, null, null);
        List<String> triggers = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            triggers.add(query);
        }
        if (factLedgerContext != null && !factLedgerContext.isBlank()) {
            triggers.add(factLedgerContext);
        }
        return retrieveUniversal3BucketStatutes(domainStr, query, triggers, facts);
    }

    public RetrievalResult retrieveUniversal3BucketStatutes(
            String legalDomain,
            String userPrompt,
            List<String> keyTriggers
    ) {
        return retrieveUniversal3BucketStatutes(legalDomain, userPrompt, keyTriggers, new ExtractedFactsDTO(null, null, null, null));
    }

    public RetrievalResult retrieveUniversal3BucketStatutes(
            String legalDomain,
            String userPrompt
    ) {
        return retrieveUniversal3BucketStatutes(legalDomain, userPrompt, List.of(), new ExtractedFactsDTO(null, null, null, null));
    }

    public String retrieveRelevantStatutes(String caseFacts, String legalDomain, ExtractedFactsDTO facts) {
        LegalCategory category = LegalCategory.fromStringSafe(legalDomain);
        List<String> triggers = (caseFacts != null && !caseFacts.isBlank())
                ? List.of(caseFacts)
                : Collections.emptyList();

        RetrievalResult result = resolveStatutePackWithEvaluation(category, triggers, facts, caseFacts);
        if (result.statutePack().isBlank()) {
            log.warn("Threshold starvation: No relevant statutes resolved for domain: {}", legalDomain);
            throw new IllegalStateException("INSUFFICIENT_STATUTES");
        }
        return result.statutePack();
    }

    public String retrieveRelevantStatutes(String caseFacts, String legalDomain) {
        return retrieveRelevantStatutes(caseFacts, legalDomain, new ExtractedFactsDTO(null, null, null, null));
    }

    public List<StatutePin> retrieveCrossStatutePinsForContention(String contention, LegalDomain primaryDomain, int limit) {
        return retrieveCrossStatutePinsForContention(contention, primaryDomain != null ? primaryDomain.name() : null, limit);
    }

    /**
     * Dynamically searches across all 13 domains in okfCache for statutory provisions,
     * exceptions, or statutory bars matching an opposite-party contention.
     */
    public List<StatutePin> retrieveCrossStatutePinsForContention(String contention, String primaryDomain, int limit) {
        if (contention == null || contention.isBlank()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = Arrays.stream(contention.toLowerCase().split("[^a-zA-Z0-9]+"))
                .filter(t -> t.length() >= 2 && !STOP_WORDS.contains(t))
                .toList();

        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<StatuteFile> matches = new ArrayList<>();
        Map<StatuteFile, Integer> scores = new HashMap<>();

        for (Map.Entry<LegalCategory, List<StatuteFile>> entry : okfCache.entrySet()) {
            for (StatuteFile file : entry.getValue()) {
                int score = 0;
                String heading = file.heading() != null ? file.heading().toLowerCase() : "";
                String sec = file.sectionNumber() != null ? file.sectionNumber().toLowerCase() : "";
                String actName = file.statuteName() != null ? file.statuteName().toLowerCase() : "";
                String searchContent = file.searchContent() != null ? file.searchContent() : "";

                for (String token : queryTokens) {
                    if (sec.equals(token)) score += 20;
                    if (actName.contains(token)) score += 10;
                    if (heading.contains(token)) score += 8;
                    if (searchContent.contains(token)) score += 1;
                }

                if (score > 10) {
                    scores.put(file, score);
                    matches.add(file);
                }
            }
        }

        matches.sort((a, b) -> Integer.compare(scores.getOrDefault(b, 0), scores.getOrDefault(a, 0)));

        return matches.stream()
                .limit(limit)
                .map(f -> new StatutePin(
                        f.statuteName(),
                        f.sectionNumber() != null && !f.sectionNumber().isBlank() ? "Section " + f.sectionNumber() : f.filename(),
                        StatuteType.OPERATIVE,
                        f.heading(),
                        f.content()
                ))
                .toList();
    }
}