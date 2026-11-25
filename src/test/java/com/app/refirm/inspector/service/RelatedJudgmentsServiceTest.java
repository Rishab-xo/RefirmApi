package com.app.refirm.inspector.service;

import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedCitationPageResponse;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedJudgmentItem;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.RelatedJudgmentsResponse;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository.CitationSummaryCounts;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository.SimilarCandidateRow;
import com.app.refirm.inspector.repository.RelatedPrecedentRepository.TargetContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RelatedJudgmentsService Unit Tests")
class RelatedJudgmentsServiceTest {

    @Mock
    private RelatedPrecedentRepository relatedRepository;

    private PariMateriaCrosswalkService crosswalkService;
    private RelatedJudgmentsService service;

    @BeforeEach
    void setUp() {
        crosswalkService = new PariMateriaCrosswalkService(new ObjectMapper());
        crosswalkService.init();
        service = new RelatedJudgmentsService(relatedRepository, crosswalkService);
    }

    @Test
    @DisplayName("(a) Missing authorityId throws 404 NOT_FOUND on both methods")
    void missingAuthorityThrows404OnBothMethods() {
        when(relatedRepository.findTargetContext("missing-id")).thenReturn(Optional.empty());

        ResponseStatusException ex1 = assertThrows(
                ResponseStatusException.class,
                () -> service.getRelatedJudgments("missing-id", 15)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex1.getStatusCode());

        ResponseStatusException ex2 = assertThrows(
                ResponseStatusException.class,
                () -> service.getRelatedCitationsPage("missing-id", "CITED_BY", "ALL", 0, 20)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex2.getStatusCode());

        ResponseStatusException exBlank = assertThrows(
                ResponseStatusException.class,
                () -> service.getRelatedJudgments("   ", 15)
        );
        assertEquals(HttpStatus.NOT_FOUND, exBlank.getStatusCode());
    }

    @Test
    @DisplayName("(b) Invalid direction or treatment throws 400 BAD_REQUEST")
    void invalidDirectionOrTreatmentThrows400() {
        ResponseStatusException badDir = assertThrows(
                ResponseStatusException.class,
                () -> service.getRelatedCitationsPage("auth-1", "INVALID_DIR", "ALL", 0, 20)
        );
        assertEquals(HttpStatus.BAD_REQUEST, badDir.getStatusCode());

        ResponseStatusException badTreat = assertThrows(
                ResponseStatusException.class,
                () -> service.getRelatedCitationsPage("auth-1", "CITED_BY", "ONLY_GOOD", 0, 20)
        );
        assertEquals(HttpStatus.BAD_REQUEST, badTreat.getStatusCode());

        verifyNoInteractions(relatedRepository);
    }

    @Test
    @DisplayName("(c) Null or blank embeddingLiteral skips ANN search and returns empty similarCases")
    void nullEmbeddingSkipsVectorSearchCleanly() throws Exception {
        TargetContext target = new TargetContext("auth-novec", "CRIMINAL", List.of("IPC:420"), null);
        RelatedJudgmentItem citedItem = sampleCitationItem("c-1", "OVERRULED", "Court held X.", "Para 12", "quote x");
        RelatedJudgmentItem reliesItem = sampleCitationItem("r-1", "CITED", null, "Para 3", "quote y");

        when(relatedRepository.findTargetContext("auth-novec")).thenReturn(Optional.of(target));
        when(relatedRepository.countCitationSummary("auth-novec")).thenReturn(new CitationSummaryCounts(12, 4, 2));
        when(relatedRepository.findCitedBy("auth-novec", false, 0, 15)).thenReturn(List.of(citedItem));
        when(relatedRepository.findReliesOn("auth-novec", false, 0, 15)).thenReturn(List.of(reliesItem));

        RelatedJudgmentsResponse resp = service.getRelatedJudgments("auth-novec", 15);

        assertEquals("auth-novec", resp.authorityId());
        assertEquals(12, resp.citedByCount());
        assertEquals(4, resp.reliesOnCount());
        assertEquals(2, resp.negativeTreatmentCount());
        assertEquals(1, resp.citedBy().size());
        assertEquals(1, resp.reliesOn().size());
        assertTrue(resp.similarCases().isEmpty());

        verify(relatedRepository, never()).findSimilarCandidates(anyString(), anyString(), anyInt());

        // Firewall assertion on serialized JSON
        String json = new ObjectMapper().writeValueAsString(resp);
        assertFalse(json.contains("reasonStatus"));
        assertFalse(json.contains("reason_status"));
        assertFalse(json.toUpperCase().contains("ABSTAINED"));
    }

