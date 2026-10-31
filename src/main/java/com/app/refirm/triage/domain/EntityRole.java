package com.app.refirm.triage.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum EntityRole {
    CONSUMER,
    ALLOTTEE,
    OPPOSITE_PARTY,
    PLATFORM_AGGREGATOR,
    GOVERNMENT_BODY,
    UNKNOWN;

    @JsonCreator
    public static EntityRole fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase();
        for (EntityRole role : values()) {
            if (role.name().equalsIgnoreCase(normalized)) {
                return role;
            }
        }
        return UNKNOWN;
    }
}
