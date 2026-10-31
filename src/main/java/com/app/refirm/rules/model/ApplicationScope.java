package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * Defines the scope of entities to which a statute applies and those explicitly exempted.
 * <p>
 * Maps from JSON:
 * <pre>
 * {
 *   "applicable_entities": ["Landlord", "Tenant"],
 *   "exempted_entities": ["Central Government", "State Government"]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApplicationScope(
        List<String> applicableEntities,
        List<String> exemptedEntities
) {}
