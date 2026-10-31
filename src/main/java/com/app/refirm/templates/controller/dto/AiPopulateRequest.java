package com.app.refirm.templates.controller.dto;

/**
 * Payload to trigger isolated AI narrative block population.
 */
public record AiPopulateRequest(
        String customLawyerInstruction
) {}
