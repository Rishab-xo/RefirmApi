package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.rules.model.StatuteRule;
import com.app.refirm.rules.registry.StatuteRegistry;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.pipeline.ForumResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Primary
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DslForumResolver implements ForumResolver {

    private final StatuteRegistry statuteRegistry;

    public DslForumResolver() {
        this(null);
    }

    @Autowired
    public DslForumResolver(@Nullable StatuteRegistry statuteRegistry) {
        this.statuteRegistry = statuteRegistry;
    }

    @Override
    public boolean supports(com.app.refirm.triage.domain.LegalDomain domain) {
        if (domain == null) return false;
        LegalDomain rulesDomain = LegalDomain.fromStringSafe(domain.name());
        if (rulesDomain == LegalDomain.CONSUMER) return true;
        return rulesDomain != LegalDomain.UNKNOWN && statuteRegistry != null && !statuteRegistry.findByDomain(rulesDomain).isEmpty();
    }

    @Override
    public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
        if (facts == null || facts.domain() == null) {
            return new ResolvedForum("Appropriate District Commission / Court", "Standard Statutory Filing", "Default Fallback");
        }

        double financialLoss = facts.estimatedFinancialLoss() != null ? facts.estimatedFinancialLoss() : 0.0;
        String locationSuffix = (userLocation != null && !userLocation.isBlank()) ? " (" + userLocation + ")" : "";

        LegalDomain rulesDomain = LegalDomain.fromStringSafe(facts.domain().name());

        // 1. Explicit Deterministic Consumer Routing (CPA 2019 / 2021 Tiers)
        if (rulesDomain == LegalDomain.CONSUMER) {
            if (financialLoss <= 5_000_000.0) { // <= 50 Lakhs
                return new ResolvedForum(
                        "District Consumer Disputes Redressal Commission (DCDRC)" + locationSuffix,
                        "Section 35 Consumer Complaint via e-Daakhil",
                        "District Commission pecuniary limit (<= Rs. 50 Lakhs)"
                );
            } else if (financialLoss <= 20_000_000.0) { // 50 Lakhs to 2 Crores
                return new ResolvedForum(
                        "State Consumer Disputes Redressal Commission (SCDRC)" + locationSuffix,
                        "Section 47 Consumer Complaint",
                        "State Commission pecuniary limit (Rs. 50 Lakhs to Rs. 2 Crores)"
                );
            } else {
                return new ResolvedForum(
                        "National Consumer Disputes Redressal Commission (NCDRC), New Delhi",
                        "Section 58 Consumer Complaint",
                        "Claim of Rs. " + financialLoss + " exceeds Rs. 2 Crores"
                );
            }
        }

        // 1b. Deterministic Real Estate / RERA Routing
        if (facts.domain() == com.app.refirm.triage.domain.LegalDomain.REAL_ESTATE) {
            return new ResolvedForum(
                    "Real Estate Regulatory Authority (RERA)" + locationSuffix,
                    "Section 31 Complaint via State RERA Portal",
                    "Statutory jurisdiction under Section 31 of RERA 2016"
            );
        }

        Map<String, StatuteRule> domainRules = (statuteRegistry != null) ? statuteRegistry.findByDomain(rulesDomain) : Map.of();

        // 2. Fuzzy Match the LLM's Extracted Statute Name against the JSON Keys
        StatuteRule matchedRule = null;
        if (domainRules != null && !domainRules.isEmpty() && facts.factualAnomalies() != null) {
            for (String anomaly : facts.factualAnomalies()) {
                String cleanAnomaly = anomaly.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (cleanAnomaly.isEmpty()) continue;
                for (StatuteRule rule : domainRules.values()) {
                    String cleanRuleName = rule.statuteName() != null ? rule.statuteName().toLowerCase().replaceAll("[^a-z0-9]", "") : "";
                    if (!cleanRuleName.isEmpty() && (cleanRuleName.contains(cleanAnomaly) || cleanAnomaly.contains(cleanRuleName))) {
                        matchedRule = rule;
                        break;
                    }
                }
                if (matchedRule != null) break;
            }
        }

        if (domainRules != null && !domainRules.isEmpty()) {
            // Fallback: If fuzzy match fails, grab the first statute in the domain with a designated tribunal
            if (matchedRule == null || matchedRule.jurisdictionalRouting() == null) {
                matchedRule = domainRules.values().stream()
                        .filter(r -> r.jurisdictionalRouting() != null && r.jurisdictionalRouting().designatedTribunal() != null)
                        .findFirst().orElse(null);
            }
        }

        if (matchedRule == null) {
            return new ResolvedForum("Appropriate Tribunal" + locationSuffix, "Standard Filing", "No routing data found");
        }

        // Special handling for RERA in Civil Property domain
        if (matchedRule.statuteName() != null && matchedRule.statuteName().toLowerCase().contains("real estate")) {
            return new ResolvedForum(
                    "Real Estate Regulatory Authority (RERA)" + locationSuffix,
                    "Section 31 Complaint via State RERA Portal",
                    "Statutory jurisdiction under Section 31 of RERA 2016"
            );
        }

        // 3. Dynamic Pecuniary Routing (Supports both Tiers and Flat Limits)
        var pecuniary = matchedRule.pecuniaryJurisdiction();
        if (pecuniary != null && Boolean.TRUE.equals(pecuniary.hasLimit())) {

            // Scenario A: Tiered Limits (e.g., Consumer Protection Act)
            if (pecuniary.tribunalTiers() != null && !pecuniary.tribunalTiers().isEmpty()) {
                for (var tier : pecuniary.tribunalTiers()) {
                    double min = tier.minimumThreshold() != null ? tier.minimumThreshold() : 0.0;
                    double max = tier.maximumThreshold() != null ? tier.maximumThreshold() : Double.MAX_VALUE;
                    if (financialLoss >= min && financialLoss <= max) {
                        return new ResolvedForum(tier.designatedTribunal() + locationSuffix, "Statutory Complaint", "Dynamic Tier Match from " + matchedRule.statuteName());
                    }
                }
            }
            // Scenario B: Flat Limits (e.g., Competition Act, Registration Act)
            else {
                double min = pecuniary.minimumThreshold() != null ? pecuniary.minimumThreshold() : 0.0;
                double max = pecuniary.maximumThreshold() != null ? pecuniary.maximumThreshold() : Double.MAX_VALUE;
                if (financialLoss >= min && financialLoss <= max) {
                    return new ResolvedForum(matchedRule.jurisdictionalRouting().designatedTribunal() + locationSuffix, "Statutory Complaint", "Dynamic Flat Limit Match from " + matchedRule.statuteName());
                } else {
                    return new ResolvedForum("Appropriate Appellate Tribunal" + locationSuffix, "Standard Filing", "Primary Limit Exceeded");
                }
            }
        }

        // 4. Final Fallback: Non-Pecuniary Tribunal
        String defaultTribunal = matchedRule.jurisdictionalRouting() != null ? matchedRule.jurisdictionalRouting().designatedTribunal() : null;
        return new ResolvedForum(
                (defaultTribunal != null ? defaultTribunal : "Appropriate Civil Court") + locationSuffix,
                "Standard Filing",
                "Default Routing from " + matchedRule.statuteName()
        );
    }
}
