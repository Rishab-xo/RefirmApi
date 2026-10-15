package com.app.refirm.triage.entities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Top-Level Domain legal taxonomy matching the 13 OKF bundle domains.
 */
public enum LegalCategory {

    BANKING("Banking & Finance Law", "BANKING"),
    CIVIL_PROPERTY("Civil & Property Law", "CIVIL_PROPERTY"),
    CONSTITUTIONAL("Constitutional & Writ Law", "CONSTITUTIONAL"),
    CONSUMER("Consumer Protection & Grievance", "CONSUMER"),
    CORPORATE("Corporate & Commercial Law", "CORPORATE"),
    CRIMINAL("Criminal Law", "CRIMINAL"),
    CYBER("Cyber & Information Technology Law", "CYBER"),
    EMPLOYMENT("Labour & Employment Law", "EMPLOYMENT"),
    ENVIRONMENTAL("Environmental Law", "ENVIRONMENTAL"),
    FAMILY("Family & Matrimonial Law", "FAMILY"),
    IP("Intellectual Property Law", "IP"),
    TAXATION("Taxation & Customs", "TAXATION"),
    TENANCY("Tenancy & Rent Control", "TENANCY"),
    UNKNOWN("Unknown — Pending Classification", "UNKNOWN");

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
     * {@code UNKNOWN} if the value is null, blank, or invalid.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LegalCategory fromStringSafe(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
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
            if (clean.contains("BANK") || clean.contains("LOAN") || clean.contains("SARFAESI") || clean.contains("NPA")) return BANKING;
            if (clean.contains("TENAN") || clean.contains("RENT") || clean.contains("LANDLORD") || clean.contains("EVICTION")) return TENANCY;
            if (clean.contains("CIVIL") || clean.contains("PROPERTY") || clean.contains("PARTITION") || clean.contains("REAL") || clean.contains("ESTATE") || clean.contains("RERA")) return CIVIL_PROPERTY;
            if (clean.contains("CONSTITUT") || clean.contains("WRIT") || clean.contains("FUNDAMENTAL")) return CONSTITUTIONAL;
            if (clean.contains("CONSUMER") || clean.contains("DEFECT")) return CONSUMER;
            if (clean.contains("CORP") || clean.contains("COMMERC") || clean.contains("COMPANY")) return CORPORATE;
            if (clean.contains("CRIMIN") || clean.contains("FIR") || clean.contains("POLICE") || clean.contains("BAIL")) return CRIMINAL;
            if (clean.contains("CYBER") || clean.contains("IT_ACT") || clean.contains("HACK") || clean.contains("PHISH")) return CYBER;
            if (clean.contains("LABOUR") || clean.contains("EMPLOY") || clean.contains("WAGE") || clean.contains("SALARY")) return EMPLOYMENT;
            if (clean.contains("ENVIRON") || clean.contains("POLLUTION") || clean.contains("NGT")) return ENVIRONMENTAL;
            if (clean.contains("FAMILY") || clean.contains("MATRIMON") || clean.contains("DIVORCE") || clean.contains("DOWRY")) return FAMILY;
            if (clean.contains("IP") || clean.contains("INTELLECT") || clean.contains("TRADEMARK") || clean.contains("COPYRIGHT") || clean.contains("PATENT")) return IP;
            if (clean.contains("TAX") || clean.contains("CUSTOM") || clean.contains("GST")) return TAXATION;
            return UNKNOWN;
        }
    }
}