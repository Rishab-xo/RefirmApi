package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.pipeline.BriefGenerator;
import com.app.refirm.triage.pipeline.FactExtractor;
import com.app.refirm.triage.pipeline.ForumResolver;
import com.app.refirm.triage.pipeline.StatuteRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Master Orchestrator implementing the Two-Pass Pipeline:
 * <ol>
 *   <li>Pass 1: Structured Fact Extraction (gpt-6-luna)</li>
 *   <li>Step 2: Statutory Context Retrieval</li>
 *   <li>Step 3: Strategy Pattern Deterministic Forum Resolution</li>
 *   <li>Pass 2: Grounded Legal Brief Drafting (o3-mini)</li>
 * </ol>
 */
@Service
public class TwoPassPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TwoPassPipelineOrchestrator.class);

    private final FactExtractor factExtractor;
    private final List<ForumResolver> forumResolvers;
    private final Optional<StatuteRetriever> statuteRetriever;
    private final Optional<BriefGenerator> briefGenerator;

    public TwoPassPipelineOrchestrator(
            FactExtractor factExtractor,
            List<ForumResolver> forumResolvers,
            @Autowired(required = false) StatuteRetriever statuteRetriever,
            @Autowired(required = false) BriefGenerator briefGenerator) {
        this.factExtractor = factExtractor;
        this.forumResolvers = forumResolvers;
        this.statuteRetriever = Optional.ofNullable(statuteRetriever);
        this.briefGenerator = Optional.ofNullable(briefGenerator);
    }

    /**
     * Executes Pass 1 (Fact Extraction) and Step 3 (Deterministic Forum Resolution),
     * assembling the master DraftingContext.
     *
     * @param userNarrative Client dispute narrative
     * @param userLocation  Client geographical location or jurisdiction
     * @return Fully assembled, immutable DraftingContext
     */
    public DraftingContext assembleContext(String userNarrative, String userLocation) {
        log.info("Starting pipeline execution for narrative length: {}", userNarrative != null ? userNarrative.length() : 0);

        // Pass 1: Extraction via gpt-6-luna
        ExtractedFacts facts = factExtractor.extract(userNarrative);
        log.info("Pass 1 extracted domain: {}, loss: {}", facts.domain(), facts.estimatedFinancialLoss());

        // Step 2: Statute Retrieval (if retriever present)
        var retrievalResult = statuteRetriever
                .map(retriever -> retriever.retrieveStatutes(facts))
                .orElse(new StatuteRetriever.StatuteRetrievalResult(List.of(), List.of()));

        // Step 3: Strategy Pattern Forum Resolution
        ForumResolver resolver = forumResolvers.stream()
                .filter(r -> r.supports(facts.domain()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No forum resolver found supporting domain: " + facts.domain()));

        ResolvedForum targetForum = resolver.resolve(facts, userLocation);
        return new DraftingContext(
                userNarrative, 
                facts, 
                retrievalResult.pins(), 
                targetForum, 
                retrievalResult.assumptionCaveats(),
                retrievalResult.limitationMonths(),
                retrievalResult.civilCourtsBarred()
        );
    }

    /**
     * Full execution of Two-Pass Pipeline (Extraction -> Retrieval -> Resolution -> Pass 2 Drafting).
     *
     * @param userNarrative Client dispute narrative
     * @param userLocation  Client geographical location
     * @return Formatted legal brief Markdown
     */
    public String executePipeline(String userNarrative, String userLocation) {
        DraftingContext context = assembleContext(userNarrative, userLocation);

        if (briefGenerator.isPresent()) {
            log.info("Executing Pass 2 Brief Generation...");
            return briefGenerator.get().generateDraft(context);
        } else {
            log.warn("BriefGenerator not yet registered. Returning assembled context as preview.");
            return "# CONTEXT ASSEMBLED\nTarget Forum: " + context.targetForum().forumName();
        }
    }
}
