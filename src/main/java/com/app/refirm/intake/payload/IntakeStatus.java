package com.app.refirm.intake.payload;

/**
 * Status outcomes for the stateless 2-strike legal intake pipeline.
 */
public enum IntakeStatus {

    /**
     * Critical facts are missing on Strike 1; client must provide clarification.
     */
    MISSING_DATA,

    /**
     * Intake completed and brief was generated (either full data on Turn 1 or Fail-Open on Turn 2).
     */
    PROCESSED
}
