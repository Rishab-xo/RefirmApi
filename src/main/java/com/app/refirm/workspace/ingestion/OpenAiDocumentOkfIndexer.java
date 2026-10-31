package com.app.refirm.workspace.ingestion;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.domain.*;
import com.app.refirm.workspace.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.app.refirm.workspace.util.JsonFenceSanitizer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Windowed Page-OKF Indexer synthesizing document text into a lightweight manifest,
 * rich structured summary (with IRAC for precedents), and dispute facts.
 */
@Service
public class OpenAiDocumentOkfIndexer implements DocumentOkfIndexer {

    private static final Logger log = LoggerFactory.getLogger(OpenAiDocumentOkfIndexer.class);

    public static final int MAX_PAGES_PER_BATCH = 15;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenAiDocumentOkfIndexer(
            @Qualifier("extractionChatClient") ChatClient chatClient,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public OpenAiDocumentOkfIndexer(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder != null ? builder.build() : null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public IndexingOutput indexDocument(String fileName, DocumentType requestedType, List<ExtractedPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return new IndexingOutput(
                    DocumentOkfManifest.empty(),
                    StructuredDocumentSummary.empty(requestedType, fileName),
                    null,
                    null
            );
        }

        int totalPages = pages.size();
        List<ExtractedPage> windowedPages = pages.stream().limit(MAX_PAGES_PER_BATCH).toList();

        StringBuilder formattedPages = new StringBuilder();
        for (ExtractedPage page : windowedPages) {
            formattedPages.append(String.format("=== [PAGE %d OF %d] ===\n%s\n\n",
                    page.pageNumber(), totalPages, page.rawText()));
        }

        String systemPrompt = """
            You are Refirm's Senior Judicial Document Indexer and Evidence Analyst.
            Analyze the provided document text (windowed across labeled pages) and extract structured JSON state.
            
            Document Type: %s
            Total Pages: %d
            
            Rules:
            1. Extract a 2-line executiveGist.
            2. Extract keyEntities (companies, banks, merchants, courts, parties).
            3. Extract importantDates (in ISO or YYYY-MM-DD/DD-MM-YYYY format).
            4. Extract monetaryAmounts (fees, charges, compensations with currency).
            5. In topicPagePins, map specific evidence topics (e.g. "pos_receipt", "denial_email", "defective_invoice", "arbitration_clause")
               to the EXACT 1-based page numbers where they appear (e.g. {"pos_receipt": [1], "denial_letter": [2, 3]}).
            6. For chronologyOfEvents, list significant chronological events.
            7. If Document Type is JUDGMENT_PRECEDENT or IMPUGNED_ORDER, populate iracBrief:
               {"facts": "...", "issuesFramed": [...], "provisionsCited": [...], "ratioDecidendi": "...", "finalOrderHeld": "..."}.
            8. If Document Type is an evidentiary dispute document (e.g., INVOICE_RECEIPT, LEGAL_NOTICE, CONTRACT_AGREEMENT, EVIDENCE_OTHER):
               Extract claimant, oppositeParties, and estimatedFinancialLoss.
            9. Output pure JSON matching the requested schema. Do not output markdown code fences.
            """.formatted(requestedType.name(), totalPages);

        try {
            String rawJson = chatClient.prompt()
                    .system(systemPrompt)
                    .user(formattedPages.toString())
                    .call()
                    .content();

            String cleanJson = JsonFenceSanitizer.sanitizeJson(rawJson);
            DocumentAnalysisDto parsed = objectMapper.readValue(cleanJson, DocumentAnalysisDto.class);

            DocumentOkfManifest rawManifest = new DocumentOkfManifest(
                    parsed.executiveGist != null ? parsed.executiveGist : "",
                    parsed.keyEntities != null ? parsed.keyEntities : List.of(),
                    parsed.importantDates != null ? parsed.importantDates : List.of(),
                    parsed.monetaryAmounts != null ? parsed.monetaryAmounts : List.of(),
                    parsed.topicPagePins != null ? parsed.topicPagePins : Map.of()
            );

            DocumentOkfManifest sanitizedManifest = sanitizeManifestBounds(rawManifest, totalPages);

            IracBrief irac = null;
            if (parsed.iracBrief != null && (requestedType == DocumentType.JUDGMENT_PRECEDENT || requestedType == DocumentType.IMPUGNED_ORDER)) {
                irac = new IracBrief(
                        parsed.iracBrief.facts != null ? parsed.iracBrief.facts : "",
                        parsed.iracBrief.issuesFramed != null ? parsed.iracBrief.issuesFramed : List.of(),
                        parsed.iracBrief.provisionsCited != null ? parsed.iracBrief.provisionsCited : List.of(),
                        parsed.iracBrief.ratioDecidendi != null ? parsed.iracBrief.ratioDecidendi : "",
                        parsed.iracBrief.finalOrderHeld != null ? parsed.iracBrief.finalOrderHeld : ""
                );
            }

            StructuredDocumentSummary summary = new StructuredDocumentSummary(
                    requestedType,
                    fileName,
                    sanitizedManifest.executiveGist(),
                    parsed.chronologyOfEvents != null ? parsed.chronologyOfEvents : List.of(),
                    parsed.keyPartiesAndRoles != null ? parsed.keyPartiesAndRoles : List.of(),
                    parsed.financialAndReliefFigures != null ? parsed.financialAndReliefFigures : List.of(),
                    irac
            );

            // Precedent Contamination Guardrail
            if (requestedType == DocumentType.JUDGMENT_PRECEDENT) {
                log.info("Document '{}' is JUDGMENT_PRECEDENT. Suppressing fact ledger updates.", fileName);
                return new IndexingOutput(sanitizedManifest, summary, null, null);
            }

            ExtractedFacts extractedFacts = null;
            ExtractedFactsDTO ruleFacts = null;

            if (parsed.extractedFacts != null) {
                PartyEntity claimant = parsed.extractedFacts.claimantName != null && !parsed.extractedFacts.claimantName.isBlank()
                        ? new PartyEntity(parsed.extractedFacts.claimantName, EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE)
                        : null;

                List<PartyEntity> oppParties = new ArrayList<>();
                if (parsed.extractedFacts.oppositeParties != null) {
                    for (String opp : parsed.extractedFacts.oppositeParties) {
                        if (opp != null && !opp.isBlank()) {
                            oppParties.add(new PartyEntity(opp, EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE));
                        }
                    }
                }

                Double loss = parsed.extractedFacts.estimatedFinancialLoss != null ? parsed.extractedFacts.estimatedFinancialLoss : 0.0;
                extractedFacts = new ExtractedFacts(
                        LegalDomain.fromStringSafe(parsed.extractedFacts.domain),
                        claimant,
                        null,
                        oppParties,
                        List.of(),
                        loss
                );

                com.app.refirm.rules.model.LegalDomain rDomain = parsed.extractedFacts.domain != null
                        ? com.app.refirm.rules.model.LegalDomain.fromStringSafe(parsed.extractedFacts.domain)
                        : null;

                ruleFacts = new ExtractedFactsDTO(
                        rDomain,
                        loss > 0 ? Math.round(loss) : null,
                        parsed.extractedFacts.location,
                        !oppParties.isEmpty() ? oppParties.get(0).name() : null,
                        null
                );
            }

            return new IndexingOutput(sanitizedManifest, summary, extractedFacts, ruleFacts);

        } catch (Exception e) {
            log.warn("Document indexing via ChatClient failed or returned non-JSON for '{}': {}. Generating heuristic fallback.",
                    fileName, e.getMessage());

            DocumentOkfManifest fallbackManifest = sanitizeManifestBounds(
                    new DocumentOkfManifest(
                            "Document content analyzed across " + totalPages + " pages.",
                            List.of(),
                            List.of(),
                            List.of(),
                            Map.of("general_substance", List.of(1))
                    ),
                    totalPages
            );

            StructuredDocumentSummary fallbackSummary = StructuredDocumentSummary.empty(requestedType, fileName);
            return new IndexingOutput(fallbackManifest, fallbackSummary, null, null);
        }
    }

    /**
     * Sanitizes topicPagePins to ensure all page numbers are strictly within 1..totalPages.
     */
    public static DocumentOkfManifest sanitizeManifestBounds(DocumentOkfManifest raw, int totalPages) {
        if (raw == null) {
            return DocumentOkfManifest.empty();
        }

        int maxPage = Math.max(1, totalPages);
        Map<String, List<Integer>> cleanPins = new LinkedHashMap<>();

        if (raw.topicPagePins() != null) {
            for (Map.Entry<String, List<Integer>> entry : raw.topicPagePins().entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().trim() : "";
                if (key.isBlank()) continue;

                List<Integer> validPages = entry.getValue() != null
                        ? entry.getValue().stream()
                        .filter(Objects::nonNull)
                        .filter(p -> p >= 1 && p <= maxPage)
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList())
                        : List.of();

                if (!validPages.isEmpty()) {
                    cleanPins.put(key, validPages);
                }
            }
        }

        if (cleanPins.isEmpty() && totalPages >= 1) {
            cleanPins.put("general_substance", List.of(1));
        }

        return new DocumentOkfManifest(
                raw.executiveGist(),
                raw.keyEntities(),
                raw.importantDates(),
                raw.monetaryAmounts(),
                cleanPins
        );
    }

    // ─── INTERNAL DESERIALIZATION DTOS ───

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DocumentAnalysisDto {
        public String executiveGist;
        public List<String> keyEntities;
        public List<String> importantDates;
        public List<String> monetaryAmounts;
        public Map<String, List<Integer>> topicPagePins;
        public List<String> chronologyOfEvents;
        public List<String> keyPartiesAndRoles;
        public List<String> financialAndReliefFigures;
        public IracDto iracBrief;
        public FactDto extractedFacts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class IracDto {
        public String facts;
        public List<String> issuesFramed;
        public List<String> provisionsCited;
        public String ratioDecidendi;
        public String finalOrderHeld;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FactDto {
        public String domain;
        public String claimantName;
        public List<String> oppositeParties;
        public Double estimatedFinancialLoss;
        public String location;
    }
}
