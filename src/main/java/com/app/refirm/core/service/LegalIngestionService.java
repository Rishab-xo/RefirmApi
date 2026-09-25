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

    @Value("classpath:data/clean_core_laws.json")
    private Resource cleanLawsJson;

    public LegalIngestionService(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    public String ingestStatutes() {
        try {
            log.info("Starting loss-free structural ingestion with OpenAI embeddings...");
            List<Map<String, String>> acts = objectMapper.readValue(
                    cleanLawsJson.getInputStream(),
                    new TypeReference<>() {}
            );

            List<Document> allDocuments = new ArrayList<>();

            for (Map<String, String> act : acts) {
                String actName = act.get("act_name");
                String domain = act.get("legal_domain");
                String text = act.get("raw_text");

                log.info("Parsing sections for: {}", actName);

                // Regex splits text cleanly by Section or Clause boundaries
                String[] sections = text.split("\n(?=(?:Section |Clause )?\\d+[a-zA-Z]?\\.)");

                for (int i = 0; i < sections.length; i++) {
                    String sectionText = sections[i].trim();
                    if (sectionText.length() < 25) continue; // Skip residual fragments

                    // FAILSAFE: Zero-Loss Splitter for massive Schedules
                    int maxCharLength = 12000; // Extremely safe limit (~3000 tokens for OpenAI 8k limit)

                    if (sectionText.length() > maxCharLength) {
                        log.warn("Massive text block found in {} (Length: {}). Applying safe newline slicing.", actName, sectionText.length());

                        String[] lines = sectionText.split("\n");
                        StringBuilder chunkBuilder = new StringBuilder();
                        int subChunkIndex = 0;

                        for (String line : lines) {
                            // ULTIMATE FALLBACK: If a single line has no breaks and exceeds 12k chars, force slice it
                            if (line.length() > maxCharLength) {
                                int index = 0;
                                while (index < line.length()) {
                                    int end = Math.min(index + maxCharLength, line.length());
                                    String forcedSlice = line.substring(index, end);
                                    saveChunk(actName, domain, forcedSlice, i + "_forced_" + subChunkIndex, allDocuments);
                                    index = end;
                                    subChunkIndex++;
                                }
                                continue;
                            }

                            // Flush accumulated text if adding the next line breaches the limit
                            if (chunkBuilder.length() + line.length() > maxCharLength) {
                                saveChunk(actName, domain, chunkBuilder.toString(), i + "_part_" + subChunkIndex, allDocuments);
                                chunkBuilder = new StringBuilder();
                                subChunkIndex++;
                            }
                            chunkBuilder.append(line).append("\n");
                        }

                        // Save any residual text in the builder
                        if (chunkBuilder.length() > 0) {
                            saveChunk(actName, domain, chunkBuilder.toString(), i + "_part_" + subChunkIndex, allDocuments);
                        }

                    } else {
                        // Standard safe section chunking
                        saveChunk(actName, domain, sectionText, String.valueOf(i), allDocuments);
                    }
                }
            }

            log.info("Generated {} atomic section chunks. Uploading to Neon PgVector in batches of 50...", allDocuments.size());

            // 50 documents per batch is optimal for Neon database transaction sizes and OpenAI rate limits
            int batchSize = 50;
            for (int i = 0; i < allDocuments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allDocuments.size());
                List<Document> batch = allDocuments.subList(i, end);
                vectorStore.accept(batch);
                log.info("Successfully pushed batch {} to {} into Neon", i, end);
            }

            return "Successfully indexed " + allDocuments.size() + " legal sections into Neon PgVector.";

        } catch (IOException e) {
            log.error("Failed to read clean_core_laws.json", e);
            throw new RuntimeException("Ingestion failed", e);
        }
    }

    private void saveChunk(String actName, String domain, String text, String indexString, List<Document> allDocuments) {
        String cleanText = text.trim();
        if (cleanText.isEmpty()) return;

        // Embed the act name directly into the AI's readable text so it never loses context
        String enrichedContent = String.format("[Act: %s]\n%s", actName, cleanText);

        Document doc = new Document(enrichedContent, Map.of(
                "act_name", actName,
                "legal_domain", domain,
                "chunk_index", indexString
        ));
        allDocuments.add(doc);
    }
}