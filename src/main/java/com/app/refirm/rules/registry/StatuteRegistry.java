package com.app.refirm.rules.registry;

import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.rules.model.StatuteRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.app.refirm.rules.model.MaintainabilityBarRule;

/**
 * Thread-safe, in-memory registry of all statutes loaded from {@code master_rules_engine.json}.
 * <p>
 * Loaded once at startup via {@link #loadRules()}. After the {@code @PostConstruct}
 * phase completes, both internal caches are effectively immutable — no write operations
 * occur post-startup, making all subsequent reads inherently thread-safe.
 * <p>
 * <strong>Fail-safe design:</strong> If {@code master_rules_engine.json} is absent from
 * the classpath, the registry starts empty with a warning instead of crashing the server.
 * Drop the file into {@code src/main/resources/} and restart to activate the engine.
 *
 * <h3>Cache structure</h3>
 * <ul>
 *   <li>{@code ruleCache} — flat map keyed by statute name for O(1) lookup by name.</li>
 *   <li>{@code domainRuleCache} — map keyed by {@link LegalDomain} for O(1) domain-scoped retrieval.</li>
 * </ul>
 */
@Service
public class StatuteRegistry {

    private static final Logger log = LoggerFactory.getLogger(StatuteRegistry.class);
    private static final String RULES_FILE_PATH = "master_rules_engine.json";

    /**
     * Flat statute lookup: statute name → StatuteRule.
     * ConcurrentHashMap guarantees safe publication of entries written during @PostConstruct
     * to all subsequent reader threads.
     */
    private final Map<String, StatuteRule> ruleCache = new ConcurrentHashMap<>();

    /**
     * Domain-scoped lookup: LegalDomain → (statute name → StatuteRule).
     */
    private final Map<LegalDomain, Map<String, StatuteRule>> domainRuleCache = new ConcurrentHashMap<>();

    /**
     * Domain-scoped maintainability and bar rules: Domain string → List of MaintainabilityBarRule.
     */
    private final Map<String, List<com.app.refirm.rules.model.MaintainabilityBarRule>> maintainabilityBarRulesCache = new ConcurrentHashMap<>();

    /**
     * Universal cross-cutting maintainability and bar rules (e.g. Limitation Act, Evidence Act / BSA).
     */
    private final List<com.app.refirm.rules.model.MaintainabilityBarRule> crossCuttingBarRules = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ObjectMapper objectMapper;

