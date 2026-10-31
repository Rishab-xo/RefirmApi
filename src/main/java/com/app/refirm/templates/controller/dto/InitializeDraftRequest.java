package com.app.refirm.templates.controller.dto;

import java.util.Map;

/**
 * Payload to initialize a new versioned draft from a master template.
 */
public record InitializeDraftRequest(
        String templateId,
        Map<String, String> userProvidedOverrides
) {}
