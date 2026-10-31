package com.app.refirm.intake.payload;

/**
 * Response payload for the stateless 2-strike legal intake pipeline.
 *
 * @param status             overall status of the intake evaluation
 * @param followUpQuestion  empathetic clarification prompt when status is MISSING_DATA (null otherwise)
 * @param generatedBrief    rendered markdown brief and petition when status is PROCESSED (null otherwise)
 */
public record IntakeResponse(
        IntakeStatus status,
        String followUpQuestion,
        String generatedBrief
) {

    /**
     * Factory for Strike 1 clarification requests.
     *
     * @param question empathetic follow-up question prompting for missing critical facts
     * @return an immutable {@link IntakeResponse} with {@link IntakeStatus#MISSING_DATA}
     */
    public static IntakeResponse needsClarification(String question) {
        return new IntakeResponse(IntakeStatus.MISSING_DATA, question, null);
    }

    /**
     * Factory for successful brief generation (Turn 1 complete or Turn 2 fail-open).
     *
     * @param brief markdown legal brief and petition
     * @return an immutable {@link IntakeResponse} with {@link IntakeStatus#PROCESSED}
     */
    public static IntakeResponse completed(String brief) {
        return new IntakeResponse(IntakeStatus.PROCESSED, null, brief);
    }
}
