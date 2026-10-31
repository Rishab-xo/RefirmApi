package com.app.refirm.intake;

import com.app.refirm.intake.payload.IntakeRequest;
import com.app.refirm.intake.payload.IntakeResponse;
import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.triage.dto.FullIntakeResponse;
import com.app.refirm.triage.service.TriageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stateless Orchestrator implementing the 2-Strike intake policy.
 * <p>
 * Evaluates whether critical facts (legal domain or financial dispute amount) are present.
 * <ul>
 *   <li><strong>Turn 1 (Strike 1):</strong> If critical data is missing, short-circuits and
 *       prompts the user with an empathetic follow-up question.</li>
 *   <li><strong>Turn 2 (Strike 2):</strong> Bypasses missing data checks, passes whatever facts
 *       exist (even if null) into the rules engine for Fail-Open evaluation, and generates the brief.</li>
 * </ul>
 */
@Service
public class IntakeOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(IntakeOrchestratorService.class);

    private static final String DEFAULT_FOLLOW_UP_QUESTION =
            "I am so sorry you are dealing with this. To make sure we route your case to the exact right legal forum, could you estimate the total financial amount involved?";

    private final LunaFactExtractorService lunaExtractor;
    private final TriageService triageService;

    public IntakeOrchestratorService(LunaFactExtractorService lunaExtractor, TriageService triageService) {
        this.lunaExtractor = lunaExtractor;
        this.triageService = triageService;
    }

    /**
     * Executes the stateless 2-strike intake pipeline.
     *
     * @param request the incoming user story and turn counter
     * @return {@link IntakeResponse} containing either a clarification question or the generated brief
     */
    public IntakeResponse processIntake(IntakeRequest request) {
        if (request == null || request.userStory() == null || request.userStory().isBlank()) {
            return IntakeResponse.needsClarification(
                    "Please provide a description of your legal dispute so we can assess your case."
            );
        }

        log.info("Processing intake on turn: {}", request.intakeTurn());

        // 1. Extract facts via Luna
        ExtractedFactsDTO facts = lunaExtractor.extractFacts(request.userStory());

        // 2. Determine if critical data is missing (domain or disputeAmount)
        boolean missingData = (facts.domain() == null
                || facts.domain() == LegalDomain.UNKNOWN
                || facts.disputeAmount() == null);

        // 3. Strike 1 Check: Turn 1 with missing critical facts
        if (missingData && request.intakeTurn() <= 1) {
            log.info("Strike 1: Critical data missing (domain: {}, disputeAmount: {}). Prompting for clarification.",
                    facts.domain(), facts.disputeAmount());
            return IntakeResponse.needsClarification(DEFAULT_FOLLOW_UP_QUESTION);
        }

        // 4. Strike 2 / Complete Check: Turn >= 2 or all critical data present -> Fail-open & generate brief
        log.info("Executing pipeline (turn: {}, missingData: {}). Passing facts to rules engine & drafter.",
                request.intakeTurn(), missingData);
        FullIntakeResponse pipelineResponse = triageService.processIntakePipeline(request.userStory(), facts);

        return IntakeResponse.completed(pipelineResponse.brief());
    }
}
