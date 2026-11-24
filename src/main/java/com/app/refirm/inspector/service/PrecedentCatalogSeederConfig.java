package com.app.refirm.inspector.service;

import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.rules.model.LegalDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 4 of Step 5 (Ring 2):
 * Idempotent Precedent Catalog Seeder.
 * On application startup, reads the 26+ verified seed authorities from CorpusPrecedentRegistryService,
 * extracts canonical (Act, Section) tuples, classifies taxonomy topics/issue tags via PariMateriaCrosswalkService,
 * and idempotently populates Table 1 (legal_precedent_index) and Table 2 (legal_precedent_okf_content).
 *
 * Fault-Tolerant Invariant:
 * Completely enclosed in try-catch so unit tests and environments running without an active
 * PostgreSQL connection log a warning and fall back to in-memory catalog mode without failing context startup.
 */
@Configuration
public class PrecedentCatalogSeederConfig {

    private static final Logger log = LoggerFactory.getLogger(PrecedentCatalogSeederConfig.class);

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?i)(?:Section|Sec\\.?|S\\.?|Article|Art\\.?)\\s*([0-9]+[A-Za-z]?(?:\\([0-9A-Za-z]+\\))*)");

    private final CorpusPrecedentRegistryService corpusRegistry;
    private final PrecedentIndexRepository precedentRepository;
    private final PariMateriaCrosswalkService crosswalkService;

    @Autowired
    public PrecedentCatalogSeederConfig(
            CorpusPrecedentRegistryService corpusRegistry,
            PrecedentIndexRepository precedentRepository,
            PariMateriaCrosswalkService crosswalkService
    ) {
        this.corpusRegistry = corpusRegistry;
        this.precedentRepository = precedentRepository;
        this.crosswalkService = crosswalkService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedPrecedentCatalogOnStartup() {
        seedPrecedentCatalog();
    }

    /**
     * Public idempotent seeding method. Can be called on startup or invoked directly by tests.
     */
    public synchronized int seedPrecedentCatalog() {
        try {
            List<CorpusPrecedentEntry> entries = corpusRegistry.getAllPrecedents();
            if (entries == null || entries.isEmpty()) {
                log.info("PrecedentCatalogSeeder: No seed precedents found in registry.");
                return 0;
            }

            int seededCount = 0;
            int enrichedCount = 0;
            for (CorpusPrecedentEntry entry : entries) {
                if (entry.authorityId() == null || entry.authorityId().isBlank()) {
                    continue;
                }

                PrecedentIndexRecord record = buildIndexRecordFromSeed(entry);

                if (precedentRepository != null) {
                    // Dedup-Safe Guard: Check if an R2-backed record already exists
                    Optional<PrecedentIndexRecord> existingR2 = precedentRepository.findExistingR2Counterpart(
                            entry.authorityId(),
                            record.canonicalDedupKey(),
                            entry.caseTitle(),
                            record.decisionYear()
                    );

                    if (existingR2.isPresent()) {
                        PrecedentIndexRecord r2Record = existingR2.get();
                        if (!"IRAC_ENRICHED".equalsIgnoreCase(r2Record.enrichmentStatus())) {
                            precedentRepository.enrichR2RecordWithCuratedData(r2Record.authorityId(), record);
                            enrichedCount++;
                        }
                        // Skip inserting a duplicate stub
                        continue;
                    }

                    precedentRepository.upsertPrecedent(record, null, entry.pages(), null);
                    seededCount++;
                }
            }

            log.info("PrecedentCatalogSeeder: Successfully processed precedents (seeded: {}, enriched existing R2: {}).",
                    seededCount, enrichedCount);
            return seededCount;
        } catch (Exception e) {
            log.warn("PrecedentCatalogSeeder: Precedent database seeding skipped or deferred ({}). Continuing in in-memory mode.",
                    e.getMessage());
            return 0;
        }
    }

    /**
     * Transforms an in-memory CorpusPrecedentEntry into a rich PrecedentIndexRecord.
     */
    public PrecedentIndexRecord buildIndexRecordFromSeed(CorpusPrecedentEntry entry) {
        int decisionYear = extractDecisionYear(entry.judgmentDate(), entry.primaryCitation());
        String courtName = (entry.courtName() != null && !entry.courtName().isBlank())
                ? entry.courtName().trim()
                : "Supreme Court of India";
        String courtCode = resolveCourtCode(courtName);
        int courtTierWeight = resolveCourtTierWeight(courtName, entry.caseTitle(), entry.summary());

        List<String> actSectionTuples = extractCanonicalTuples(entry);

        StringBuilder textForClassification = new StringBuilder();
        if (entry.summary() != null) textForClassification.append(entry.summary()).append(" ");
        if (entry.keyPropositions() != null) {
            for (String p : entry.keyPropositions()) textForClassification.append(p).append(" ");
        }
        if (entry.iracBrief() != null && entry.iracBrief().ratioDecidendi() != null) {
            textForClassification.append(entry.iracBrief().ratioDecidendi()).append(" ");
        }

        PariMateriaCrosswalkService.TaxonomyClassification taxonomy =
                crosswalkService.classifyJudgmentTaxonomy(actSectionTuples, textForClassification.toString());

        String supportingPosture = inferSupportingPosture(entry);
        String canonicalDedupKey = PrecedentIndexRepository.buildCanonicalDedupKey(
                entry.caseTitle(), decisionYear, courtCode);

        return new PrecedentIndexRecord(
                entry.authorityId(),
                canonicalDedupKey,
                "", // parquetDocId
                "", // parquetShard
                "IRAC_ENRICHED",
                entry.caseTitle() != null ? entry.caseTitle() : "",
                courtCode,
                courtName,
                courtTierWeight,
                entry.judgmentDate() != null ? entry.judgmentDate() : "",
                decisionYear,
                entry.primaryCitation() != null ? entry.primaryCitation() : "",
                entry.equivalentCitations() != null ? entry.equivalentCitations() : List.of(),
                entry.primaryDomain() != null ? entry.primaryDomain() : LegalDomain.UNKNOWN,
                entry.secondaryDomains() != null ? entry.secondaryDomains() : List.of(),
                actSectionTuples,
                taxonomy.topicSlugs(),
                taxonomy.issueTags(),
                supportingPosture,
                "", // disposalNature
                entry.summary() != null ? entry.summary() : "",
                entry.keyPropositions() != null ? entry.keyPropositions() : List.of(),
                entry.triggerKeywords() != null ? entry.triggerKeywords() : List.of(),
                entry.iracBrief()
        );
    }

    /**
     * Extracts canonical (Act, Section) tuples from provisionsCited, triggerKeywords, and propositions.
     */
    public List<String> extractCanonicalTuples(CorpusPrecedentEntry entry) {
        Set<String> tuples = new LinkedHashSet<>();

        // 1. Provisions Cited in IRAC brief (most accurate and granular)
        if (entry.iracBrief() != null && entry.iracBrief().provisionsCited() != null) {
            for (String provision : entry.iracBrief().provisionsCited()) {
                if (provision == null || provision.isBlank()) continue;
                extractTuplesFromProvisionString(provision, entry.primaryDomain(), tuples);
            }
        }

        // 2. Trigger keywords (e.g. "section 438", "sec 439 crpc", "482 crpc")
        if (entry.triggerKeywords() != null) {
            for (String kw : entry.triggerKeywords()) {
                if (kw == null || kw.isBlank()) continue;
                extractTuplesFromProvisionString(kw, entry.primaryDomain(), tuples);
            }
        }

        // 3. Fallback extraction from summary and propositions if empty
        if (tuples.isEmpty() && entry.summary() != null) {
            extractTuplesFromProvisionString(entry.summary(), entry.primaryDomain(), tuples);
        }

        return new ArrayList<>(tuples);
    }

    private void extractTuplesFromProvisionString(String text, LegalDomain domain, Set<String> target) {
        Matcher secMatcher = SECTION_PATTERN.matcher(text);
        List<String> foundSections = new ArrayList<>();
        while (secMatcher.find()) {
            foundSections.add(secMatcher.group(1));
        }

        if (foundSections.isEmpty()) {
            return;
        }

        // Strip section references to isolate Act name
        String actPart = SECTION_PATTERN.matcher(text).replaceAll("");
        actPart = actPart.replaceAll("(?i)\\b(?:of\\s+the|of|under|and|&|in)\\b", " ");
        actPart = actPart.replaceAll("[,.;:]+", " ").trim();

        String canonicalAct = crosswalkService.normalizeActCode(actPart);
        if (canonicalAct.isEmpty() || canonicalAct.equals(actPart.replace(' ', '_'))) {
            // Check if text mentions Constitution explicitly
            if (text.toLowerCase(Locale.ROOT).contains("constitution")) {
                canonicalAct = "CONSTITUTION";
            } else {
                // Infer canonical Act from domain fallback
                canonicalAct = inferActFromDomain(domain);
            }
        }

        if (!canonicalAct.isEmpty()) {
            for (String sec : foundSections) {
                String tuple = crosswalkService.toCanonicalTuple(canonicalAct, sec);
                if (!tuple.isEmpty()) {
                    target.add(tuple);
                }
            }
        }
    }

    private String inferActFromDomain(LegalDomain domain) {
        if (domain == null) return "";
        return switch (domain) {
            case CRIMINAL -> "CRPC";
            case CORPORATE -> "COMPANIES_ACT";
            case CONSUMER -> "CPA_2019";
            case BANKING -> "NI_ACT";
            case CIVIL_PROPERTY -> "CPC";
            case CONSTITUTIONAL -> "CONSTITUTION";
            case CYBER -> "IT_ACT";
            case FAMILY -> "HMA";
            case IP -> "TRADE_MARKS_ACT_1999";
            case TENANCY -> "DELHI_RENT_CONTROL_ACT_1958";
            default -> "";
        };
    }

    private int extractDecisionYear(String judgmentDate, String primaryCitation) {
        if (judgmentDate != null && !judgmentDate.isBlank()) {
            Matcher m = YEAR_PATTERN.matcher(judgmentDate);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (primaryCitation != null && !primaryCitation.isBlank()) {
            Matcher m = YEAR_PATTERN.matcher(primaryCitation);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }
        return 2000;
    }

    private String resolveCourtCode(String courtName) {
        if (courtName == null) return "SC";
        String lower = courtName.toLowerCase(Locale.ROOT);
        if (lower.contains("high court")) return "HC";
        if (lower.contains("tribunal") || lower.contains("nclat") || lower.contains("nclt")) return "TRIBUNAL";
        return "SC";
    }

    private int resolveCourtTierWeight(String courtName, String caseTitle, String summary) {
        String combined = (courtName + " " + caseTitle + " " + summary).toLowerCase(Locale.ROOT);
        if (combined.contains("constitution bench") || combined.contains("9-judge") || combined.contains("7-judge") || combined.contains("5-judge")) {
            return 15;
        }
        if (courtName != null && courtName.toLowerCase(Locale.ROOT).contains("high court")) {
            return 8;
        }
        if (courtName != null && (courtName.toLowerCase(Locale.ROOT).contains("tribunal") || courtName.toLowerCase(Locale.ROOT).contains("commission"))) {
            return 5;
        }
        return 12; // Supreme Court 2-3 judge bench default
    }

    private String inferSupportingPosture(CorpusPrecedentEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.summary() != null) sb.append(entry.summary()).append(" ");
        if (entry.keyPropositions() != null) {
            for (String p : entry.keyPropositions()) sb.append(p).append(" ");
        }
        if (entry.iracBrief() != null && entry.iracBrief().finalOrderHeld() != null) {
            sb.append(entry.iracBrief().finalOrderHeld()).append(" ");
        }

        String lower = sb.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("anticipatory bail granted")
                || lower.contains("bail granted")
                || lower.contains("quashed")
                || lower.contains("discharge")
                || lower.contains("in favour of accused")
                || lower.contains("defence allowed")) {
            return "APPLICANT_OR_DEFENCE";
        }
        if (lower.contains("conviction upheld")
                || lower.contains("bail denied")
                || lower.contains("prosecution upheld")
                || lower.contains("in favour of claimant")
                || lower.contains("consumer complaint allowed")) {
            return "CLAIMANT_OR_PROSECUTION";
        }
        return "BOTH_SIDES";
    }
}
