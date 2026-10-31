package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Defines a date-based statutory blackout or exclusion window
 * (e.g., IBC Section 10A suspension from 25.03.2020 to 25.03.2021;
 * Supreme Court COVID exclusion from 15.03.2020 to 28.02.2022).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DateWindowTrigger(
        String startDate,
        String endDate,
        String barWarningDirective
) {}
