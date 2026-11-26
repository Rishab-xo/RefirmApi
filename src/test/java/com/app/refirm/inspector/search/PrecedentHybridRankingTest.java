package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import com.app.refirm.rules.model.LegalDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit test suite for Milestone 2B Final Polish:
 *  1. PrecedentQuerySanitizer (intent classification, formal citation detection, statutory stripping)
 *  2. Reciprocal Rank Fusion (k=20 smoothing, multi-channel weights)
 *  3. Legal Reranker Dimensions:
 *     - Exact Formal Citation routing without BM25
 *     - Rank-1 Channel Anchor Floor (42 pts)
 *     - Neutral domainPosturePts == 0.00
 *     - General recency cap at 6.00
 *     - Headnote Section Prominence bonus (+6.0 pts)
 *  4. Good-Law Guardrails (-35 pt penalty for POTENTIALLY_OVERRULED vs Known-Item direct bypass)
 *  5. Profile Differentiation (SEARCH_LIBRARY vs ARGUMENT_PREP)
 */
class PrecedentHybridRankingTest {

    private PrecedentQuerySanitizer sanitizer;
    private PrecedentRankingService rankingService;

    @BeforeEach
    void setUp() {
        sanitizer = new PrecedentQuerySanitizer();
        rankingService = new PrecedentRankingService();
    }

    // =========================================================================
    // 1. QUERY SANITIZER & INTENT CLASSIFICATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Sanitizer correctly classifies formal reporter citations as KNOWN_ITEM and sets isFormalCitation = true")
    void testSanitizerKnownItemFormalCitations() {
        SanitizedPrecedentQuery q1 = sanitizer.sanitize("(2014) 8 SCC 273", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q1.intent());
        assertTrue(q1.isKnownItemDirectMatch());
        assertTrue(q1.isFormalCitation());
        assertEquals("(2014) 8 SCC 273", q1.normalizedCitation());
        assertNull(q1.sanitizedLexicalQuery(), "Lexical query must be null to skip BM25 number collision");

        // Dot variant
        SanitizedPrecedentQuery q1Dot = sanitizer.sanitize("(2014) 8 S.C.C. 273", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q1Dot.intent());
        assertTrue(q1Dot.isFormalCitation());
        assertEquals("(2014) 8 SCC 273", q1Dot.normalizedCitation());
        assertNull(q1Dot.sanitizedLexicalQuery());

        SanitizedPrecedentQuery q2 = sanitizer.sanitize("2024 INSC 463", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q2.intent());
        assertTrue(q2.isFormalCitation());
        assertEquals("2024 INSC 463", q2.normalizedCitation());

        SanitizedPrecedentQuery q3 = sanitizer.sanitize("AIR 1976 SC 1207", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q3.intent());
        assertTrue(q3.isFormalCitation());
        assertEquals("AIR 1976 SC 1207", q3.normalizedCitation());

        SanitizedPrecedentQuery q4 = sanitizer.sanitize("[2014] 8 S.C.R. 128", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q4.intent());
        assertTrue(q4.isFormalCitation());
        assertEquals("[2014] 8 SCR 128", q4.normalizedCitation());
    }

    @Test
    @DisplayName("Sanitizer correctly classifies party-name matches as KNOWN_ITEM")
    void testSanitizerKnownItemPartyNames() {
        SanitizedPrecedentQuery q1 = sanitizer.sanitize("Arnesh Kumar v. State of Bihar", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q1.intent());
        assertFalse(q1.isFormalCitation());

        SanitizedPrecedentQuery q2 = sanitizer.sanitize("ADM Jabalpur vs. Shivkant Shukla", List.of(), List.of());
        assertEquals(QueryIntent.KNOWN_ITEM, q2.intent());

        SanitizedPrecedentQuery q3 = sanitizer.sanitize("Kesavananda Bharati", List.of(), List.of());
        assertEquals(QueryIntent.HYBRID_LEGAL, q3.intent(),
                "Ambiguous short queries without v./citation must stay HYBRID_LEGAL so Vector ANN stays active");
    }

    @Test
    @DisplayName("Sanitizer correctly classifies STATUTE_ONLY queries and strips tokens")
    void testSanitizerStatuteOnly() {
        List<String> spans = List.of("Section 438 CrPC");
        List<String> tuples = List.of("crpc:438");

        SanitizedPrecedentQuery q1 = sanitizer.sanitize("Section 438 CrPC", spans, tuples);
        assertEquals(QueryIntent.STATUTE_ONLY, q1.intent());
        assertNull(q1.sanitizedLexicalQuery(), "Lexical query must be null when only statute exists");

        SanitizedPrecedentQuery q2 = sanitizer.sanitize("Sec 138 NI Act", List.of("Sec 138 NI Act"), List.of("nia:138"));
        assertEquals(QueryIntent.STATUTE_ONLY, q2.intent());
        assertNull(q2.sanitizedLexicalQuery());
    }

    @Test
    @DisplayName("Sanitizer correctly classifies HYBRID_LEGAL queries and preserves non-statutory proposition text")
    void testSanitizerHybridLegal() {
        List<String> spans = List.of("Section 439 CrPC");
        List<String> tuples = List.of("crpc:439");

        SanitizedPrecedentQuery q = sanitizer.sanitize("bail cancellation under Section 439 CrPC for tampering with evidence", spans, tuples);
        assertEquals(QueryIntent.HYBRID_LEGAL, q.intent());
        assertNotNull(q.sanitizedLexicalQuery());
        assertTrue(q.sanitizedLexicalQuery().contains("bail cancellation"), "Must retain 'bail cancellation'");
        assertTrue(q.sanitizedLexicalQuery().contains("tampering"), "Must retain 'tampering'");
        assertFalse(q.sanitizedLexicalQuery().toLowerCase().contains("section 439"), "Must strip 'section 439'");
    }

