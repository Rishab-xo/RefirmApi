package com.app.refirm.workspace.domain;

/**
 * 1-based page number and raw extracted text from a document.
 */
public record ExtractedPage(
        int pageNumber,
        String rawText
) {
    public ExtractedPage {
        rawText = rawText != null ? rawText : "";
    }
}
