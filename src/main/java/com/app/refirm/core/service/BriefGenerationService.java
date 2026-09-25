package com.app.refirm.core.service;

import com.app.refirm.core.dto.GenerateBriefResponse;
import com.app.refirm.triage.entities.LegalCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BriefGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BriefGenerationService.class);

    private final ChatClient chatClient;
    private final LegalRetrievalService retrievalService;

    // Loads the externalized prompt template from resources
    @Value("classpath:prompts/brief-system.st")
    private Resource systemPromptTemplate;

    public BriefGenerationService(ChatClient.Builder chatClientBuilder, LegalRetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalService = retrievalService;
    }

    /**
     * Generates a legal petition/brief from structured gathered facts.
     * <p>
     * This is the primary method — it takes the structured fact map from the intake
     * checklist and formats it into a professional input for the drafting LLM.
     *
     * @param gatheredFacts  Key-value map of facts (e.g., "nature_of_dispute" -> "Illegal lockout by landlord")
     * @param legalDomain    Raw enum string or human label (e.g., "CIVIL_PROPERTY" or "Civil Property")
     * @param categoryLabel  Human-readable category label (e.g., "Civil & Property Law")
     * @return GenerateBriefResponse containing draft markdown, retrieved statutes, and hasStatutes flag
     */
    public GenerateBriefResponse generateDraftFromFacts(Map<String, String> gatheredFacts, String legalDomain, String categoryLabel) {
        log.info("Generating draft from {} structured facts for domain: {}", gatheredFacts.size(), legalDomain);

        // 1. Format gathered facts into a structured, readable text block
        String structuredFactsText = formatGatheredFacts(gatheredFacts, categoryLabel);

        // 2. RAG Retrieval — use structured facts as the semantic query
        String retrievedLaws;
        boolean hasStatutes = true;
        try {
            retrievedLaws = retrievalService.retrieveRelevantStatutes(structuredFactsText, legalDomain);
            log.info("Successfully retrieved statutes for domain: {}", legalDomain);
        } catch (IllegalStateException e) {
            if ("INSUFFICIENT_STATUTES".equals(e.getMessage())) {
                log.warn("No statutes found for domain: {}. Generating brief with advisory note.", legalDomain);
                hasStatutes = false;
                retrievedLaws = "[NO STATUTES RETRIEVED — The vector database did not return relevant statutory provisions for domain: "
                        + legalDomain + ". The advocate must independently research and cite applicable Acts and Sections.]";
            } else {
                throw e;
            }
        }

        final String statutesForPrompt = retrievedLaws;

        // 3. Drafting — send structured facts + retrieved statutes to the LLM
        String draft = chatClient.prompt()
                .system(sys -> sys.text(systemPromptTemplate)
                        .param("statutes", statutesForPrompt))
                .user(structuredFactsText)
                .call()
                .content();

        log.info("Draft generation complete. Output length: {} chars", draft != null ? draft.length() : 0);
        return new GenerateBriefResponse(draft, retrievedLaws, hasStatutes);
    }

    /**
     * Legacy method — accepts raw text facts and a domain string.
     */
    public GenerateBriefResponse generateDraft(String sessionFacts, String legalDomain) {
        log.info("generateDraft called with domain: {}", legalDomain);
        String retrievedLaws;
        boolean hasStatutes = true;
        try {
            retrievedLaws = retrievalService.retrieveRelevantStatutes(sessionFacts, legalDomain);
        } catch (IllegalStateException e) {
            if ("INSUFFICIENT_STATUTES".equals(e.getMessage())) {
                log.warn("No statutes found for legacy domain: {}", legalDomain);
                hasStatutes = false;
                retrievedLaws = "[NO STATUTES RETRIEVED — The vector database did not return relevant statutory provisions for domain: "
                        + legalDomain + ". The advocate must independently research and cite applicable Acts and Sections.]";
            } else {
                throw e;
            }
        }

        final String statutesForPrompt = retrievedLaws;

        String draft = chatClient.prompt()
                .system(sys -> sys.text(systemPromptTemplate)
                        .param("statutes", statutesForPrompt))
                .user(sessionFacts != null ? sessionFacts : "")
                .call()
                .content();

        return new GenerateBriefResponse(draft, retrievedLaws, hasStatutes);
    }

    /**
     * Formats the gathered facts map into a clean, structured text block
     * that serves as both the RAG query and the user context for the drafting LLM.
     */
    private String formatGatheredFacts(Map<String, String> gatheredFacts, String categoryLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("## CASE CATEGORY: ").append(categoryLabel != null ? categoryLabel : "General Legal").append("\n\n");
        sb.append("## GATHERED FACTS:\n\n");

        if (gatheredFacts != null) {
            for (Map.Entry<String, String> entry : gatheredFacts.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                // Skip UNKNOWN facts — they'll become [INSERT X] placeholders in the brief
                if (value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value.trim())) {
                    sb.append("- **").append(formatKey(key)).append(":** [INFORMATION NOT PROVIDED — use placeholder]\n");
                } else {
                    sb.append("- **").append(formatKey(key)).append(":** ").append(value).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Converts snake_case keys to Human Readable Labels.
     * e.g., "nature_of_dispute" -> "Nature Of Dispute"
     */
    private String formatKey(String snakeCaseKey) {
        if (snakeCaseKey == null) return "";
        String[] words = snakeCaseKey.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }
}