    // =========================================================================
    // 2. RECIPROCAL RANK FUSION (RRF) TESTS
    // =========================================================================

    @Test
    @DisplayName("RRF k=20 multi-channel agreement outscores single channel rank")
    void testRrfAgreementOutscoresSingleChannel() {
        double k = 20.0;
        double wTrigram = 1.00;
        double wBm25 = 1.60;

        double rrfCandidateA = (wTrigram * (1.0 / (k + 1))) + (wBm25 * (1.0 / (k + 2)));
        double rrfCandidateB = (wBm25 * (1.0 / (k + 1)));

        assertTrue(rrfCandidateA > rrfCandidateB,
                "Candidate matching across multiple channels must achieve higher RRF score than single-channel match");
    }

    // =========================================================================
    // 3. EXACT CITATION ROUTING & RANK-1 CHANNEL ANCHOR FLOOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Exact formal citation (2014) 8 SCC 273 matches Arnesh Kumar at Rank #1 with anchor floor")
    void testExactFormalCitationRouting() {
        SanitizedPrecedentQuery query = sanitizer.sanitize("(2014) 8 SCC 273", List.of(), List.of());
        assertTrue(query.isFormalCitation());
        assertEquals("(2014) 8 SCC 273", query.normalizedCitation());

        PrecedentIndexRecord arneshKumar = createFullRecord("arnesh_kumar_2014", "Arnesh Kumar v. State of Bihar & Anr.",
                "[2014] 8 S.C.R. 128", List.of("(2014) 8 SCC 273", "2014 INSC 463"),
                List.of("crpc:41", "crpc:41a"), "GOOD_LAW", 10, 27, 2014, "BOTH_SIDES",
                "Mandated checklist under Section 41 CrPC.", List.of("Magistrates must scrutinize checklist."));

        // Precedent with primary citation match [2014] 8 S.C.R. 273 (Punjab Univ) - unrelated case
        PrecedentIndexRecord scrCollision = createFullRecord("corp-scr-collision", "Punjab University v. State",
                "[2014] 8 S.C.R. 273", List.of(),
                List.of(), "GOOD_LAW", 8, 2, 2014, "BOTH_SIDES",
                "Educational dispute.", List.of("University regulations."));

        CandidateRecallItem itemArnesh = new CandidateRecallItem(arneshKumar, 0.05, Map.of("TRIGRAM", 1));
        CandidateRecallItem itemCollision = new CandidateRecallItem(scrCollision, 0.02, Map.of("TRIGRAM", 2));

        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "(2014) 8 SCC 273", null, null, null, null, 10);
        List<RankedPrecedent> ranked = rankingService.rankCandidates(List.of(itemArnesh, itemCollision), query, criteria, RankingProfile.SEARCH_LIBRARY);

