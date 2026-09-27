package com.app.refirm.triage.entities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Top-Level Domain legal taxonomy for the Refirm triage pipeline.
 * <p>
 * Uses broad buckets intentionally to prevent LLM "analysis paralysis" —
 * sub-categorization happens downstream in Phase 2 (Retrieval Engine).
 * <p>
 * {@code UNKNOWN} and {@code NEEDS_HUMAN_TRIAGE} serve as deterministic
 * fallback values when the LLM cannot classify or when it fails entirely.
 */
public enum LegalCategory {

    CIVIL_PROPERTY("Civil & Property Law", "CIVIL_PROPERTY"),
    CRIMINAL_LAW("Criminal Law", "CRIMINAL_LAW"),
    FAMILY_MATRIMONIAL("Family & Matrimonial Law", "FAMILY_MATRIMONIAL"),
    CORPORATE_COMMERCIAL("Corporate & Commercial Law", "CORPORATE_COMMERCIAL"),
    LABOUR_EMPLOYMENT("Labour & Employment Law", "LABOUR_EMPLOYMENT"),
    CONSUMER_GRIEVANCE("Consumer Grievance", "CONSUMER_GRIEVANCE"),
    TAX_CUSTOMS("Tax & Customs", "TAXATION"), // Mapped to match database metadata
    INTELLECTUAL_PROPERTY("Intellectual Property", "INTELLECTUAL_PROPERTY"),
    CONSTITUTIONAL_WRIT("Constitutional & Writ Petitions", "CONSTITUTIONAL_WRIT"),
    UNKNOWN("Unknown — Pending Classification", "UNKNOWN"),
    NEEDS_HUMAN_TRIAGE("Needs Human Triage", "NEEDS_HUMAN_TRIAGE");

    private final String displayLabel;
    private final String dbDomain;

    LegalCategory(String displayLabel, String dbDomain) {
        this.displayLabel = displayLabel;
        this.dbDomain = dbDomain;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public String getDbDomain() {
        return dbDomain;
    }

    @JsonValue
    public String getJsonValue() {
        return name();
    }

    /**
     * Safely resolves a string to a {@link LegalCategory}, returning
     * {@code NEEDS_HUMAN_TRIAGE} if the value is null, blank, or invalid.
     * Annotated with {@code @JsonCreator} so Jackson automatically uses this
     * tolerant resolver during JSON deserialization.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LegalCategory fromStringSafe(String value) {
        if (value == null || value.isBlank()) {
            return NEEDS_HUMAN_TRIAGE;
        }
        String clean = value.trim().toUpperCase().replace(" ", "_").replace("-", "_").replace("&", "").replaceAll("_+", "_");
        try {
            return LegalCategory.valueOf(clean);
        } catch (IllegalArgumentException e) {
            for (LegalCategory cat : values()) {
                if (cat.name().equalsIgnoreCase(clean)
                        || cat.getDisplayLabel().equalsIgnoreCase(value.trim())
                        || clean.contains(cat.name())) {
                    return cat;
                }
            }
            if (clean.contains("CIVIL") || clean.contains("PROPERTY")) return CIVIL_PROPERTY;
            if (clean.contains("CRIMIN")) return CRIMINAL_LAW;
            if (clean.contains("CONSUMER")) return CONSUMER_GRIEVANCE;
            if (clean.contains("FAMILY") || clean.contains("MATRIMON")) return FAMILY_MATRIMONIAL;
            if (clean.contains("LABOUR") || clean.contains("EMPLOY")) return LABOUR_EMPLOYMENT;
            if (clean.contains("CORP") || clean.contains("COMMERC")) return CORPORATE_COMMERCIAL;
            if (clean.contains("TAX") || clean.contains("CUSTOM")) return TAX_CUSTOMS;
            if (clean.contains("IP") || clean.contains("INTELLECT")) return INTELLECTUAL_PROPERTY;
            if (clean.contains("WRIT") || clean.contains("CONSTITUT")) return CONSTITUTIONAL_WRIT;
            return NEEDS_HUMAN_TRIAGE;
        }
    }
}