package com.app.refirm.triage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record TriageBriefResponse(
    @JsonPropertyDescription("Immediate legal verdict and rights shield. Zero academic fluff.")
    String verdictShield,

    @JsonPropertyDescription("Itemized statutory provisions mapped directly to the facts")
    List<StatutoryReference> statutoryMatrix,

    @JsonPropertyDescription("Evidentiary reconciliation: admitted facts, disputed points, and evidence to preserve")
    EvidentiaryReconciliation evidentiaryReconciliation,

    @JsonPropertyDescription("Tactical guidance: dos, don'ts, and next immediate actions")
    TacticalGuidance tacticalGuidance,

    @JsonPropertyDescription("Ready-to-use deliverable draft: contemporaneous message or formal notice")
    DraftDeliverable draftDeliverable
) {
    public record StatutoryReference(String domain, String act, String section, String applicability) {}
    public record EvidentiaryReconciliation(List<String> admittedFacts, List<String> disputedPoints, List<String> evidenceToPreserve) {}
    public record TacticalGuidance(List<String> actionsToTake, List<String> actionsToAvoid) {}
    public record DraftDeliverable(String type, String content) {}
}
