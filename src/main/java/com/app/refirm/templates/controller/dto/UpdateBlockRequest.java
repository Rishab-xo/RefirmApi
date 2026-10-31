package com.app.refirm.templates.controller.dto;

/**
 * Payload for direct 0-token manual lawyer edits to an AI block.
 */
public record UpdateBlockRequest(
        String manualMarkdownContent,
        Boolean lockBlock
) {}
