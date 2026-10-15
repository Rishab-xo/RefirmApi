package com.app.refirm.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LegalIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LegalIngestionService.class);

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    // Legacy JSON ingestion disabled in favor of OKF markdown bundles
    // @Value("classpath:data/*.json")
    // private Resource[] lawFiles;

    public LegalIngestionService(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    public String ingestStatutes() {
        log.info("Legacy JSON ingestion skipped - OKF markdown bundles are active.");
        return "Legacy JSON ingestion disabled in favor of OKF markdown bundles.";
    }
}