package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Represents a single tier within a multi-tier tribunal jurisdiction model.
 * <p>
 * Maps from JSON:
 * <pre>
 * {
 *   "designated_tribunal": "NCLT",
 *   "minimum_threshold": 100000,
 *   "maximum_threshold": 10000000
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TribunalTier(
        @JsonProperty("designated_tribunal") String designatedTribunal,
        @JsonProperty("minimum_threshold") Double minimumThreshold,
        @JsonProperty("maximum_threshold") Double maximumThreshold
) {
    public TribunalTier(String designatedTribunal, Number minimumThreshold, Number maximumThreshold) {
        this(
                designatedTribunal,
                minimumThreshold != null ? minimumThreshold.doubleValue() : null,
                maximumThreshold != null ? maximumThreshold.doubleValue() : null
        );
    }
}

