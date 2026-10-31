package com.app.refirm.workspace.controller.dto;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.ExtractedFacts;

import java.util.Set;

public record FactOverrideRequest(
        ExtractedFacts updatedFacts,
        ExtractedFactsDTO updatedRuleFacts,
        Set<String> fieldsToLock
) {}