    @Test
    @DisplayName("(d) Similar Cases strips boilerplate, expands BNSS:482 <-> CRPC:438 crosswalk, applies domain boost & -35 overruled penalty")
    void similarCasesRankingAppliesCrosswalkDomainAndOverruledPenalty() {
        TargetContext target = new TargetContext(
                "auth-2024-bnss",
                "CRIMINAL",
                List.of("CONSTITUTION:136", "CRPC:482", "BNSS:528", "BNSS:482"),
                "[0.1,0.2]"
        );

        // Candidate 1: Higher raw cosine (0.85) in CIVIL_PROPERTY with ONLY boilerplate overlap (must NOT match CRPC:482/CONSTITUTION:136)
        // Score = 0.85*65 + 0 + 0 = 55.25
        SimilarCandidateRow candDrift = new SimilarCandidateRow(
                "cand-drift", "Civil SLP Case", 2021, "SC", "[2021] 1 SCR 10",
                2, false, 50, "GOOD_LAW", null, "Ratio A",
                "CIVIL_PROPERTY", List.of("CONSTITUTION:136", "CRPC:482"), 0.85
        );

        // Candidate 2: Lower raw cosine (0.80) in CRIMINAL with CRPC:438 (crosswalk twin of BNSS:482!)
        // Score = 0.80*65 (52.0) + 10.0 (domain) + 8.5 (1 shared tuple) = 70.50 -> ranks #1!
        SimilarCandidateRow candCrosswalk = new SimilarCandidateRow(
                "cand-bail-2020", "Criminal Bail Precedent", 2020, "SC", "[2020] 2 SCR 100",
                5, true, 120, "GOOD_LAW", null, "Ratio B",
                "CRIMINAL", List.of("CONSTITUTION:226", "CRPC:438"), 0.80
        );

        // Candidate 3: High cosine (0.90) + domain (10) + CRPC:438 (8.5), but POTENTIALLY_OVERRULED (-35.0 penalty)
        // Score = 0.90*65 (58.5) + 10.0 + 8.5 - 35.0 = 42.0 -> stays visible at #3 with overruledByAuthorityId preserved!
        SimilarCandidateRow candOverruled = new SimilarCandidateRow(
                "cand-overruled", "Overruled Bail Case", 1995, "SC", "[1995] 6 SCR 556",
                2, false, 90, "POTENTIALLY_OVERRULED", "corp-sushila-2020", "Ratio C",
                "CRIMINAL", List.of("CRPC:438"), 0.901234
        );

        when(relatedRepository.findTargetContext("auth-2024-bnss")).thenReturn(Optional.of(target));
        when(relatedRepository.countCitationSummary("auth-2024-bnss")).thenReturn(new CitationSummaryCounts(0, 0, 0));
        when(relatedRepository.findCitedBy("auth-2024-bnss", false, 0, 15)).thenReturn(List.of());
        when(relatedRepository.findReliesOn("auth-2024-bnss", false, 0, 15)).thenReturn(List.of());
        when(relatedRepository.findSimilarCandidates("auth-2024-bnss", "[0.1,0.2]", 80))
                .thenReturn(List.of(candDrift, candCrosswalk, candOverruled));

        RelatedJudgmentsResponse resp = service.getRelatedJudgments("auth-2024-bnss", 15);
        List<RelatedJudgmentItem> sims = resp.similarCases();

        assertEquals(3, sims.size());

        // #1: Criminal Bail Precedent (crosswalk BNSS:482 -> CRPC:438 + CRIMINAL domain boost)
        assertEquals("cand-bail-2020", sims.get(0).authorityId());
        assertEquals(List.of("CRPC:438"), sims.get(0).sharedTuples());
        assertEquals("SIMILAR", sims.get(0).treatmentType());
        assertEquals(0.8, sims.get(0).similarityScore());
        assertTrue(sims.get(0).isConstitutionBench());

        // #2: Civil SLP Case (no shared tuples because CONSTITUTION:136 & CRPC:482 stripped)
        assertEquals("cand-drift", sims.get(1).authorityId());
        assertTrue(sims.get(1).sharedTuples().isEmpty());

        // #3: POTENTIALLY_OVERRULED kept visible with -35 penalty and preserved overruledByAuthorityId
        assertEquals("cand-overruled", sims.get(2).authorityId());
        assertEquals("POTENTIALLY_OVERRULED", sims.get(2).goodLawStatus());
        assertEquals("corp-sushila-2020", sims.get(2).overruledByAuthorityId());
        assertEquals(0.9012, sims.get(2).similarityScore());
    }

