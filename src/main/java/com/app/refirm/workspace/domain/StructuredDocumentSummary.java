package com.app.refirm.workspace.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Rich structured document summary containing executive gist, factual chronology,
 * parties/roles, financial figures, and an optional IRAC brief for precedent judgments.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredDocumentSummary(
        DocumentType detectedType,
        String title,
        String executiveGist,
        List<String> chronologyOfEvents,
        List<String> keyPartiesAndRoles,
        List<String> financialAndReliefFigures,
        IracBrief iracBrief
) {
    public StructuredDocumentSummary {
        title = title != null ? title : "";
        executiveGist = executiveGist != null ? executiveGist : "";
        chronologyOfEvents = chronologyOfEvents != null ? List.copyOf(chronologyOfEvents) : List.of();
        keyPartiesAndRoles = keyPartiesAndRoles != null ? List.copyOf(keyPartiesAndRoles) : List.of();
        financialAndReliefFigures = financialAndReliefFigures != null ? List.copyOf(financialAndReliefFigures) : List.of();
    }

    public static StructuredDocumentSummary empty(DocumentType type, String title) {
        return new StructuredDocumentSummary(type, title, "", List.of(), List.of(), List.of(), null);
    }
}
