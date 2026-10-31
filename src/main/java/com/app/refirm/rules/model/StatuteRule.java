package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * The root entity of the Rules Engine DSL. Represents a single statute's complete
 * deterministic rule-set as loaded from {@code master_rules_engine.json}.
 * <p>
 * All nested objects are composed from the other {@code rules.model} records.
 * All fields are nullable at the Java level (no primitive types) to ensure
 * graceful deserialization of statutes with partial data.
 * <p>
 * Example JSON structure:
 * <pre>
 * {
 *   "statute_name": "Transfer of Property Act 1882",
 *   "territorial_extent": "All India except J&K (pre-2019)",
 *   "application": { ... },
 *   "pecuniary_jurisdiction": { ... },
 *   "jurisdictional_routing": { ... },
 *   "statutory_preconditions": { ... },
 *   "non_obstante_overrides": ["Section 9 CPC", "Specific Relief Act"]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StatuteRule(
        String statuteName,
        String territorialExtent,
        ApplicationScope application,
        PecuniaryJurisdiction pecuniaryJurisdiction,
        JurisdictionalRouting jurisdictionalRouting,
        StatutoryPreconditions statutoryPreconditions,
        List<String> nonObstanteOverrides
) {}
