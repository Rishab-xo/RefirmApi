package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.core.service.LegalRetrievalService;
import com.app.refirm.core.service.LegalRetrievalService.StatuteFile;
import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.rules.model.StatuteRule;
import com.app.refirm.rules.registry.StatuteRegistry;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.domain.StatuteType;
import com.app.refirm.triage.entities.LegalCategory;
import com.app.refirm.triage.pipeline.StatuteRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Primary
public class OkfStatuteRetrieverAdapter implements StatuteRetriever {

    private static final Logger log = LoggerFactory.getLogger(OkfStatuteRetrieverAdapter.class);

    private final LegalRetrievalService legalRetrievalService;
    private final StatuteRegistry statuteRegistry;

    @Autowired
    public OkfStatuteRetrieverAdapter(
            LegalRetrievalService legalRetrievalService,
            @Nullable StatuteRegistry statuteRegistry) {
        this.legalRetrievalService = legalRetrievalService;
        this.statuteRegistry = statuteRegistry;
    }

    public OkfStatuteRetrieverAdapter(LegalRetrievalService legalRetrievalService) {
        this(legalRetrievalService, null);
    }

    @Override
    public StatuteRetrievalResult retrieveStatutes(ExtractedFacts facts) {
        if (facts == null || facts.domain() == null) {
            return new StatuteRetrievalResult(List.of(), List.of());
        }

        LegalCategory category = LegalCategory.fromStringSafe(facts.domain().name());
        LegalDomain rulesDomain = LegalDomain.fromStringSafe(facts.domain().name());
        
        Long loss = facts.estimatedFinancialLoss() != null
                ? facts.estimatedFinancialLoss().longValue()
                : null;
        ExtractedFactsDTO factsDto = new ExtractedFactsDTO(
                rulesDomain,
                loss, 
                null, null, null
        );

        var evaluationResult = legalRetrievalService.resolveStatutePackWithEvaluation(
                category, facts.factualAnomalies(), factsDto
        );

        List<StatuteFile> rawFiles = (evaluationResult.selectedFiles() != null && !evaluationResult.selectedFiles().isEmpty())
                ? evaluationResult.selectedFiles()
                : legalRetrievalService.resolveRawStatuteFiles(category, facts.factualAnomalies(), factsDto);

        List<StatutePin> pins = rawFiles.stream()
                .map(file -> new StatutePin(
                        file.statuteName(),
                        file.filename().replace(".md", ""),
                        StatuteType.SUBSTANTIVE_BREACH,
                        "Extracted from OKF Bundle",
                        file.content()
                )).toList();

        Integer limitationMonths = null;
        Boolean civilCourtsBarred = null;

        if (statuteRegistry != null) {
            // 1. Resolve preconditions ONLY from statutes explicitly included by evaluation
            if (evaluationResult.includedStatuteNames() != null) {
                for (String statuteName : evaluationResult.includedStatuteNames()) {
                    Optional<StatuteRule> ruleOpt = statuteRegistry.getRule(statuteName);
                    if (ruleOpt.isPresent()) {
                        StatuteRule rule = ruleOpt.get();
                        if (limitationMonths == null && rule.statutoryPreconditions() != null 
                                && rule.statutoryPreconditions().statuteOfLimitationsMonths() != null) {
                            limitationMonths = rule.statutoryPreconditions().statuteOfLimitationsMonths();
                            log.info("Resolved limitation period: {} months from statute '{}'", limitationMonths, statuteName);
                        }
                        if (civilCourtsBarred == null && rule.jurisdictionalRouting() != null 
                                && rule.jurisdictionalRouting().civilCourtsBarred() != null) {
                            civilCourtsBarred = rule.jurisdictionalRouting().civilCourtsBarred();
                        }
                    }
                }
            }
        }

        return new StatuteRetrievalResult(pins, evaluationResult.assumptionCaveats(), limitationMonths, civilCourtsBarred);
    }
}
