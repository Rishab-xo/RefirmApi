package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.triage.domain.*;
import com.app.refirm.triage.pipeline.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TriagePipelineOrchestratorImpl implements TriagePipelineOrchestrator {

    private final FactExtractor factExtractor;
    private final List<ForumResolver> forumResolvers;
    private final StatuteRetriever statuteRetriever;
    private final BriefGenerator briefGenerator;

    @Autowired
    public TriagePipelineOrchestratorImpl(
            FactExtractor factExtractor, 
            List<ForumResolver> forumResolvers, 
            @Autowired(required = false) @Nullable StatuteRetriever statuteRetriever, 
            @Autowired(required = false) @Nullable BriefGenerator briefGenerator) {
        this.factExtractor = factExtractor;
        this.forumResolvers = forumResolvers;
        this.statuteRetriever = statuteRetriever;
        this.briefGenerator = briefGenerator;
    }

    @Override
    public TriagePipelineResult executeWithContext(String userNarrative, String userLocation) {
        // 1. Extract Structured Facts (gpt-6-luna)
        ExtractedFacts facts = factExtractor.extract(userNarrative);
        
        // 2. Resolve Jurisdiction Deterministically (Java Strategy Pattern)
        ResolvedForum targetForum = forumResolvers.stream()
                .filter(resolver -> resolver.supports(facts.domain()))
                .findFirst()
                .map(resolver -> resolver.resolve(facts, userLocation))
                .orElse(new ResolvedForum(
                        "Appropriate Statutory Tribunal / Civil Court", 
                        "Standard Filing", 
                        "Domain jurisdiction determined generally."
                ));
                
        // 3. Retrieve Exact Statutory Pins & Caveats
        var retrievalResult = statuteRetriever != null
                ? statuteRetriever.retrieveStatutes(facts)
                : new StatuteRetriever.StatuteRetrievalResult(List.of(), List.of());
        
        // 4. Generate the Final Strategic Brief (Pass 2)
        DraftingContext context = new DraftingContext(
                userNarrative, 
                facts, 
                retrievalResult.pins(), 
                targetForum, 
                retrievalResult.assumptionCaveats(),
                retrievalResult.limitationMonths(),
                retrievalResult.civilCourtsBarred()
        );
        String brief = briefGenerator != null
                ? briefGenerator.generateDraft(context)
                : "# CONTEXT ASSEMBLED\nTarget Forum: " + targetForum.forumName();

        return new TriagePipelineResult(context, brief);
    }

    @Override
    public String processCase(String userNarrative, String userLocation) {
        return executeWithContext(userNarrative, userLocation).briefMarkdown();
    }
}
