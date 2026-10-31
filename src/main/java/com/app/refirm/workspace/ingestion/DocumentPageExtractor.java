package com.app.refirm.workspace.ingestion;

import com.app.refirm.workspace.domain.ExtractedPage;

import java.util.List;

/**
 * Strategy interface for extracting page-by-page text from documents.
 */
public interface DocumentPageExtractor {

    /**
     * Extracts text page-by-page from raw file bytes.
     *
     * @param fileName  Original name of the file
     * @param fileBytes Binary file content
     * @return List of ExtractedPage records with 1-based page numbers
     */
    List<ExtractedPage> extractPages(String fileName, byte[] fileBytes);
}
