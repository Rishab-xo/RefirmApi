package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * Defines the monetary jurisdiction limits for a statute.
 * <p>
 * Supports flat limits, multi-tier tribunal routing, geographic zone caps,
 * and physical-size exemptions. All fields are optional in the JSON — boxed types
 * and {@code @JsonIgnoreProperties} ensure zero-crash deserialization for any
 * absent or unexpected field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PecuniaryJurisdiction(
        @JsonProperty("has_limit") Boolean hasLimit,
        @JsonProperty("minimum_threshold") Double minimumThreshold,
        @JsonProperty("maximum_threshold") Double maximumThreshold,
        @JsonProperty("tribunal_tiers") List<TribunalTier> tribunalTiers,
        @JsonProperty("geographic_tiers") List<GeographicTier> geographicTiers,
        @JsonProperty("physical_size_exemptions") PhysicalSizeExemption physicalSizeExemptions
) {
    public PecuniaryJurisdiction(
            Boolean hasLimit,
            Double minimumThreshold,
            Double maximumThreshold,
            List<TribunalTier> tribunalTiers
    ) {
        this(hasLimit, minimumThreshold, maximumThreshold, tribunalTiers, null, null);
    }

    public PecuniaryJurisdiction(
            Boolean hasLimit,
            Number minimumThreshold,
            Number maximumThreshold,
            List<TribunalTier> tribunalTiers,
            List<GeographicTier> geographicTiers,
            PhysicalSizeExemption physicalSizeExemptions
    ) {
        this(
                hasLimit,
                minimumThreshold != null ? minimumThreshold.doubleValue() : null,
                maximumThreshold != null ? maximumThreshold.doubleValue() : null,
                tribunalTiers,
                geographicTiers,
                physicalSizeExemptions
        );
    }
}

