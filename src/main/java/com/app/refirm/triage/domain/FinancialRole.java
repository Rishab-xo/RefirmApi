package com.app.refirm.triage.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;

public enum FinancialRole {
    @JsonAlias({"PAYED_CONSIDERATION"})
    PAID_CONSIDERATION,
    INVOICE_RECIPIENT,
    PROMOTER_OR_MERCHANT,
    NONE;

    @JsonCreator
    public static FinancialRole fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toUpperCase();
        if ("PAYED_CONSIDERATION".equals(normalized)) {
            return PAID_CONSIDERATION;
        }
        for (FinancialRole role : values()) {
            if (role.name().equalsIgnoreCase(normalized)) {
                return role;
            }
        }
        return NONE;
    }
}
