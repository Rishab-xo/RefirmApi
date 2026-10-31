package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Represents a geographic zone tier within a statute's jurisdiction model.
 * <p>
 * Maps from JSON:
 * <pre>
 * {
 *   "zone_classification": "Metro",
 *   "maximum_threshold": 5000000
 * }
 * </pre>
 * Boxed {@link Long} prevents NPE on absent numeric fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GeographicTier(
        @JsonProperty("zone_classification") String zoneClassification,
        @JsonProperty("maximum_threshold") Long maximumThreshold
) {}

