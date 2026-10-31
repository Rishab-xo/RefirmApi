package com.app.refirm.workspace.ingestion;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.workspace.domain.DocumentOkfManifest;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.ExtractedPage;
import com.app.refirm.workspace.domain.StructuredDocumentSummary;

import java.util.List;

/**
 * Strategy interface for extracting lightweight Page-OKF manifest, rich structured summaries,
 * and dispute facts from uploaded case documents.
 */
public interface DocumentOkfIndexer {

    record IndexingOutput(
            DocumentOkfManifest manifest,
            StructuredDocumentSummary summary,
            ExtractedFacts extractedFacts,
            ExtractedFactsDTO ruleFacts
    ) {}

    /**
     * Analyzes and indexes document pages.
     *
     * @param fileName      Name of the uploaded document
     * @param requestedType Declared or detected document classification
     * @param pages         Validated pages extracted from the document
     * @return IndexingOutput containing manifest, summary, and client dispute facts (if not a precedent)
     */
    IndexingOutput indexDocument(String fileName, DocumentType requestedType, List<ExtractedPage> pages);
}
