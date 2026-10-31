package com.app.refirm.rules.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Legal domain taxonomy for the Rules Engine DSL.
 * <p>
 * Mirrors the 13 domain keys present at the root of {@code master_rules_engine.json}.
 * Any unknown or malformed domain key from the JSON will be safely coerced to {@link #UNKNOWN}
 * provided the ObjectMapper has {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} enabled
 * (configured in {@code JacksonConfig}).
 * <p>
 * NOTE: This enum is intentionally separate from {@code LegalCategory} to preserve
 * isolation between the triage/entity layer and the rules engine layer.
 */
public enum LegalDomain {

    BANKING,
    CIVIL_PROPERTY,
    CONSTITUTIONAL,
    CONSUMER,
    CORPORATE,
    CRIMINAL,
    CYBER,
    EMPLOYMENT,
    ENVIRONMENTAL,
    FAMILY,
    IP,
    TAXATION,
    TENANCY,

    /**
     * Fallback sentinel value. Jackson will route any unrecognised domain string here
     * when {@code DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} is active.
     */
    @JsonEnumDefaultValue
    UNKNOWN;

    /**
     * Safe factory used by Jackson for string-based deserialization.
     * Performs a best-effort normalisation (trim + upper-case) before lookup,
     * returning {@link #UNKNOWN} for any value that cannot be matched.
     *
     * @param value raw JSON string token
     * @return matched {@link LegalDomain}, or {@link #UNKNOWN} if unresolvable
     */
    @JsonCreator
    public static LegalDomain fromStringSafe(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String clean = value.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        try {
            return LegalDomain.valueOf(clean);
        } catch (IllegalArgumentException e) {
            if (clean.contains("CONSUMER")) return CONSUMER;
            if (clean.contains("REAL") || clean.contains("ESTATE") || clean.contains("PROPERTY") || clean.contains("CIVIL")) return CIVIL_PROPERTY;
            if (clean.contains("BANK")) return BANKING;
            if (clean.contains("TENAN") || clean.contains("RENT")) return TENANCY;
            if (clean.contains("LABOUR") || clean.contains("EMPLOY")) return EMPLOYMENT;
            if (clean.contains("CRIMIN")) return CRIMINAL;
            if (clean.contains("CORP")) return CORPORATE;
            if (clean.contains("CYBER") || clean.contains("IT")) return CYBER;
            if (clean.contains("ENVIRON")) return ENVIRONMENTAL;
            if (clean.contains("FAMILY") || clean.contains("MATRIMON")) return FAMILY;
            if (clean.contains("IP") || clean.contains("PATENT") || clean.contains("COPYRIGHT") || clean.contains("TRADEMARK")) return IP;
            if (clean.contains("TAX")) return TAXATION;
            if (clean.contains("CONSTITUT")) return CONSTITUTIONAL;
            if (clean.contains("MEDICAL")) return CONSUMER;
            return UNKNOWN;
        }
    }
}
