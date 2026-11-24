package com.app.refirm.inspector;

import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Step 5 Ring 1 Tests: Pari Materia Statute Crosswalk, Canonical Tuples & Taxonomy Resolver")
class PariMateriaCrosswalkServiceTest {

    private PariMateriaCrosswalkService crosswalkService;

    @BeforeEach
    void setUp() {
        crosswalkService = new PariMateriaCrosswalkService(new ObjectMapper());
        crosswalkService.init();
    }

    @Test
    @DisplayName("1. Act Aliases & Canonical Code Normalization")
    void testNormalizeActCode() {
        assertEquals("CRPC", crosswalkService.normalizeActCode("Cr.P.C."));
        assertEquals("CRPC", crosswalkService.normalizeActCode("Code of Criminal Procedure 1973"));
        assertEquals("BNSS", crosswalkService.normalizeActCode("Bharatiya Nagarik Suraksha Sanhita 2023"));
        assertEquals("IPC", crosswalkService.normalizeActCode("Indian Penal Code"));
        assertEquals("BNS", crosswalkService.normalizeActCode("Bharatiya Nyaya Sanhita"));
        assertEquals("IEA", crosswalkService.normalizeActCode("Indian Evidence Act 1872"));
        assertEquals("BSA", crosswalkService.normalizeActCode("Bharatiya Sakshya Adhiniyam"));
        assertEquals("NI_ACT", crosswalkService.normalizeActCode("Negotiable Instruments Act 1881"));
        assertEquals("IBC", crosswalkService.normalizeActCode("Insolvency and Bankruptcy Code"));
        assertEquals("LIMITATION_ACT", crosswalkService.normalizeActCode("Limitation Act 1963"));
    }

    @Test
    @DisplayName("2. Section Token Normalization")
    void testNormalizeSectionToken() {
        assertEquals("438", crosswalkService.normalizeSectionToken("Section 438"));
        assertEquals("439", crosswalkService.normalizeSectionToken("Sec. 439"));
        assertEquals("482", crosswalkService.normalizeSectionToken("S. 482"));
        assertEquals("318(4)", crosswalkService.normalizeSectionToken("Section 318 (4)"));
        assertEquals("65B", crosswalkService.normalizeSectionToken("Section 65-B"));
        assertEquals("63(4)", crosswalkService.normalizeSectionToken("Sec. 63 (4)"));
        assertEquals("138", crosswalkService.normalizeSectionToken("138"));
    }

    @Test
    @DisplayName("3. Canonical Tuple Construction")
    void testToCanonicalTuple() {
        assertEquals("CRPC:439", crosswalkService.toCanonicalTuple("Cr.P.C.", "Section 439"));
        assertEquals("BNSS:483", crosswalkService.toCanonicalTuple("BNSS", "Sec 483"));
        assertEquals("IPC:420", crosswalkService.toCanonicalTuple("Indian Penal Code", "420"));
        assertEquals("BNS:318(4)", crosswalkService.toCanonicalTuple("Bharatiya Nyaya Sanhita", "318 (4)"));
        assertEquals("NI_ACT:138", crosswalkService.toCanonicalTuple("N.I. Act", "Section 138"));
    }

    @Test
    @DisplayName("4. Twin-Tuple Query Expansion (BNSS -> CrPC & IPC -> BNS)")
    void testTwinTupleQueryExpansion() {
        // Query on modern regular bail under BNSS Section 483
        ExpandedTupleQuery expandedBail = crosswalkService.expandQueryTuples(List.of("BNSS:483"));
        assertTrue(expandedBail.primaryTuples().contains("BNSS:483"));
        assertTrue(expandedBail.allExpandedTuples().contains("BNSS:483"), "Must contain primary modern tuple");
        assertTrue(expandedBail.allExpandedTuples().contains("CRPC:439"), "Must expand to include legacy CrPC counterpart");

        // Query on modern cheating under BNS Section 318(4)
        ExpandedTupleQuery expandedCheating = crosswalkService.expandQueryTuples(List.of("BNS:318(4)"));
        assertTrue(expandedCheating.primaryTuples().contains("BNS:318(4)"));
        assertTrue(expandedCheating.allExpandedTuples().contains("BNS:318(4)"));
        assertTrue(expandedCheating.allExpandedTuples().contains("BNS:318"), "Must include base section fallback");
        assertTrue(expandedCheating.allExpandedTuples().contains("IPC:420"), "Must expand to legacy IPC 420 counterpart");

        // Query on Section 41A CrPC
        ExpandedTupleQuery expanded41A = crosswalkService.expandQueryTuples(List.of("CRPC:41A"));
        assertTrue(expanded41A.allExpandedTuples().contains("BNSS:35(3)"), "Must expand to BNSS 35(3)");
    }

    @Test
    @DisplayName("5. Crosswalk Badge Resolution")
    void testResolveCrosswalkBadge() {
        // Lawyer searches for modern BNSS 483 bail
        ExpandedTupleQuery query = crosswalkService.expandQueryTuples(List.of("BNSS:483"));

        // Match against a 2018 landmark judgment decided under CrPC Section 439
        Optional<String> badge = crosswalkService.resolveCrosswalkBadge(query, List.of("CRPC:439"));
        assertTrue(badge.isPresent(), "Badge must be generated for counterpart match");
        assertEquals("[Decided under Sec 439 CrPC — Pari Materia to Sec 483 BNSS]", badge.get());

        // When a judgment already has the queried modern section directly, no crosswalk badge is needed
        Optional<String> noBadge = crosswalkService.resolveCrosswalkBadge(query, List.of("BNSS:483"));
        assertTrue(noBadge.isEmpty(), "No badge needed when primary section matches directly");
    }

    @Test
    @DisplayName("6. Topic & Sub-Issue Taxonomy Classification")
    void testClassifyJudgmentTaxonomy() {
        // Regular bail with parity and prolonged custody
        TaxonomyClassification classification = crosswalkService.classifyJudgmentTaxonomy(
                List.of("CRPC:439"),
                "The appellant seeks regular bail on grounds of parity with co-accused who was granted bail, and prolonged incarceration of 3 years without trial progress."
        );

        assertTrue(classification.topicSlugs().contains("regular-bail"), "Must match regular-bail topic");
        assertTrue(classification.issueTags().contains("parity"), "Must identify parity issue tag");
        assertTrue(classification.issueTags().contains("prolonged-custody"), "Must identify prolonged-custody issue tag");

        // Cheque dishonour under NI Act
        TaxonomyClassification niClassification = crosswalkService.classifyJudgmentTaxonomy(
                List.of("NI_ACT:138", "NI_ACT:141"),
                "Criminal complaint under section 138 alleging non-executive director vicarious liability under section 141 without specific averments in charge of and responsible."
        );

        assertTrue(niClassification.topicSlugs().contains("cheque-dishonour-138"), "Must match cheque-dishonour topic");
        assertTrue(niClassification.issueTags().contains("director-vicarious-liability"), "Must identify director-vicarious-liability issue tag");
    }
}

