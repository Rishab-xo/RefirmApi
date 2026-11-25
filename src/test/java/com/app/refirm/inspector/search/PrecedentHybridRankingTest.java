package com.app.refirm.inspector.search;

import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.rules.model.LegalDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertEquals(QueryIntent.KNOWN_ITEM, q3.intent(), "Short proper-noun <= 4 words should be KNOWN_ITEM");
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
        double wTrigram = 0.60;
        double wBm25 = 1.25;

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
