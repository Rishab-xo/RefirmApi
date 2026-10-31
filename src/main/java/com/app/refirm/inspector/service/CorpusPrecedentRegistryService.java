package com.app.refirm.inspector.service;

import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-performance, thread-safe registry of landmark Indian Supreme Court / High Court precedents.
 * Dynamically loaded from {@code classpath:okf-precedents/verified_precedents_catalog.json} across all 13 LegalDomains.
 * Eliminates all hardcoded case switches and provides domain-filtered and keyword-ranked precedent discovery.
 */
@Service
public class CorpusPrecedentRegistryService {

    private static final Logger log = LoggerFactory.getLogger(CorpusPrecedentRegistryService.class);
    private static final String CATALOG_RESOURCE_PATH = "okf-precedents/verified_precedents_catalog.json";

    private final ObjectMapper objectMapper;

    private final Map<String, CorpusPrecedentEntry> idMap = new ConcurrentHashMap<>();
    private final Map<LegalDomain, List<CorpusPrecedentEntry>> domainMap = new ConcurrentHashMap<>();
    private final List<CorpusPrecedentEntry> allPrecedents = new ArrayList<>();

    @Autowired
    public CorpusPrecedentRegistryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadCatalog();
    }

    public synchronized void loadCatalog() {
        idMap.clear();
        domainMap.clear();
        allPrecedents.clear();

        try {
            ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE_PATH);
            if (!resource.exists()) {
                log.warn("Corpus precedent catalog not found at classpath: {}", CATALOG_RESOURCE_PATH);
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                List<CorpusPrecedentEntry> entries = objectMapper.readValue(
                        is,
                        new TypeReference<List<CorpusPrecedentEntry>>() {}
                );

                if (entries != null) {
                    for (CorpusPrecedentEntry entry : entries) {
                        if (entry.authorityId() != null && !entry.authorityId().isBlank()) {
                            idMap.put(entry.authorityId().trim().toLowerCase(), entry);
                        }
                        allPrecedents.add(entry);

                        LegalDomain domain = entry.primaryDomain() != null ? entry.primaryDomain() : LegalDomain.UNKNOWN;
                        domainMap.computeIfAbsent(domain, k -> new ArrayList<>()).add(entry);

                        if (entry.secondaryDomains() != null) {
                            for (String secDomStr : entry.secondaryDomains()) {
                                LegalDomain secDom = LegalDomain.fromStringSafe(secDomStr);
                                if (secDom != LegalDomain.UNKNOWN && secDom != domain) {
                                    domainMap.computeIfAbsent(secDom, k -> new ArrayList<>()).add(entry);
                                }
                            }
                        }
                    }
                }

                log.info("Successfully loaded {} verified precedents across {} domains from {}",
                        allPrecedents.size(), domainMap.size(), CATALOG_RESOURCE_PATH);
            }
        } catch (Exception e) {
            log.error("Failed to load corpus precedent catalog from {}: {}", CATALOG_RESOURCE_PATH, e.getMessage(), e);
        }
    }

    /**
     * Universal lookup by authorityId (case-insensitive), primary citation, or case title.
     */
    public Optional<CorpusPrecedentEntry> findById(String authorityId) {
        if (authorityId == null || authorityId.isBlank()) {
            return Optional.empty();
        }
        String cleanId = authorityId.trim().toLowerCase();
        CorpusPrecedentEntry direct = idMap.get(cleanId);
        if (direct != null) {
            return Optional.of(direct);
        }

        // Secondary search by citation or title match
        for (CorpusPrecedentEntry entry : allPrecedents) {
            if (entry.primaryCitation() != null && entry.primaryCitation().equalsIgnoreCase(authorityId.trim())) {
                return Optional.of(entry);
            }
            if (entry.caseTitle() != null && entry.caseTitle().equalsIgnoreCase(authorityId.trim())) {
                return Optional.of(entry);
            }
        }

        return Optional.empty();
    }

    /**
     * Retrieve all precedents associated with the specified LegalDomain.
     */
    public List<CorpusPrecedentEntry> findByDomain(LegalDomain domain) {
        if (domain == null || domain == LegalDomain.UNKNOWN) {
            return Collections.unmodifiableList(allPrecedents);
        }
        List<CorpusPrecedentEntry> list = domainMap.get(domain);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<CorpusPrecedentEntry> findByDomain(String domainStr) {
        return findByDomain(LegalDomain.fromStringSafe(domainStr));
    }

    public List<CorpusPrecedentEntry> getAllPrecedents() {
        return Collections.unmodifiableList(allPrecedents);
    }

    /**
     * Resolves the primary LegalDomain for a workspace, filters corpus precedents,
     * and ranks them by matching trigger keywords against the workspace's statute pack,
     * narrative, and factual context.
     */
    public List<CorpusPrecedentEntry> findRelevantForWorkspace(LegalWorkspace workspace) {
        if (workspace == null) {
            return Collections.unmodifiableList(allPrecedents);
        }

        // 1. Resolve Target Domain
        LegalDomain domain = resolveWorkspaceDomain(workspace);

        // 2. Filter Candidate Precedents
        List<CorpusPrecedentEntry> candidates = domainMap.get(domain);
        if (candidates == null || candidates.isEmpty()) {
            candidates = allPrecedents;
        }

        // 3. Build Context Search Query for Keyword Ranking
        String query = buildContextQuery(workspace);

        if (query.isBlank()) {
            return Collections.unmodifiableList(candidates);
        }

        // 4. Rank Candidates by Trigger Keyword Matches
        List<ScoredPrecedent> scored = new ArrayList<>();
        int originalIndex = 0;
        for (CorpusPrecedentEntry entry : candidates) {
            int score = 0;
            if (entry.triggerKeywords() != null) {
                for (String kw : entry.triggerKeywords()) {
                    if (kw != null && !kw.isBlank() && query.contains(kw.toLowerCase().trim())) {
                        score += 2;
                    }
                }
            }
            // Additional bonus if primary citation or case title is mentioned in text
            if (!entry.primaryCitation().isBlank() && query.contains(entry.primaryCitation().toLowerCase())) {
                score += 5;
            }
            scored.add(new ScoredPrecedent(entry, score, originalIndex++));
        }

        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
        });

        return scored.stream().map(sp -> sp.entry).collect(Collectors.toList());
    }

    private LegalDomain resolveWorkspaceDomain(LegalWorkspace workspace) {
        if (workspace.getPrimaryDomain() != null && workspace.getPrimaryDomain() != com.app.refirm.triage.domain.LegalDomain.UNKNOWN) {
            return LegalDomain.fromStringSafe(workspace.getPrimaryDomain().name());
        }
        if (workspace.getFactLedger() != null) {
            if (workspace.getFactLedger().ruleEvaluationFacts() != null && workspace.getFactLedger().ruleEvaluationFacts().domain() != null) {
                return workspace.getFactLedger().ruleEvaluationFacts().domain();
            }
            if (workspace.getFactLedger().extractedFacts() != null && workspace.getFactLedger().extractedFacts().domain() != null) {
                return LegalDomain.fromStringSafe(workspace.getFactLedger().extractedFacts().domain().name());
            }
        }
        if (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().statutePack() != null) {
            for (StatutePin pin : workspace.getLatestDraftingContext().statutePack()) {
                String act = pin.actName() != null ? pin.actName().toUpperCase() : "";
                if (act.contains("CONSUMER")) return LegalDomain.CONSUMER;
                if (act.contains("PENAL") || act.contains("NYAYA") || act.contains("CRIMIN")) return LegalDomain.CRIMINAL;
                if (act.contains("COMPAN") || act.contains("INSOLVENCY")) return LegalDomain.CORPORATE;
                if (act.contains("INFORMATION TECH") || act.contains("CYBER")) return LegalDomain.CYBER;
                if (act.contains("RENT") || act.contains("TENAN")) return LegalDomain.TENANCY;
                if (act.contains("BANK") || act.contains("SARFAESI") || act.contains("NEGOTIABLE")) return LegalDomain.BANKING;
                if (act.contains("TRADE MARK") || act.contains("COPYRIGHT") || act.contains("PATENT")) return LegalDomain.IP;
                if (act.contains("INCOME TAX") || act.contains("GST")) return LegalDomain.TAXATION;
                if (act.contains("ENVIRONMENT") || act.contains("POLLUTION")) return LegalDomain.ENVIRONMENTAL;
                if (act.contains("INDUSTRIAL") || act.contains("LABOUR") || act.contains("EMPLOYMENT")) return LegalDomain.EMPLOYMENT;
                if (act.contains("MARRIAGE") || act.contains("DIVORCE") || act.contains("FAMILY")) return LegalDomain.FAMILY;
                if (act.contains("PROPERTY") || act.contains("TRANSFER OF PROPERTY") || act.contains("SPECIFIC RELIEF")) return LegalDomain.CIVIL_PROPERTY;
            }
        }
        return LegalDomain.CONSUMER;
    }

    private String buildContextQuery(LegalWorkspace workspace) {
        StringBuilder sb = new StringBuilder();
        if (workspace.getTitle() != null) {
            sb.append(workspace.getTitle()).append(" ");
        }

        DraftingContext ctx = workspace.getLatestDraftingContext();
        if (ctx != null) {
            if (ctx.userNarrative() != null) {
                sb.append(ctx.userNarrative()).append(" ");
            }
            if (ctx.statutePack() != null) {
                for (StatutePin pin : ctx.statutePack()) {
                    if (pin.actName() != null) sb.append(pin.actName()).append(" ");
                    if (pin.sectionNumber() != null) sb.append(pin.sectionNumber()).append(" ");
                    if (pin.operativeText() != null) sb.append(pin.operativeText()).append(" ");
                }
            }
        }

        CaseFactLedger ledger = workspace.getFactLedger();
        if (ledger != null) {
            if (ledger.extractedFacts() != null) {
                if (ledger.extractedFacts().factualAnomalies() != null) {
                    for (String anom : ledger.extractedFacts().factualAnomalies()) {
                        if (anom != null) sb.append(anom).append(" ");
                    }
                }
                if (ledger.extractedFacts().claimant() != null && ledger.extractedFacts().claimant().name() != null) {
                    sb.append(ledger.extractedFacts().claimant().name()).append(" ");
                }
                if (ledger.extractedFacts().oppositeParties() != null) {
                    for (var op : ledger.extractedFacts().oppositeParties()) {
                        if (op.name() != null) sb.append(op.name()).append(" ");
                    }
                }
            }
            if (ledger.ruleEvaluationFacts() != null) {
                if (ledger.ruleEvaluationFacts().counterpartyType() != null) {
                    sb.append(ledger.ruleEvaluationFacts().counterpartyType()).append(" ");
                }
                if (ledger.ruleEvaluationFacts().location() != null) {
                    sb.append(ledger.ruleEvaluationFacts().location()).append(" ");
                }
            }
        }

        return sb.toString().toLowerCase();
    }

    private record ScoredPrecedent(CorpusPrecedentEntry entry, int score, int originalIndex) {}
}
