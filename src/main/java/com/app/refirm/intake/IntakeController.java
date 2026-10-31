package com.app.refirm.intake;

import com.app.refirm.intake.payload.IntakeRequest;
import com.app.refirm.intake.payload.IntakeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stateless REST controller for the 2-strike legal intake pipeline.
 * <p>
 * Accessible at {@code /api/v1.0/intake}.
 */
@RestController
@RequestMapping({"/intake", "/api/v1.0/intake"})
public class IntakeController {

    private static final Logger log = LoggerFactory.getLogger(IntakeController.class);

    private final IntakeOrchestratorService orchestratorService;

    public IntakeController(IntakeOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    /**
     * Handles intake submissions using the 2-strike stateless protocol.
     *
     * @param request JSON payload carrying userStory and intakeTurn counter
     * @return {@link IntakeResponse} with either MISSING_DATA or PROCESSED status
     */
    @PostMapping
    public ResponseEntity<IntakeResponse> handleIntake(@RequestBody IntakeRequest request) {
        log.info("POST /api/v1.0/intake received for turn: {}", request != null ? request.intakeTurn() : 0);
        IntakeResponse response = orchestratorService.processIntake(request);
        return ResponseEntity.ok(response);
    }
}
