package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Captures procedural preconditions that must be satisfied before a case can proceed.
 * <p>
 * Maps from JSON:
 * <pre>
 * {
 *   "requires_sanction_for_cognizance": true,
 *   "statute_of_limitations_months": 36
 * }
 * </pre>
 * Boxed {@link Boolean} and {@link Integer} prevent NPE when fields are absent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StatutoryPreconditions(
        Boolean requiresSanctionForCognizance,
        Integer statuteOfLimitationsMonths
) {}
