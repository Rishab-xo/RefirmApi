package com.app.refirm.core.service;

import com.app.refirm.triage.entities.LegalCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class LegalRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(LegalRetrievalService.class);
    private final VectorStore vectorStore;

    public LegalRetrievalService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public String retrieveRelevantStatutes(String caseFacts, String legalDomain) {

        // ARCHITECTURAL FIX: Rely exclusively on the centralized LegalCategory enum for sanitization.
        // fromStringSafe() normalizes any dirty input (frontend or DB).
        // getDbDomain() guarantees perfect alignment with Neon PgVector metadata tags.
        LegalCategory resolvedCategory = LegalCategory.fromStringSafe(legalDomain);
        String targetDomain = resolvedCategory.getDbDomain();

        log.info("Executing semantic RAG query for database domain: {} (resolved from raw input: {})", targetDomain, legalDomain);

        // Safe, programmatic metadata filtering prevents NoSQL/Vector injection
        var filterBuilder = new FilterExpressionBuilder();

        SearchRequest request = SearchRequest.builder()
                .query(caseFacts)
                .topK(8) // Increased to ensure both Procedural and Substantive laws are captured
                .filterExpression(filterBuilder.eq("legal_domain", targetDomain).build())
                .build();

        List<Document> relevantDocuments = vectorStore.similaritySearch(request);

        if (relevantDocuments.isEmpty()) {
            log.warn("Threshold starvation: No relevant statutes found for database domain: {}", targetDomain);
            // Throw a specific exception to short-circuit the BriefGenerationService
            throw new IllegalStateException("INSUFFICIENT_STATUTES");
        }

        return relevantDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}