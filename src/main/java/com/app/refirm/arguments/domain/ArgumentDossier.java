package com.app.refirm.arguments.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Complete structured courtroom argument preparation dossier including 60-second pitch,
 * framed legal issues, substantive submissions, rebuttal matrix, bench grill questions,
 * and pre-hearing evidentiary checklist.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArgumentDossier(
        String openingPitch60Seconds,
        List<FramedLegalIssue> framedIssues,
        List<ArgumentSubmission> coreSubmissions,
        List<RebuttalMatrixItem> rebuttalMatrix,
        List<BenchQuestionItem> benchGrillQuestions,
        List<String> preHearingEvidenceChecklist
) {
    public ArgumentDossier {
        openingPitch60Seconds = openingPitch60Seconds != null ? openingPitch60Seconds : "";
        framedIssues = framedIssues != null ? List.copyOf(framedIssues) : List.of();
        coreSubmissions = coreSubmissions != null ? List.copyOf(coreSubmissions) : List.of();
        rebuttalMatrix = rebuttalMatrix != null ? List.copyOf(rebuttalMatrix) : List.of();
        benchGrillQuestions = benchGrillQuestions != null ? List.copyOf(benchGrillQuestions) : List.of();
        preHearingEvidenceChecklist = preHearingEvidenceChecklist != null ? List.copyOf(preHearingEvidenceChecklist) : List.of();
    }

    public static ArgumentDossier empty() {
        return new ArgumentDossier("", List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
