package com.app.refirm.templates.controller.dto;

/**
 * Payload to refine a single isolated AI block via chat instructions.
 */
public record RefineBlockRequest(
        String lawyerInstruction,
        Boolean lockAfterRefine
) {}
