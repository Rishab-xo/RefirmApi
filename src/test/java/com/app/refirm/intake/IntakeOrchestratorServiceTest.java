package com.app.refirm.intake;

import com.app.refirm.intake.payload.IntakeRequest;
import com.app.refirm.intake.payload.IntakeResponse;
import com.app.refirm.intake.payload.IntakeStatus;
import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.triage.dto.CategorizationResult;
import com.app.refirm.triage.dto.FullIntakeResponse;
import com.app.refirm.triage.entities.LegalCategory;
import com.app.refirm.triage.service.TriageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("2-Strike IntakeOrchestratorService Tests")
class IntakeOrchestratorServiceTest {

    private LunaFactExtractorService lunaExtractor;
    private TriageService triageService;
    private IntakeOrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        lunaExtractor = mock(LunaFactExtractorService.class);
        triageService = mock(TriageService.class);
        orchestratorService = new IntakeOrchestratorService(lunaExtractor, triageService);
    }

    @Test
    @DisplayName("Strike 1: Missing disputeAmount triggers MISSING_DATA and follow-up question")
    void shouldReturnMissingDataOnTurn1WhenDisputeAmountMissing() {
        // Given: Turn 1 narrative missing financial amount
        IntakeRequest request = new IntakeRequest("My landlord locked me out of the house.", 1);
        ExtractedFactsDTO factsWithoutAmount = new ExtractedFactsDTO(
                LegalDomain.TENANCY,
                null,
                "Delhi",
                "Landlord",
                null
        );
        when(lunaExtractor.extractFacts(request.userStory())).thenReturn(factsWithoutAmount);

        // When
        IntakeResponse response = orchestratorService.processIntake(request);

        // Then
        assertEquals(IntakeStatus.MISSING_DATA, response.status());
        assertNotNull(response.followUpQuestion());
        assertTrue(response.followUpQuestion().contains("estimate the total financial amount"));
        assertNull(response.generatedBrief());
        verify(triageService, never()).processIntakePipeline(any(), any());
    }

    @Test
    @DisplayName("Strike 1: UNKNOWN domain triggers MISSING_DATA and follow-up question")
    void shouldReturnMissingDataOnTurn1WhenDomainUnknown() {
        IntakeRequest request = new IntakeRequest("Someone did something bad to me.", 1);
        ExtractedFactsDTO factsUnknownDomain = new ExtractedFactsDTO(
                LegalDomain.UNKNOWN,
                50000L,
                "Delhi",
                "Person",
                null
        );
        when(lunaExtractor.extractFacts(request.userStory())).thenReturn(factsUnknownDomain);

        IntakeResponse response = orchestratorService.processIntake(request);

        assertEquals(IntakeStatus.MISSING_DATA, response.status());
        assertNotNull(response.followUpQuestion());
        assertNull(response.generatedBrief());
        verify(triageService, never()).processIntakePipeline(any(), any());
    }

    @Test
    @DisplayName("Turn 1 Complete: All critical data present directly generates PROCESSED brief")
    void shouldGenerateBriefOnTurn1WhenAllCriticalDataPresent() {
        IntakeRequest request = new IntakeRequest("My landlord in Delhi refused my 50000 deposit refund.", 1);
        ExtractedFactsDTO completeFacts = new ExtractedFactsDTO(
                LegalDomain.TENANCY,
                50000L,
                "Delhi",
                "Landlord",
                null
        );
        when(lunaExtractor.extractFacts(request.userStory())).thenReturn(completeFacts);

        FullIntakeResponse fullResponse = new FullIntakeResponse(
                new CategorizationResult(LegalCategory.TENANCY, List.of(), List.of(), 0.95, "Tenancy dispute"),
                "# FORMAL LEGAL BRIEF\nDraft content",
                1
        );
        when(triageService.processIntakePipeline(eq(request.userStory()), eq(completeFacts))).thenReturn(fullResponse);

        IntakeResponse response = orchestratorService.processIntake(request);

        assertEquals(IntakeStatus.PROCESSED, response.status());
        assertNull(response.followUpQuestion());
        assertEquals("# FORMAL LEGAL BRIEF\nDraft content", response.generatedBrief());
        verify(triageService).processIntakePipeline(request.userStory(), completeFacts);
    }

    @Test
    @DisplayName("Strike 2: Turn 2 bypasses missing data and fails-open to generate PROCESSED brief")
    void shouldBypassMissingDataOnTurn2AndGenerateBrief() {
        // Given: Turn 2 narrative still missing disputeAmount
        IntakeRequest request = new IntakeRequest("I still don't know the exact amount, but he evicted me.", 2);
        ExtractedFactsDTO stillMissingFacts = new ExtractedFactsDTO(
                LegalDomain.TENANCY,
                null,
                "Delhi",
                "Landlord",
                null
        );
        when(lunaExtractor.extractFacts(request.userStory())).thenReturn(stillMissingFacts);

        FullIntakeResponse fullResponse = new FullIntakeResponse(
                new CategorizationResult(LegalCategory.TENANCY, List.of(), List.of(), 0.90, "Tenancy dispute"),
                "# FORMAL LEGAL BRIEF\n## IV. ASSUMPTIONS MADE\nCaveat included.",
                1
        );
        when(triageService.processIntakePipeline(eq(request.userStory()), eq(stillMissingFacts))).thenReturn(fullResponse);

        // When
        IntakeResponse response = orchestratorService.processIntake(request);

        // Then
        assertEquals(IntakeStatus.PROCESSED, response.status());
        assertNull(response.followUpQuestion());
        assertEquals("# FORMAL LEGAL BRIEF\n## IV. ASSUMPTIONS MADE\nCaveat included.", response.generatedBrief());
        verify(triageService).processIntakePipeline(request.userStory(), stillMissingFacts);
    }

    @Test
    @DisplayName("Handles null or blank userStory safely with clarification prompt")
    void shouldHandleNullStorySafely() {
        IntakeResponse response = orchestratorService.processIntake(new IntakeRequest(null, 1));
        assertEquals(IntakeStatus.MISSING_DATA, response.status());
        assertNotNull(response.followUpQuestion());
        assertNull(response.generatedBrief());
    }
}
