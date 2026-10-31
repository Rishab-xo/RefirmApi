package com.app.refirm.triage.domain;

public record PartyEntity(
    String name,
    EntityRole role,
    RelationshipType relationship,
    FinancialRole financialRole
) {}
