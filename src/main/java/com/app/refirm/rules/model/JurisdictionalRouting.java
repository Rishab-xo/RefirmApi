package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Describes the mandatory routing and forum-selection rules for a statute.
 * <p>
 * Maps from JSON:
 * <pre>
 * {
 *   "civil_courts_barred": true,
 *   "designated_tribunal": "Consumer Disputes Redressal Commission"
 * }
 * </pre>
 * Boxed {@link Boolean} prevents NPE when the field is absent in the JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JurisdictionalRouting(
        Boolean civilCourtsBarred,
        String designatedTribunal
) {}
