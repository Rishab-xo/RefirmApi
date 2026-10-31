package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Represents physical-size-based exemptions embedded in some statute patches.
 * <p>
 * Maps from the non-standard patched extension in JSON:
 * <pre>
 * {
 *   "maximum_land_area_sq_meters": 500,
 *   "maximum_apartments": 2
 * }
 * </pre>
 * Boxed {@link Integer} prevents NPE when fields are absent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PhysicalSizeExemption(
        Integer maximumLandAreaSqMeters,
        Integer maximumApartments
) {}
