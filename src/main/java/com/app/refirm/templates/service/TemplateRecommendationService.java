package com.app.refirm.templates.service;

import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateRecommendation;
import com.app.refirm.templates.domain.TemplateStage;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Smart Procedural Recommender that analyzes workspace domain, client perspective,
 * procedural litigation stage, and statutory trigger keywords to rank suitable templates.
 */
@Service
public class TemplateRecommendationService {

    private final TemplateRegistryService templateRegistryService;
    private final WorkspaceDocumentRepository documentRepository;

    @Autowired
    public TemplateRecommendationService(
            TemplateRegistryService templateRegistryService,
            @Autowired(required = false) WorkspaceDocumentRepository documentRepository
    ) {
        this.templateRegistryService = templateRegistryService;
        this.documentRepository = documentRepository;
    }

    public TemplateRecommendationService(TemplateRegistryService templateRegistryService) {
        this(templateRegistryService, null);
    }

    /**
     * Recommends and ranks templates deterministically for a given legal workspace.
     */
    public List<TemplateRecommendation> recommendForWorkspace(LegalWorkspace workspace) {
        return recommendForWorkspace(workspace, null);
    }

    /**
     * Overloaded recommender with explicit stage prioritization.
     */
    public List<TemplateRecommendation> recommendForWorkspace(LegalWorkspace workspace, TemplateStage preferredStage) {
        if (workspace == null) {
            return List.of();
        }

        LegalDomain wsDomain = resolveWorkspaceDomain(workspace);
        PartyPerspective wsPerspective = workspace.getPartyPerspective();

        List<TemplateManifest> allTemplates = templateRegistryService.getAllTemplates();
        List<TemplateRecommendation> recommendations = new ArrayList<>();

        boolean isAppellateContext = detectAppellateContext(workspace);

        for (TemplateManifest template : allTemplates) {
            int score = 0;
            List<String> reasons = new ArrayList<>();

            // 1. Domain match (+50)
            if (isDomainMatching(template.primaryDomain(), wsDomain)) {
                score += 50;
                reasons.add("Matches workspace legal domain (" + wsDomain + ")");
            }

            // 2. Party perspective match (+40)
            if (template.targetPerspective() == wsPerspective) {
                score += 40;
                reasons.add("Tailored for " + wsPerspective + " perspective");
            } else if (template.targetPerspective() == PartyPerspective.NEUTRAL_EVALUATOR) {
                score += 40;
                reasons.add("Neutral instrument applicable to all parties");
            }

            // 3. Universal procedural necessities (+25)
            if ("bsa_sec63_electronic_evidence_cert".equals(template.templateId())) {
                score += 25;
                reasons.add("Mandatory electronic evidence certification under Section 63 BSA 2023");
            } else if ("supporting_affidavit_general".equals(template.templateId())) {
                score += 25;
                reasons.add("Standard procedural supporting affidavit under Order XIX CPC");
            }

            // 4. Stage Prioritization
            if (preferredStage != null) {
                if (template.stage() == preferredStage) {
                    score += 35;
                    reasons.add("Matches preferred procedural stage (" + preferredStage + ")");
                }
            } else {
                if (wsPerspective == PartyPerspective.CLAIMANT_PETITIONER) {
                    if (template.stage() == TemplateStage.PRE_LITIGATION_NOTICE) {
                        score += 20;
                        reasons.add("Priority pre-litigation demand stage for claimant");
                    } else if (template.stage() == TemplateStage.ORIGINAL_FILING) {
                        score += 15;
                        reasons.add("Priority original filing stage for claimant");
                    } else if (template.stage() == TemplateStage.APPELLATE) {
                        if (!isAppellateContext) {
                            score -= 20;
                            reasons.add("Appellate stage deprioritized: no impugned order or appeal narrative found");
                        }
                    }
                } else if (wsPerspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT) {
                    if (template.stage() == TemplateStage.DEFENSE_REPLY) {
                        score += 25;
                        reasons.add("Priority defense reply stage for respondent");
                    }
                }
            }

            // 5. Statute & Trigger Keyword Matching (+15 per match, capped at +30)
            if (template.triggerKeywords() != null && !template.triggerKeywords().isEmpty()) {
                int keywordScore = 0;
                List<String> matchedKeywords = new ArrayList<>();
                for (String kw : template.triggerKeywords()) {
                    if (kw == null || kw.isBlank()) continue;
                    if (matchesKeyword(kw.trim().toLowerCase(Locale.ROOT), workspace)) {
                        keywordScore += 15;
                        matchedKeywords.add(kw);
                        if (keywordScore >= 30) {
                            keywordScore = 30;
                            break;
                        }
                    }
                }
                if (keywordScore > 0) {
                    score += keywordScore;
                    reasons.add("Trigger keyword match: " + String.join(", ", matchedKeywords));
                }
            }

            String reason = reasons.isEmpty() ? "Standard statutory template" : String.join("; ", reasons);
            recommendations.add(new TemplateRecommendation(
                    template,
                    score,
                    reason,
                    template.variantComparisonNote()
            ));
        }

        recommendations.sort(Comparator.comparingInt(TemplateRecommendation::matchScore).reversed());
        return recommendations;
    }

