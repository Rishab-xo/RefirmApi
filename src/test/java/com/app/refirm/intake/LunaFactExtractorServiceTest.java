package com.app.refirm.intake;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.rules.model.LegalDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("LunaFactExtractorService Tests")
class LunaFactExtractorServiceTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;
    private LunaFactExtractorService extractorService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);

        extractorService = new LunaFactExtractorService(builder);
    }

    @Test
    @DisplayName("Returns empty ExtractedFactsDTO when user story is null or blank without invoking LLM")
    void shouldReturnEmptyWhenStoryIsBlank() {
        ExtractedFactsDTO resultNull = extractorService.extractFacts(null);
        assertNotNull(resultNull);
        assertNull(resultNull.domain());
        assertNull(resultNull.disputeAmount());
        assertNull(resultNull.location());

        ExtractedFactsDTO resultBlank = extractorService.extractFacts("   ");
        assertNotNull(resultBlank);
        assertNull(resultBlank.domain());
        assertNull(resultBlank.disputeAmount());

        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("Extracts structured facts by calling ChatClient with system prompt and entity parsing")
    void shouldExtractStructuredFacts() {
        ExtractedFactsDTO expectedDto = new ExtractedFactsDTO(
                LegalDomain.TENANCY,
                45000L,
                "Bengaluru",
                "Landlord",
                120
        );
        when(responseSpec.entity(ExtractedFactsDTO.class)).thenReturn(expectedDto);

        String story = "My landlord in Bengaluru locked me out of my 120 sqm flat over 45000 rent dispute.";
        ExtractedFactsDTO result = extractorService.extractFacts(story);

        assertNotNull(result);
        assertEquals(LegalDomain.TENANCY, result.domain());
        assertEquals(45000L, result.disputeAmount());
        assertEquals("Bengaluru", result.location());
        assertEquals("Landlord", result.counterpartyType());
        assertEquals(120, result.propertySizeSqMeters());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);

        verify(requestSpec).system(systemCaptor.capture());
        verify(requestSpec).user(userCaptor.capture());

        assertTrue(systemCaptor.getValue().contains("surgical legal fact-extraction"));
        assertTrue(systemCaptor.getValue().contains("NEVER guess or assume"));
        assertEquals(story, userCaptor.getValue());
    }

    @Test
    @DisplayName("Preserves null for missing fields without hallucinations")
    void shouldPreserveNullForMissingFacts() {
        ExtractedFactsDTO expectedDto = new ExtractedFactsDTO(
                LegalDomain.BANKING,
                null,
                "Mumbai",
                "Bank",
                null
        );
        when(responseSpec.entity(ExtractedFactsDTO.class)).thenReturn(expectedDto);

        String story = "The bank branch in Mumbai froze my account without explaining why.";
        ExtractedFactsDTO result = extractorService.extractFacts(story);

        assertNotNull(result);
        assertEquals(LegalDomain.BANKING, result.domain());
        assertNull(result.disputeAmount());
        assertEquals("Mumbai", result.location());
        assertEquals("Bank", result.counterpartyType());
        assertNull(result.propertySizeSqMeters());
    }
}
