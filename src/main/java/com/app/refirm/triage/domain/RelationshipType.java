package com.app.refirm.triage.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum RelationshipType {
    PRIMARY_CLAIMANT,
    PAYMENT_INSTRUMENT_HOLDER,
    THIRD_PARTY_WITNESS,
    OPPOSING_ENTITY,
    UNKNOWN;

    @JsonCreator
    public static RelationshipType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase();
        for (RelationshipType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