    private boolean detectAppellateContext(LegalWorkspace workspace) {
        if (workspace == null) return false;

        // Check document repository for IMPUGNED_ORDER
        if (documentRepository != null && workspace.getWorkspaceId() != null && workspace.getClerkUserId() != null) {
            try {
                boolean hasImpugnedDoc = documentRepository
                        .findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(workspace.getWorkspaceId(), workspace.getClerkUserId())
                        .stream()
                        .anyMatch(d -> d.getDocumentType() == DocumentType.IMPUGNED_ORDER);
                if (hasImpugnedDoc) return true;
            } catch (Exception ignored) {}
        }

        // Check user narrative
        if (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().userNarrative() != null) {
            String narrative = workspace.getLatestDraftingContext().userNarrative().toLowerCase(Locale.ROOT);
            if (narrative.contains("appeal") || narrative.contains("impugned order")) {
                return true;
            }
        }

        // Check title
        if (workspace.getTitle() != null) {
            String title = workspace.getTitle().toLowerCase(Locale.ROOT);
            if (title.contains("appeal") || title.contains("impugned order")) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesKeyword(String lowerKeyword, LegalWorkspace workspace) {
        if (workspace == null) return false;

        // Check title
        if (workspace.getTitle() != null && workspace.getTitle().toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
            return true;
        }

        // Check factual anomalies
        if (workspace.getFactLedger() != null && workspace.getFactLedger().extractedFacts() != null) {
            List<String> anomalies = workspace.getFactLedger().extractedFacts().factualAnomalies();
            if (anomalies != null) {
                for (String a : anomalies) {
                    if (a != null && a.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                        return true;
                    }
                }
            }
        }

        // Check DraftingContext statutePack and userNarrative
        if (workspace.getLatestDraftingContext() != null) {
            var context = workspace.getLatestDraftingContext();
            if (context.statutePack() != null) {
                for (var pin : context.statutePack()) {
                    if (pin != null) {
                        if (pin.actName() != null && pin.actName().toLowerCase(Locale.ROOT).contains(lowerKeyword)) return true;
                        if (pin.sectionNumber() != null && pin.sectionNumber().toLowerCase(Locale.ROOT).contains(lowerKeyword)) return true;
                        if (pin.operativeText() != null && pin.operativeText().toLowerCase(Locale.ROOT).contains(lowerKeyword)) return true;
                        if (pin.parentContext() != null && pin.parentContext().toLowerCase(Locale.ROOT).contains(lowerKeyword)) return true;
                    }
                }
            }
            if (context.userNarrative() != null && context.userNarrative().toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                return true;
            }
        }

        return false;
    }

    private LegalDomain resolveWorkspaceDomain(LegalWorkspace workspace) {
        if (workspace.getPrimaryDomain() != null && workspace.getPrimaryDomain() != LegalDomain.UNKNOWN) {
            return workspace.getPrimaryDomain();
        }
        if (workspace.getFactLedger() != null) {
            if (workspace.getFactLedger().extractedFacts() != null && workspace.getFactLedger().extractedFacts().domain() != null) {
                return workspace.getFactLedger().extractedFacts().domain();
            }
            if (workspace.getFactLedger().ruleEvaluationFacts() != null && workspace.getFactLedger().ruleEvaluationFacts().domain() != null) {
                try {
                    String domainName = workspace.getFactLedger().ruleEvaluationFacts().domain().name();
                    return LegalDomain.valueOf(domainName);
                } catch (Exception ignored) {}
            }
        }
        if (workspace.getLatestDraftingContext() != null && workspace.getLatestDraftingContext().facts() != null) {
            if (workspace.getLatestDraftingContext().facts().domain() != null) {
                return workspace.getLatestDraftingContext().facts().domain();
            }
        }
        return LegalDomain.CONSUMER;
    }

    private boolean isDomainMatching(LegalDomain templateDomain, LegalDomain wsDomain) {
        if (templateDomain == null || wsDomain == null || wsDomain == LegalDomain.UNKNOWN) return false;
        if (templateDomain == wsDomain) return true;
        if ((templateDomain == LegalDomain.CONSUMER || templateDomain == LegalDomain.CONSUMER_RIGHTS) &&
                (wsDomain == LegalDomain.CONSUMER || wsDomain == LegalDomain.CONSUMER_RIGHTS)) {
            return true;
        }
        return false;
    }
}