    /**
     * Constructor injection of Spring Boot's auto-configured {@link ObjectMapper}.
     * The same mapper has been customised by {@code JacksonConfig} to support
     * {@code @JsonEnumDefaultValue} and case-insensitive enum matching.
     *
     * @param objectMapper the shared, customised Jackson mapper
     */
    public StatuteRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Startup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads and indexes all statutes and maintainability/bar checks from {@code master_rules_engine.json} at application startup.
     */
    @PostConstruct
    public void loadRules() {
        log.info("StatuteRegistry: initiating load from classpath:{}", RULES_FILE_PATH);

        ClassPathResource resource = new ClassPathResource(RULES_FILE_PATH);

        if (!resource.exists()) {
            log.warn(
                "StatuteRegistry: '{}' not found on classpath. " +
                "The rules engine will be empty until the file is placed in src/main/resources/ and the server is restarted.",
                RULES_FILE_PATH
            );
            return;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(inputStream);

            if (rootNode == null || !rootNode.isObject() || rootNode.isEmpty()) {
                log.warn("StatuteRegistry: JSON parsed successfully but the root map is empty. No statutes loaded.");
                return;
            }

            int totalStatutes = 0;
            int totalBarRules = 0;

            for (java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> domainIt = rootNode.fields(); domainIt.hasNext(); ) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> domainEntry = domainIt.next();
                String domainKey = domainEntry.getKey();
                com.fasterxml.jackson.databind.JsonNode domainNode = domainEntry.getValue();

                if (domainNode == null || !domainNode.isObject()) {
                    continue;
                }

                // Safely resolve the domain key string → LegalDomain enum
                LegalDomain domain = LegalDomain.fromStringSafe(domainKey);

                // 1. Process maintainability_and_bar_checks array if present
                com.fasterxml.jackson.databind.JsonNode barChecksNode = domainNode.get("maintainability_and_bar_checks");
                if (barChecksNode != null && barChecksNode.isArray()) {
                    try {
                        List<com.app.refirm.rules.model.MaintainabilityBarRule> barRules = objectMapper.convertValue(
                                barChecksNode,
                                new TypeReference<List<com.app.refirm.rules.model.MaintainabilityBarRule>>() {}
                        );
                        if (barRules != null && !barRules.isEmpty()) {
                            maintainabilityBarRulesCache.put(domainKey.toUpperCase(), Collections.unmodifiableList(barRules));
                            if (domain != LegalDomain.UNKNOWN) {
                                maintainabilityBarRulesCache.put(domain.name(), Collections.unmodifiableList(barRules));
                            }
                            if ("CROSS_CUTTING".equalsIgnoreCase(domainKey)) {
                                crossCuttingBarRules.addAll(barRules);
                            }
                            totalBarRules += barRules.size();
                            log.debug("Loaded {} maintainability bar rules for domain '{}'", barRules.size(), domainKey);
                        }
                    } catch (Exception ex) {
                        log.warn("Could not deserialize maintainability_and_bar_checks for domain '{}': {}", domainKey, ex.getMessage());
                    }
                }

                // 2. Process statute definitions within this domain
                Map<String, StatuteRule> indexedStatutes = new HashMap<>();

                for (java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> statIt = domainNode.fields(); statIt.hasNext(); ) {
                    Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> statEntry = statIt.next();
                    String statuteKey = statEntry.getKey();

                    if ("maintainability_and_bar_checks".equalsIgnoreCase(statuteKey)) {
                        continue;
                    }

                    com.fasterxml.jackson.databind.JsonNode statNode = statEntry.getValue();
                    if (statNode == null || !statNode.isObject()) {
                        continue;
                    }

                    try {
                        StatuteRule rule = objectMapper.treeToValue(statNode, StatuteRule.class);
                        if (rule != null) {
                            ruleCache.put(statuteKey, rule);
                            if (rule.statuteName() != null && !rule.statuteName().isBlank()) {
                                ruleCache.put(rule.statuteName(), rule);
                            }
                            indexedStatutes.put(statuteKey, rule);
                            totalStatutes++;
                        }
                    } catch (Exception ex) {
                        log.warn("Could not deserialize statute '{}' in domain '{}': {}", statuteKey, domainKey, ex.getMessage());
                    }
                }

                if (!indexedStatutes.isEmpty()) {
                    domainRuleCache.merge(domain, indexedStatutes, (existing, incoming) -> {
                        Map<String, StatuteRule> merged = new HashMap<>(existing);
                        merged.putAll(incoming);
                        return merged;
                    });
                }
            }

            log.info(
                "StatuteRegistry: load complete. {} statute(s) indexed across {} domain(s), {} maintainability bar rule(s) registered.",
                totalStatutes,
                domainRuleCache.size(),
                totalBarRules
            );

        } catch (Exception e) {
            log.error(
                "StatuteRegistry: failed to parse '{}'. Cause: {}",
                RULES_FILE_PATH,
                e.getMessage(),
                e
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public Query API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves a statute rule by its key or formal statute name.
     * Convenience alias for {@link #findByStatuteName(String)}.
     *
     * @param statuteName lookup key or name of the statute
     * @return an {@link Optional} containing the matching {@link StatuteRule}, or empty if not found
     */
    public Optional<StatuteRule> getRule(String statuteName) {
        return findByStatuteName(statuteName);
    }

    /**
     * Performs an O(1) lookup of a statute by its name or key, with fallback matching
     * for space/underscore variants and case insensitivity.
     *
     * @param statuteName the statute lookup name as indexed in the JSON (e.g., {@code "Transfer of Property Act 1882"})
     * @return an {@link Optional} containing the matching {@link StatuteRule}, or empty if not found
     */
    public Optional<StatuteRule> findByStatuteName(String statuteName) {
        if (statuteName == null || statuteName.isBlank()) {
            return Optional.empty();
        }
        StatuteRule rule = ruleCache.get(statuteName);
        if (rule != null) {
            return Optional.of(rule);
        }
        String trimmed = statuteName.trim();
        String underscored = trimmed.replace(' ', '_');
        rule = ruleCache.get(underscored);
        if (rule != null) {
            return Optional.of(rule);
        }
        String spaced = trimmed.replace('_', ' ');
        rule = ruleCache.get(spaced);
        if (rule != null) {
            return Optional.of(rule);
        }
        for (Map.Entry<String, StatuteRule> entry : ruleCache.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(trimmed)
                    || entry.getKey().equalsIgnoreCase(underscored)
                    || entry.getKey().equalsIgnoreCase(spaced)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all statutes belonging to the specified legal domain.
     * The returned map is an unmodifiable view — callers cannot alter the registry state.
     *
     * @param domain the legal domain to query
     * @return an unmodifiable {@code Map<statuteName, StatuteRule>}, or an empty map if the domain has no statutes
     */
    public Map<String, StatuteRule> findByDomain(LegalDomain domain) {
        if (domain == null) {
            return Collections.emptyMap();
        }
        Map<String, StatuteRule> domainStatutes = domainRuleCache.getOrDefault(domain, Collections.emptyMap());
        return Collections.unmodifiableMap(domainStatutes);
    }

    /**
     * Returns the total number of statute rules currently held in the flat cache.
     * Useful for startup health checks and diagnostics.
     *
     * @return total count of loaded {@link StatuteRule} instances
     */
    public int getTotalLoadedStatutes() {
        return ruleCache.size();
    }

    /**
     * Returns maintainability and statutory bar rules declared for a specific domain.
     */
    public List<com.app.refirm.rules.model.MaintainabilityBarRule> getMaintainabilityBarRules(LegalDomain domain) {
        if (domain == null) return Collections.emptyList();
        return maintainabilityBarRulesCache.getOrDefault(domain.name(), Collections.emptyList());
    }

    /**
     * Returns maintainability and statutory bar rules declared for a specific domain key string.
     */
    public List<com.app.refirm.rules.model.MaintainabilityBarRule> getMaintainabilityBarRules(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) return Collections.emptyList();
        return maintainabilityBarRulesCache.getOrDefault(domainKey.toUpperCase().trim(), Collections.emptyList());
    }

    /**
     * Returns all universal cross-cutting maintainability and bar rules (e.g. Limitation Act, BSA/Evidence Act).
     */
    public List<com.app.refirm.rules.model.MaintainabilityBarRule> getCrossCuttingBarRules() {
        return Collections.unmodifiableList(crossCuttingBarRules);
    }

    /**
     * Returns all maintainability bar rules across all domains and cross-cutting rules.
     */
    public List<com.app.refirm.rules.model.MaintainabilityBarRule> getAllMaintainabilityBarRules() {
        Set<com.app.refirm.rules.model.MaintainabilityBarRule> all = new LinkedHashSet<>();
        maintainabilityBarRulesCache.values().forEach(all::addAll);
        all.addAll(crossCuttingBarRules);
        return List.copyOf(all);
    }
}