    @Test
    @DisplayName("(e) Limit clamping [1..50], negative offset clamping (>=0), and exact hasMore pagination math")
    void limitOffsetClampingAndHasMoreMath() {
        TargetContext target = new TargetContext("maneka-1978", "CONSTITUTIONAL", List.of(), null);
        when(relatedRepository.findTargetContext("maneka-1978")).thenReturn(Optional.of(target));
        when(relatedRepository.countCitationSummary("maneka-1978")).thenReturn(new CitationSummaryCounts(302, 12, 5));

        // 0 -> clamped to 1
        when(relatedRepository.findCitedBy("maneka-1978", false, 0, 1)).thenReturn(List.of());
        when(relatedRepository.findReliesOn("maneka-1978", false, 0, 1)).thenReturn(List.of());
        service.getRelatedJudgments("maneka-1978", 0);
        verify(relatedRepository).findCitedBy("maneka-1978", false, 0, 1);

        // 200 -> clamped to 50, negative offset -5 -> clamped to 0
        RelatedJudgmentItem item = sampleCitationItem("c-1", "DISTINGUISHED", null, "Para 49", "verbatim quote");
        when(relatedRepository.countFilteredCitations("maneka-1978", true, true)).thenReturn(5);
        when(relatedRepository.findCitedBy("maneka-1978", true, 0, 50)).thenReturn(List.of(item));

        RelatedCitationPageResponse page1 = service.getRelatedCitationsPage("maneka-1978", "cited_by", "negative", -5, 200);
        assertEquals("CITED_BY", page1.direction());
        assertEquals("NEGATIVE", page1.treatment());
        assertEquals(0, page1.offset());
        assertEquals(50, page1.limit());
        assertEquals(5, page1.totalCount());
        assertTrue(page1.hasMore()); // (0 + 1) < 5
        assertNull(page1.items().get(0).substantiveReason());
        assertEquals("verbatim quote", page1.items().get(0).anchorQuote());

        // Exact end of list -> hasMore = false
        when(relatedRepository.findCitedBy("maneka-1978", true, 4, 1)).thenReturn(List.of(item));
        RelatedCitationPageResponse lastPage = service.getRelatedCitationsPage("maneka-1978", "CITED_BY", "NEGATIVE", 4, 1);
        assertFalse(lastPage.hasMore()); // (4 + 1) < 5 is false
    }

    private static RelatedJudgmentItem sampleCitationItem(
            String id,
            String treatmentType,
            String substantiveReason,
            String paraRef,
            String anchorQuote
    ) {
        return new RelatedJudgmentItem(
                id, "Sample Case", 2022, "SC", "[2022] 1 SCR 1",
                2, false, 10, "GOOD_LAW", null,
                treatmentType, "Sample snippet", substantiveReason, paraRef, anchorQuote,
                "Sample ratio", List.of(), null
        );
    }
}
