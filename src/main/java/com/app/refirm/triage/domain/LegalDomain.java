package com.app.refirm.triage.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Controlled vocabulary for top-level legal domain classification in the Two-Pass Pipeline.
 */
public enum LegalDomain {
    BANKING,
    BANKING_FINANCE,
    CIVIL_PROPERTY,
    CONSTITUTIONAL,
    CONSUMER,
    CONSUMER_RIGHTS,
    CORPORATE,
    CRIMINAL,
    CYBER,
    EMPLOYMENT,
    ENVIRONMENTAL,
    FAMILY,
    IP,
    MEDICAL_NEGLIGENCE,
    REAL_ESTATE,
    TAXATION,
    TENANCY,
    UNKNOWN;

    @JsonCreator
    public static LegalDomain fromStringSafe(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        try {
            return LegalDomain.valueOf(value.trim().toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            String clean = value.trim().toUpperCase().replace(" ", "_").replace("-", "_");
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

