package com.app.refirm.intake;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * AI intake layer that reads unstructured client narratives and maps them into
 * {@link ExtractedFactsDTO} using Spring AI's Structured Outputs API.
 */
@Service
public class LunaFactExtractorService {

    private static final Logger log = LoggerFactory.getLogger(LunaFactExtractorService.class);

    private static final String SYSTEM_PROMPT = """
            You are a surgical legal fact-extraction system.
            1. Analyze the user's unstructured story and classify the most relevant LegalDomain.
            2. Extract exact numeric thresholds (financial amounts, physical area). NEVER guess or assume. If the user does not explicitly provide a number, the value MUST be null.
            3. Extract the physical location/city and the counterparty type (e.g., 'Bank', 'Landlord', 'Spouse').
            """;

    private final ChatClient chatClient;

    public LunaFactExtractorService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-6-luna")
                        .temperature(1.0)
                        .build())
                .build();
    }

    /**
     * Extracts structured legal facts from unstructured user input.
     *
     * @param userStory unstructured narrative provided by the client
     * @return populated {@link ExtractedFactsDTO} with nulls for any absent facts
     */
    public ExtractedFactsDTO extractFacts(String userStory) {
        if (userStory == null || userStory.isBlank()) {
            return ExtractedFactsDTO.empty();
        }

        log.info("Extracting legal facts from story of length: {}", userStory.length());

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userStory)
                .call()
                .entity(ExtractedFactsDTO.class);
    }
}
