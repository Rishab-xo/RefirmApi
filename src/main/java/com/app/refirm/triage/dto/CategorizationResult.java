package com.app.refirm.triage.dto;

import com.app.refirm.triage.entities.LegalCategory;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record CategorizationResult(
    @JsonPropertyDescription("The primary legal domain best matching the core dispute")
    LegalCategory primaryDomain,

    @JsonPropertyDescription("Secondary legal domains if the dispute involves overlapping legal fields (e.g., criminal intimidation during a tenancy dispute)")
    List<LegalCategory> secondaryDomains,

    @JsonPropertyDescription("Specific legal triggers, remedies, or statutes identified from the narrative (e.g., 'Section 138 NI Act Cheque Dishonour')")
    List<String> keyTriggers,

    @JsonPropertyDescription("Confidence score between 0.0 and 1.0 regarding domain match certainty")
    double confidenceScore,

    @JsonPropertyDescription("Brief single-sentence explanation of why these domains were chosen")
    String rationale
) {}
