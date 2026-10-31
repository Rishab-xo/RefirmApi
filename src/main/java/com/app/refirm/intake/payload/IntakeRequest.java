package com.app.refirm.intake.payload;

/**
 * Request payload for the stateless 2-strike legal intake pipeline.
 *
 * @param userStory  unstructured client narrative describing the dispute
 * @param intakeTurn conversational turn counter (1 for initial submission, 2 for follow-up)
 */
public record IntakeRequest(
        String userStory,
        int intakeTurn
) {}