        assertEquals("arnesh_kumar_2014", ranked.get(0).record().authorityId(), "Arnesh Kumar must be Rank #1 on exact citation match");
        assertTrue(ranked.get(0).breakdown().relevancePts() >= 42.0, "Rank-1 Channel Anchor floor must guarantee >= 42 relevance points");
    }

    @Test
    @DisplayName("Neutral domainPosturePts is strictly 0.00 when domain and posture are null")
    void testNeutralDomainPosturePointsIsZero() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "dowry harassment arrest", "dowry harassment arrest", List.of(), QueryIntent.HYBRID_LEGAL);
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "dowry harassment arrest", null, null, null, null, 10);

        PrecedentIndexRecord rec = createSampleRecord("rec-1", "Test Case v. State",
                List.of(), "GOOD_LAW", 10, 10, 2020);

        CandidateRecallItem item = new CandidateRecallItem(rec, 0.05, Map.of("BM25", 1));
        List<RankedPrecedent> ranked = rankingService.rankCandidates(List.of(item), query, criteria, RankingProfile.SEARCH_LIBRARY);

        assertEquals(0.00, ranked.get(0).breakdown().domainPosturePts(), "When neither domain nor posture is specified, domainPosturePts must be 0.00");
    }

    @Test
    @DisplayName("Rank-1 Channel Anchor Floor + Recency cap 6.0 puts BM25 #1 landmark ahead of recent domain-overlap cases")
    void testRank1AnchorFloorAndRecencyCap() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "police arresting husband in dowry harassment without notice",
                "police arresting husband dowry harassment without notice",
                List.of(), QueryIntent.HYBRID_LEGAL);
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "dowry harassment arrest", null, null, null, null, 10);

        // Arnesh Kumar (2014): BM25 Rank 1, 27 citations, authority 10
        PrecedentIndexRecord arneshRec = createSampleRecord("arnesh_kumar_2014", "Arnesh Kumar v. State of Bihar",
                List.of("crpc:41"), "GOOD_LAW", 10, 27, 2014);
        CandidateRecallItem arneshItem = new CandidateRecallItem(arneshRec, 0.045, Map.of("BM25", 1, "VECTOR", 80));

        // Routine 2024 judgment: BM25 Rank 5, Vector Rank 3, 0 citations
        PrecedentIndexRecord recentRec = createSampleRecord("recent-2024", "State of Punjab v. Accused",
                List.of("crpc:41"), "GOOD_LAW", 8, 0, 2024);
        CandidateRecallItem recentItem = new CandidateRecallItem(recentRec, 0.048, Map.of("BM25", 5, "VECTOR", 3));

        List<RankedPrecedent> ranked = rankingService.rankCandidates(List.of(recentItem, arneshItem), query, criteria, RankingProfile.SEARCH_LIBRARY);

        assertEquals("arnesh_kumar_2014", ranked.get(0).record().authorityId(),
                "Arnesh Kumar with BM25 Rank 1 Anchor Floor (42 pts) and recency cap (6.0) must outrank routine 2024 runner-up");
        assertTrue(ranked.get(0).breakdown().relevancePts() >= 42.0);
        assertTrue(ranked.get(0).breakdown().recencyPts() <= 6.00, "General recency must be capped at 6.00");
    }

    @Test
    @DisplayName("Headnote section prominence boosts core Section 438 landmark above incidental mention")
    void testHeadnoteSectionProminenceBoost() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "anticipatory bail Section 438 CrPC", "anticipatory bail", List.of("crpc:438", "bnss:482"), QueryIntent.HYBRID_LEGAL);
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of("crpc:438", "bnss:482"), "anticipatory bail", null, null, null, null, 10);

        // Sushila Aggarwal (2020): Constitution Bench 5J, mentions 438 in headnote propositions
        PrecedentIndexRecord sushilaRec = createFullRecord("sushila-agg", "Sushila Aggarwal v. State (NCT of Delhi)",
                "[2020] 2 S.C.R. 1", List.of("(2020) 5 SCC 1"),
                List.of("crpc:438", "crpc:437"), "GOOD_LAW", 15, 11, 2020, "BOTH_SIDES",
                "Protection granted u/s.438 should not invariably be limited to a fixed period.",
                List.of("Section 438 CrPC does not contain any restrictive words.", "Anticipatory bail inures till conclusion of trial."));

        // Incidental case: cites 438 along with 20 other sections, but proposition is about sentencing under IPC
        List<String> incidentalTuples = new ArrayList<>(List.of("crpc:438"));
        for (int i = 1; i <= 20; i++) incidentalTuples.add("ipc:" + (300 + i));
        PrecedentIndexRecord incidentalRec = createFullRecord("incidental-case", "Rattiram v. State of M.P.",
                "[2012] 4 S.C.R. 500", List.of(),
                incidentalTuples, "GOOD_LAW", 10, 50, 2012, "BOTH_SIDES",
                "Fair trial is the heart of criminal jurisprudence under Article 21.",
                List.of("Sentence must be proportionate to the gravity of offence."));

        CandidateRecallItem itemSushila = new CandidateRecallItem(sushilaRec, 0.05, Map.of("TUPLE", 1, "BM25", 2));
        CandidateRecallItem itemIncidental = new CandidateRecallItem(incidentalRec, 0.05, Map.of("TUPLE", 1, "BM25", 2));

        List<RankedPrecedent> ranked = rankingService.rankCandidates(List.of(itemIncidental, itemSushila), query, criteria, RankingProfile.SEARCH_LIBRARY);

        RankedPrecedent resSushila = ranked.stream().filter(r -> r.record().authorityId().equals("sushila-agg")).findFirst().orElseThrow();
        RankedPrecedent resIncidental = ranked.stream().filter(r -> r.record().authorityId().equals("incidental-case")).findFirst().orElseThrow();

        assertTrue(resSushila.breakdown().statutePts() > resIncidental.breakdown().statutePts() + 4.0,
                "Sushila Aggarwal must receive +6.0 headnote prominence bonus, beating incidental mention");
        assertEquals("sushila-agg", ranked.get(0).record().authorityId());
    }

    // =========================================================================
    // 4. STATUTORY SPECIFICITY & OMNIBUS PENALTY TESTS
    // =========================================================================

    @Test
    @DisplayName("Reranker applies omnibus penalty to judgments reciting 40+ unrelated provisions")
    void testOmnibusDampenerPenalty() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "anticipatory bail", "anticipatory bail", List.of("crpc:438", "crpc:439"), QueryIntent.HYBRID_LEGAL);
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of("crpc:438", "crpc:439"), "anticipatory bail", null, null, null, null, 10);

        // Targeted precedent: cites exactly the 2 query provisions
        PrecedentIndexRecord targetedRec = createSampleRecord("rec-targeted", "Targeted v. State",
                List.of("crpc:438", "crpc:439"), "GOOD_LAW", 10, 50, 2022);

        // Omnibus precedent: cites the 2 query provisions PLUS 40 unrelated provisions
        List<String> omnibusTuples = new ArrayList<>(List.of("crpc:438", "crpc:439"));
        for (int i = 1; i <= 40; i++) {
            omnibusTuples.add("ipc:" + (100 + i));
        }
        PrecedentIndexRecord omnibusRec = createSampleRecord("rec-omnibus", "Omnibus Dump v. State",
                omnibusTuples, "GOOD_LAW", 10, 50, 2022);

        CandidateRecallItem itemTargeted = new CandidateRecallItem(targetedRec, 0.05, Map.of("TUPLE", 1));
        CandidateRecallItem itemOmnibus = new CandidateRecallItem(omnibusRec, 0.05, Map.of("TUPLE", 1));

        List<RankedPrecedent> ranked = rankingService.rankCandidates(
                List.of(itemTargeted, itemOmnibus), query, criteria, RankingProfile.SEARCH_LIBRARY);

        RankedPrecedent resTargeted = ranked.stream().filter(r -> r.record().authorityId().equals("rec-targeted")).findFirst().orElseThrow();
        RankedPrecedent resOmnibus = ranked.stream().filter(r -> r.record().authorityId().equals("rec-omnibus")).findFirst().orElseThrow();

        assertTrue(resTargeted.breakdown().statutePts() > resOmnibus.breakdown().statutePts() * 1.5,
                "Targeted precedent must receive substantially higher statutory specificity points than 40-tuple omnibus judgment");
    }

    // =========================================================================
    // 5. GOOD-LAW GUARDRAIL & BYPASS TESTS
    // =========================================================================

    @Test
    @DisplayName("Reranker applies -35 pt penalty to POTENTIALLY_OVERRULED in general search")
    void testGoodLawPenaltyAppliedInGeneralSearch() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "habeas corpus emergency detention", "habeas corpus emergency detention", List.of(), QueryIntent.HYBRID_LEGAL);
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "habeas corpus emergency detention", null, null, null, null, 10);

        PrecedentIndexRecord goodRec = createSampleRecord("good-law", "Puttaswamy v. Union of India",
                List.of("const:21"), "GOOD_LAW", 15, 200, 2017);
        PrecedentIndexRecord overruledRec = createSampleRecord("overruled-law", "ADM Jabalpur v. Shivkant Shukla",
                List.of("const:21"), "POTENTIALLY_OVERRULED", 15, 200, 1976);

        CandidateRecallItem itemGood = new CandidateRecallItem(goodRec, 0.04, Map.of("BM25", 1));
        CandidateRecallItem itemOverruled = new CandidateRecallItem(overruledRec, 0.04, Map.of("BM25", 1));

        List<RankedPrecedent> ranked = rankingService.rankCandidates(
                List.of(itemGood, itemOverruled), query, criteria, RankingProfile.SEARCH_LIBRARY);

        RankedPrecedent resOverruled = ranked.stream().filter(r -> r.record().authorityId().equals("overruled-law")).findFirst().orElseThrow();

        assertEquals(-35.0, resOverruled.breakdown().goodLawPenalty(), "POTENTIALLY_OVERRULED must receive -35.0 penalty in general search");
        assertTrue(resOverruled.finalScore() < 65.0, "Final score must be heavily dampened by -35 penalty");
    }

    @Test
    @DisplayName("Reranker BYPASSES good-law penalty when user directly searches the overruled case by name")
    void testGoodLawPenaltyBypassedOnDirectKnownItemSearch() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "ADM Jabalpur", "ADM Jabalpur", List.of(), QueryIntent.KNOWN_ITEM, true);
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "ADM Jabalpur", null, null, null, null, 10);

        PrecedentIndexRecord admJabalpur = createSampleRecord("overruled-law", "ADM Jabalpur v. Shivkant Shukla",
                List.of("const:21"), "POTENTIALLY_OVERRULED", 15, 200, 1976);

        CandidateRecallItem item = new CandidateRecallItem(admJabalpur, 0.05, Map.of("TRIGRAM", 1));

        List<RankedPrecedent> ranked = rankingService.rankCandidates(
                List.of(item), query, criteria, RankingProfile.SEARCH_LIBRARY);

        assertEquals(1, ranked.size());
        RankedPrecedent res = ranked.get(0);

        assertEquals(0.0, res.breakdown().goodLawPenalty(),
                "Penalty must be 0.0 when directly searching an overruled precedent as KNOWN_ITEM");
        assertTrue(res.breakdown().relevancePts() >= 40.0, "Known-item floor boost must guarantee at least 40 relevance pts");
        assertTrue(res.finalScore() >= 60.0, "Direct search for historical landmark should score highly to remain discoverable");
    }

    // =========================================================================
    // 6. PROFILE DIFFERENTIATION (SEARCH_LIBRARY vs ARGUMENT_PREP)
    // =========================================================================

    @Test
    @DisplayName("ARGUMENT_PREP profile gives higher score to matching petitioner posture")
    void testProfileDifferentiation() {
        SanitizedPrecedentQuery query = new SanitizedPrecedentQuery(
                "quashing 482", "quashing 482", List.of("crpc:482"), QueryIntent.HYBRID_LEGAL);

        // Precedent supporting petitioner
        PrecedentIndexRecord petRec = createFullRecord("rec-pet", "Petitioner Case",
                "2021 SCC 1", List.of(), List.of("crpc:482"), "GOOD_LAW", 10, 50, 2021, "PETITIONER",
                "Ratio", List.of("Proposition 1"));

        // Precedent supporting respondent
        PrecedentIndexRecord respRec = createFullRecord("rec-resp", "Respondent Case",
                "2021 SCC 2", List.of(), List.of("crpc:482"), "GOOD_LAW", 10, 50, 2021, "RESPONDENT",
                "Ratio", List.of("Proposition 1"));

        CandidateRecallItem itemPet = new CandidateRecallItem(petRec, 0.05, Map.of("TUPLE", 1));
        CandidateRecallItem itemResp = new CandidateRecallItem(respRec, 0.05, Map.of("TUPLE", 1));

        // Argument prep criteria explicitly preferring PETITIONER posture
        PrecedentSearchCriteria criteriaPrep = new PrecedentSearchCriteria(
                List.of("crpc:482"), "quashing 482", "CRIMINAL", null, null, null, 10, "PETITIONER");

        List<RankedPrecedent> rankedPrep = rankingService.rankCandidates(
                List.of(itemPet, itemResp), query, criteriaPrep, RankingProfile.ARGUMENT_PREP);

        RankedPrecedent resPet = rankedPrep.stream().filter(r -> r.record().authorityId().equals("rec-pet")).findFirst().orElseThrow();
        RankedPrecedent resResp = rankedPrep.stream().filter(r -> r.record().authorityId().equals("rec-resp")).findFirst().orElseThrow();

        assertTrue(resPet.finalScore() > resResp.finalScore(),
                "In ARGUMENT_PREP with PETITIONER preferred posture, matching precedent must rank higher than contrary precedent");
    }

    // =========================================================================
    // 7. UNIVERSAL LAYER-1 SEARCH & RANKING IMPROVEMENTS (JLIB-SRCH-001/002/003)
    // =========================================================================

    @Test
    @DisplayName("Canonical whitespace collapse produces identical SanitizedPrecedentQuery and Embedding LRU cache hit")
    void testCanonicalWhitespaceCollapseAndEmbeddingCacheKey() throws Exception {
        SanitizedPrecedentQuery single = sanitizer.sanitize("state of haryiana bhajan lal", List.of(), List.of());
        SanitizedPrecedentQuery noisy = sanitizer.sanitize("  state   of   haryiana   bhajan   lal  ", List.of(), List.of());

        assertEquals(single, noisy);
        assertEquals("state of haryiana bhajan lal", noisy.rawQuery());
        assertEquals("state of haryiana bhajan lal", noisy.sanitizedLexicalQuery());

        EmbeddingModel mockModel = Mockito.mock(EmbeddingModel.class);
        float[] vec = new float[512];
        vec[0] = 0.5f;
        EmbeddingResponse fakeResp = new EmbeddingResponse(List.of(new Embedding(vec, 0)));
        Mockito.when(mockModel.call(Mockito.any(EmbeddingRequest.class))).thenReturn(fakeResp);

        PrecedentQueryEmbeddingService embService = new PrecedentQueryEmbeddingService(mockModel);
        String lit1 = embService.embedQueryAsync("state of haryiana bhajan lal").get();
        String lit2 = embService.embedQueryAsync("  state   of   haryiana    bhajan   lal ").get();

        assertNotNull(lit1);
        assertEquals(lit1, lit2);
        Mockito.verify(mockModel, Mockito.times(1)).call(Mockito.any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("'basic structure' routes to HYBRID_LEGAL while 'A.K. Gopalan' triggers direct match floor & overruled bypass")
    void testShortDoctrineRoutingVsDecoupledDirectKnownItemBypass() {
        SanitizedPrecedentQuery doctrineQuery = sanitizer.sanitize("basic structure", List.of(), List.of());
        assertEquals(QueryIntent.HYBRID_LEGAL, doctrineQuery.intent());

        SanitizedPrecedentQuery gopalanQuery = sanitizer.sanitize("A.K. Gopalan", List.of(), List.of());
        assertEquals(QueryIntent.HYBRID_LEGAL, gopalanQuery.intent());

        PrecedentIndexRecord gopalanRec = createSampleRecord(
                "ak-gopalan", "A.K. Gopalan v. State of Madras",
                List.of("CONSTITUTION:21"), "POTENTIALLY_OVERRULED", 15, 180, 1950);
        CandidateRecallItem item = new CandidateRecallItem(gopalanRec, 0.04, Map.of("BM25", 1));
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "A.K. Gopalan", null, null, null, null, 10);

        List<RankedPrecedent> ranked = rankingService.rankCandidates(List.of(item), gopalanQuery, criteria, RankingProfile.SEARCH_LIBRARY);
        assertEquals(0.0, ranked.get(0).breakdown().goodLawPenalty(), "Decoupled direct match must bypass overruled penalty");
        assertTrue(ranked.get(0).breakdown().relevancePts() >= 42.0, "Decoupled direct match must enforce 42.0 floor");
    }

    @Test
    @DisplayName("Concept-group coverage gives 1.0 to legacy CRPC:482 and targetsModernEnactment uses rawTuples only")
    void testConceptGroupCoverageAndUserIntentRecencyGate() {
        PariMateriaCrosswalkService crosswalk = new PariMateriaCrosswalkService(new ObjectMapper());
        crosswalk.init();

        // User queries CrPC 482 -> expands to [CRPC:482, BNSS:528]
        PariMateriaCrosswalkService.ExpandedTupleQuery crpcExpanded = crosswalk.expandQueryTuples(List.of("CRPC:482"));
        SanitizedPrecedentQuery crpcQuery = sanitizer.sanitize("section 482 crpc quashing fir", List.of("section 482 crpc"), crpcExpanded);
        assertEquals(List.of("CRPC:482"), crpcQuery.rawTuples());
        assertEquals(1, crpcQuery.conceptGroups().size());
        assertTrue(crpcQuery.conceptGroups().get(0).contains("BNSS:528"));

        // Legacy landmark (2017) with CRPC:482 only vs 2025 transitional case with CRPC:482 + BNSS:528
        PrecedentIndexRecord legacyRec = createFullRecord(
                "parbatbhai", "Parbatbhai Aahir v. State of Gujarat", "[2017] 10 S.C.R. 12", List.of(),
                List.of("CRPC:482"), "GOOD_LAW", 12, 9, 2017, "BOTH_SIDES",
                "Principles for quashing u/s Section482 CrPC.", List.of("Quashing under Section482."));
        PrecedentIndexRecord transitional2025 = createFullRecord(
                "transitional-2025", "Recent Transitional Case v. State", "[2025] 1 S.C.R. 100", List.of(),
                List.of("CRPC:482", "BNSS:528"), "GOOD_LAW", 10, 0, 2025, "BOTH_SIDES",
                "Section482 CrPC and 528 BNSS discussed.", List.of("Procedural discussion."));

        PrecedentSearchCriteria crpcCriteria = new PrecedentSearchCriteria(crpcExpanded.allExpandedTuples(), "quashing fir", null, null, null, null, 10);
        List<RankedPrecedent> rankedCrpc = rankingService.rankCandidates(
                List.of(
                        new CandidateRecallItem(legacyRec, 0.08, Map.of("BM25", 10, "TUPLE", 5, "VECTOR", 10)),
                        new CandidateRecallItem(transitional2025, 0.08, Map.of("BM25", 10, "TUPLE", 5, "VECTOR", 10))
                ),
                crpcQuery, crpcCriteria, RankingProfile.SEARCH_LIBRARY
        );

        RankedPrecedent resLegacy = rankedCrpc.stream().filter(r -> r.record().authorityId().equals("parbatbhai")).findFirst().orElseThrow();
        RankedPrecedent res2025 = rankedCrpc.stream().filter(r -> r.record().authorityId().equals("transitional-2025")).findFirst().orElseThrow();

        assertEquals(resLegacy.breakdown().statutePts(), res2025.breakdown().statutePts(), 0.01,
                "Legacy CRPC:482 landmark must get equal 1.0 concept-group coverage as transitional CRPC:482+BNSS:528 case");
        assertTrue(res2025.breakdown().recencyPts() <= 6.0,
                "User typed crpc, so modern-act BNSS expansion must NOT raise maxRecency above 6.0");

        // Now user explicitly searches BNSS 528 -> maxRecency MUST unlock up to 10.0
        PariMateriaCrosswalkService.ExpandedTupleQuery bnssExpanded = crosswalk.expandQueryTuples(List.of("BNSS:528"));
        SanitizedPrecedentQuery bnssQuery = sanitizer.sanitize("section 528 bnss quashing fir", List.of("section 528 bnss"), bnssExpanded);
        List<RankedPrecedent> rankedBnss = rankingService.rankCandidates(
                List.of(new CandidateRecallItem(transitional2025, 0.08, Map.of("TUPLE", 1))),
                bnssQuery,
                new PrecedentSearchCriteria(bnssExpanded.allExpandedTuples(), "quashing fir", null, null, null, null, 10),
                RankingProfile.SEARCH_LIBRARY
        );
        assertTrue(rankedBnss.get(0).breakdown().recencyPts() > 6.0,
                "Explicit bnss query must unlock 10.0 modern-act recency cap");
    }

    @Test
    @DisplayName("Ratio-to-max RRF handles browse maxRrf=0 safely, prevents VECTOR:1/statutory BM25:1 hijack, and applies +6.0 tuple-free BM25 bonus")
    void testRatioToMaxRrfNoHijackAndBm25Bonus() {
        // 1. Browse mode (maxRrf = 0) -> relevancePts == 0.0 (no NaN)
        SanitizedPrecedentQuery browseQuery = new SanitizedPrecedentQuery("", null, List.of(), QueryIntent.HYBRID_LEGAL);
        PrecedentIndexRecord sample = createSampleRecord("b-1", "Some Precedent", List.of(), "GOOD_LAW", 10, 5, 2020);
        List<RankedPrecedent> browseRanked = rankingService.rankCandidates(
                List.of(new CandidateRecallItem(sample, 0.0, Map.of())),
                browseQuery,
                PrecedentSearchCriteria.ofTopic("bail", null, 10),
                RankingProfile.SEARCH_LIBRARY
        );
        assertEquals(0.0, browseRanked.get(0).breakdown().relevancePts());

        // 2. Statutory query (rawTuples non-empty): lone VECTOR:1 (low rrf) & BM25:1 get NO 42.0 floor and NO +6.0 bonus
        SanitizedPrecedentQuery statQuery = new SanitizedPrecedentQuery(
                "section 482 crpc quashing fir",
                "quashing fir",
                List.of("CRPC:482", "BNSS:528"),
                QueryIntent.HYBRID_LEGAL,
                false, false, null,
                List.of("CRPC:482"),
                List.of(Set.of("CRPC:482", "BNSS:528"))
        );
        PrecedentIndexRecord punjab = createSampleRecord("punjab-2011", "Punjab State Warehousing v. Durga Ji", List.of("CRPC:482"), "GOOD_LAW", 10, 0, 2011);
        PrecedentIndexRecord parbatbhai = createSampleRecord("parbatbhai-2017", "Parbatbhai Aahir v. State of Gujarat", List.of("CRPC:482"), "GOOD_LAW", 12, 9, 2017);

        List<RankedPrecedent> statRanked = rankingService.rankCandidates(
                List.of(
                        new CandidateRecallItem(punjab, 0.040, Map.of("VECTOR", 1)),
                        new CandidateRecallItem(parbatbhai, 0.081, Map.of("BM25", 30, "TUPLE", 32, "VECTOR", 12))
                ),
                statQuery,
                new PrecedentSearchCriteria(List.of("CRPC:482", "BNSS:528"), "quashing fir", null, null, null, null, 10),
                RankingProfile.SEARCH_LIBRARY
        );
        assertEquals("parbatbhai-2017", statRanked.get(0).record().authorityId(), "3-channel landmark must beat lone VECTOR:1 with lower RRF");
        RankedPrecedent punjabRes = statRanked.stream().filter(r -> r.record().authorityId().equals("punjab-2011")).findFirst().orElseThrow();
        assertTrue(punjabRes.breakdown().relevancePts() < 25.0, "Lone VECTOR:1 must stay proportional (~22.2), never floored to 42.0");

        // 3. Tuple-free fact-pattern query: Arnesh (BM25:1) gets +6.0 bonus and beats Kulwant (BM25:5 + Vector:3)
        SanitizedPrecedentQuery factQuery = sanitizer.sanitize(
                "police arresting husband in dowry harassment case without notice punishment under 7 years", List.of(), List.of());
        PrecedentIndexRecord arnesh = createSampleRecord("arnesh", "Arnesh Kumar v. State of Bihar", List.of("CRPC:41"), "GOOD_LAW", 10, 27, 2014);
        PrecedentIndexRecord kulwant = createSampleRecord("kulwant", "Kulwant Singh v. State of Punjab", List.of("IPC:304B"), "GOOD_LAW", 8, 0, 2013);
        List<RankedPrecedent> factRanked = rankingService.rankCandidates(
                List.of(
                        new CandidateRecallItem(arnesh, 0.0762, Map.of("BM25", 1)),
                        new CandidateRecallItem(kulwant, 0.1010, Map.of("BM25", 5, "VECTOR", 3))
                ),
                factQuery,
                new PrecedentSearchCriteria(List.of(), factQuery.sanitizedLexicalQuery(), null, null, null, null, 10),
                RankingProfile.SEARCH_LIBRARY
        );
        assertEquals("arnesh", factRanked.get(0).record().authorityId(), "BM25 #1 +6.0 bonus must keep Arnesh #1 on fact-pattern query");
    }

    @Test
    @DisplayName("Space-tolerant headnote regex matches glued 'Section482' and distinctive-token extraction enforces guards")
    void testGluedHeadnoteRegexAndDistinctiveTokenGuards() {
        SanitizedPrecedentQuery q = sanitizer.sanitize("section 482 crpc quashing fir", List.of("section 482 crpc"), List.of("CRPC:482"));
        List<String> sharedTuples = List.of("CRPC:482", "IPC:406", "IPC:420", "IPC:468", "IPC:471", "CONSTITUTION:21");
        PrecedentIndexRecord gluedRec = createFullRecord(
                "neeharika-glued", "Neeharika Infrastructure v. State of Maharashtra", "[2021] 1 S.C.R. 1", List.of(),
                sharedTuples, "GOOD_LAW", 12, 8, 2021, "BOTH_SIDES",
                "Interim stay pending quashing petition under Section482 Cr.P.C.",
                List.of("High Court must exercise restraint."));
        PrecedentIndexRecord noHeadnoteRec = createFullRecord(
                "other-rec", "Other Case v. State", "[2021] 1 S.C.R. 2", List.of(),
                sharedTuples, "GOOD_LAW", 12, 8, 2021, "BOTH_SIDES",
                "Inherent powers exercised in criminal revision.",
                List.of("Facts did not justify stay."));

        List<RankedPrecedent> ranked = rankingService.rankCandidates(
                List.of(
                        new CandidateRecallItem(noHeadnoteRec, 0.05, Map.of("TUPLE", 1)),
                        new CandidateRecallItem(gluedRec, 0.05, Map.of("TUPLE", 1))
                ),
                q,
                new PrecedentSearchCriteria(List.of("CRPC:482"), "quashing fir", null, null, null, null, 10),
                RankingProfile.SEARCH_LIBRARY
        );
        assertEquals("neeharika-glued", ranked.get(0).record().authorityId(), "Glued 'Section482' must earn headnote +6.0 prominence");
        assertTrue(ranked.get(0).breakdown().statutePts() > ranked.get(1).breakdown().statutePts());

        // Distinctive token extraction guards
        SanitizedPrecedentQuery insuranceQuery = sanitizer.sanitize("life insurance state nominee policy", List.of(), List.of());
        List<String> insuranceTokens = PrecedentQuerySanitizer.extractDistinctiveTitleTokens(insuranceQuery);
        assertFalse(insuranceTokens.contains("life"), "< 5 chars skipped");
        assertFalse(insuranceTokens.contains("insurance"), "institutional noun skipped");
        assertFalse(insuranceTokens.contains("state"), "institutional noun skipped");
        assertTrue(insuranceTokens.contains("nominee"));
        assertTrue(insuranceTokens.contains("policy"));

        // Long query (>7 words) returns empty distinctive tokens
        SanitizedPrecedentQuery longQuery = sanitizer.sanitize(
                "can a nominee in life insurance policy claim money over legal heirs", List.of(), List.of());
        assertTrue(PrecedentQuerySanitizer.extractDistinctiveTitleTokens(longQuery).isEmpty(),
                "Queries > 7 words must skip per-token title recall");

        // Formal citation returns empty distinctive tokens
        SanitizedPrecedentQuery formal = sanitizer.sanitize("(2014) 8 SCC 273", List.of(), List.of());
        assertTrue(PrecedentQuerySanitizer.extractDistinctiveTitleTokens(formal).isEmpty());
    }

    @Test
    @DisplayName("Word-boundary guard in isDirectKnownItemMatch matches 'A.K. Gopalan' without leaking to 'Rajagopalan'")
    void testDirectKnownItemWordBoundaryExcludesSubstringCollisions() {
        SanitizedPrecedentQuery gopalanQuery = sanitizer.sanitize("A.K. Gopalan", List.of(), List.of());
        PrecedentSearchCriteria criteria = new PrecedentSearchCriteria(List.of(), "A.K. Gopalan", null, null, null, null, 10);

        PrecedentIndexRecord gopalanRec = createSampleRecord(
                "ak-gopalan-1950", "A.K. GOPALAN versus THE STATE OF MADRAS.",
                List.of("CONSTITUTION:21"), "POTENTIALLY_OVERRULED", 15, 130, 1950);
        PrecedentIndexRecord rajagopalanRec = createSampleRecord(
                "rajagopalan-overruled", "P.S. RAJAGOPALAN versus STATE",
                List.of("CONSTITUTION:14"), "POTENTIALLY_OVERRULED", 12, 14, 1963);
        PrecedentIndexRecord rajagopalanGoodRec = createSampleRecord(
                "rajagopalan-good", "M.K. RAJAGOPALAN versus DR. PERIASAMY PALANI GOUNDER",
                List.of("IBC:31"), "GOOD_LAW", 10, 1, 2023);

        List<RankedPrecedent> ranked = rankingService.rankCandidates(
                List.of(
                        new CandidateRecallItem(gopalanRec, 0.02, Map.of("TRIGRAM", 2)),
                        new CandidateRecallItem(rajagopalanRec, 0.08, Map.of("TRIGRAM", 1)),
                        new CandidateRecallItem(rajagopalanGoodRec, 0.03, Map.of("VECTOR", 8))
                ),
                gopalanQuery, criteria, RankingProfile.SEARCH_LIBRARY
        );

        RankedPrecedent resGopalan = ranked.stream().filter(r -> r.record().authorityId().equals("ak-gopalan-1950")).findFirst().orElseThrow();
        RankedPrecedent resRajaOverruled = ranked.stream().filter(r -> r.record().authorityId().equals("rajagopalan-overruled")).findFirst().orElseThrow();
        RankedPrecedent resRajaGood = ranked.stream().filter(r -> r.record().authorityId().equals("rajagopalan-good")).findFirst().orElseThrow();

        assertEquals(42.0, resGopalan.breakdown().relevancePts(), 0.01, "True A.K. Gopalan match must receive 42.0 floor");
        assertEquals(0.0, resGopalan.breakdown().goodLawPenalty(), "True A.K. Gopalan match must bypass -35.0 penalty");
        assertEquals(-35.0, resRajaOverruled.breakdown().goodLawPenalty(), "Rajagopalan must NOT bypass -35.0 overruled penalty");
        assertTrue(resRajaGood.breakdown().relevancePts() < 25.0, "Rajagopalan must NOT receive 42.0 direct-match floor");
        assertEquals("ak-gopalan-1950", ranked.get(0).record().authorityId());
    }

    // =========================================================================
    // HELPER BUILDERS
    // =========================================================================

    private PrecedentIndexRecord createSampleRecord(
            String authorityId,
            String caseTitle,
            List<String> tuples,
            String goodLawStatus,
            int courtTierWeight,
            int citedByCount,
            int decisionYear
    ) {
        return createFullRecord(authorityId, caseTitle, decisionYear + " INSC 101", List.of(), tuples,
                goodLawStatus, courtTierWeight, citedByCount, decisionYear, "BOTH_SIDES", "Sample ratio.", List.of("Proposition 1"));
    }

    private PrecedentIndexRecord createFullRecord(
            String authorityId,
            String caseTitle,
            String primaryCitation,
            List<String> equivalentCitations,
            List<String> tuples,
            String goodLawStatus,
            int courtTierWeight,
            int citedByCount,
            int decisionYear,
            String supportingPosture,
            String twoLineRatio,
            List<String> legalPropositions
    ) {
        return new PrecedentIndexRecord(
                authorityId,
                "dedup:" + authorityId,
                "doc-1",
                "shard-1",
                "IRAC_ENRICHED",
                caseTitle,
                courtTierWeight >= 10 ? "SC" : "HC",
                courtTierWeight >= 10 ? "Supreme Court of India" : "High Court of Delhi",
                courtTierWeight,
                decisionYear + "-05-15",
                decisionYear,
                primaryCitation,
                equivalentCitations,
                LegalDomain.CRIMINAL,
                List.of(),
                tuples,
                List.of(),
                List.of(),
                supportingPosture,
                "ALLOWED",
                twoLineRatio,
                legalPropositions,
                List.of("keyword"),
                null,
                true,
                "r2-key",
                "https://r2/url",
                "sc-v1.0",
                citedByCount,
                courtTierWeight >= 15 ? 5 : 2,
                citedByCount / 2,
                goodLawStatus,
                null
        );
    }
}
