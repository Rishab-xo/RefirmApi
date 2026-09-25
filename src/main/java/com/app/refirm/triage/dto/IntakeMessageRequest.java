package com.app.refirm.triage.dto;

import java.util.UUID;

/**
 * Inbound request body for the intake message endpoint.
 * Accepts an optional sessionId for continuing an existing multi-turn conversation.
 */
public record IntakeMessageRequest(
        UUID sessionId,
        String message
) {
    public IntakeMessageRequest(String message) {
        this(null, message);
    }
}
