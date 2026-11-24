package com.app.refirm.inspector;

import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import com.app.refirm.inspector.service.PrecedentCatalogSeederConfig;
import com.app.refirm.rules.model.LegalDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrecedentIndexRepositoryAndSeederTest {

    private PariMateriaCrosswalkService crosswalkService;
    private CorpusPrecedentRegistryService corpusRegistry;
    private PrecedentCatalogSeederConfig seederConfig;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        crosswalkService = new PariMateriaCrosswalkService(objectMapper);
        crosswalkService.init();

        corpusRegistry = new CorpusPrecedentRegistryService(objectMapper);
        corpusRegistry.init();

        // null repository for unit-testing the transformation logic without needing a live PG container
        seederConfig = new PrecedentCatalogSeederConfig(corpusRegistry, null, crosswalkService);
    }

    @Test
    @DisplayName("PrecedentCatalogSeederConfig transforms seed precedents into rich PrecedentIndexRecords")
    void shouldTransformSeedPrecedentsAccurately() {
        List<CorpusPrecedentEntry> entries = corpusRegistry.getAllPrecedents();
        assertFalse(entries.isEmpty(), "Catalog must contain at least 26 seed precedents");

        for (CorpusPrecedentEntry entry : entries) {
            PrecedentIndexRecord record = seederConfig.buildIndexRecordFromSeed(entry);

            assertNotNull(record.authorityId());
            assertFalse(record.authorityId().isBlank());
            assertNotNull(record.canonicalDedupKey());
            assertTrue(record.canonicalDedupKey().contains(":"));
            assertEquals("IRAC_ENRICHED", record.enrichmentStatus());
            assertNotNull(record.caseTitle());
            assertNotNull(record.courtCode());
            assertTrue(record.courtTierWeight() >= 5 && record.courtTierWeight() <= 15);
            assertNotNull(record.actSectionTuples());
            assertNotNull(record.topicSlugs());
            assertNotNull(record.issueTags());
            assertNotNull(record.supportingPosture());
        }
    }

    @Test
    @DisplayName("PrecedentCatalogSeederConfig correctly extracts statutory tuples from landmark authorities")
    void shouldExtractExpectedTuplesFromLandmarks() {
        // Satender Kumar Antil
        var antilOpt = corpusRegistry.findById("satender_kumar_antil_2022");
        assertTrue(antilOpt.isPresent());
        PrecedentIndexRecord antilRecord = seederConfig.buildIndexRecordFromSeed(antilOpt.get());
        assertTrue(antilRecord.actSectionTuples().contains("CRPC:439")
                        || antilRecord.actSectionTuples().contains("CRPC:41A"),
                "Antil must contain bail/arrest statutory tuples");

        // Vidya Drolia
        var droliaOpt = corpusRegistry.findById("vidya_drolia_2020");
        assertTrue(droliaOpt.isPresent());
        PrecedentIndexRecord droliaRecord = seederConfig.buildIndexRecordFromSeed(droliaOpt.get());
        assertTrue(droliaRecord.actSectionTuples().contains("ARBITRATION_ACT:34")
                        || droliaRecord.actSectionTuples().contains("ARBITRATION_ACT:11")
                        || droliaRecord.actSectionTuples().contains("ARBITRATION_ACT:8"),
                "Vidya Drolia must contain arbitration statutory tuples");
    }

    @Test
    @DisplayName("PrecedentIndexRepository.buildCanonicalDedupKey produces normalized slug-year-court format")
    void shouldBuildCanonicalDedupKeyReliably() {
        String key1 = PrecedentIndexRepository.buildCanonicalDedupKey(
                "Arnesh Kumar v. State of Bihar", 2014, "SC");
        assertEquals("arnesh-kumar-v-state-of-bihar:2014:SC", key1);

        String key2 = PrecedentIndexRepository.buildCanonicalDedupKey(
                "Union of India & Anr. Versus Mohit Minerals Pvt. Ltd.", 2022, "sc");
        assertEquals("union-of-india-anr-v-mohit-minerals-pvt-ltd:2022:SC", key2);

        String keyFallback = PrecedentIndexRepository.buildCanonicalDedupKey(null, 0, null);
        assertEquals("unknown:0:SC", keyFallback);
    }

    @Test
    @DisplayName("PrecedentIndexRecord converts to backward-compatible CorpusPrecedentEntry seamlessly")
    void shouldConvertToCorpusPrecedentEntry() {
        PrecedentIndexRecord record = new PrecedentIndexRecord(
                "test_id", "test-key:2024:SC", "doc1", "shard0", "IRAC_ENRICHED",
                "State v. Accused", "SC", "Supreme Court of India", 12,
                "2024-01-15", 2024, "2024 INSC 1", List.of("(2024) 1 SCC 1"),
                LegalDomain.CRIMINAL, List.of("CONSTITUTIONAL"),
                List.of("CRPC:439", "BNSS:483"), List.of("bail"), List.of("regular-bail"),
                "APPLICANT_OR_DEFENCE", "ALLOWED", "Ratio test summary",
                List.of("Prop 1", "Prop 2"), List.of("bail", "custody"), null
        );

        DrawerPage testPage = new DrawerPage(1, "Page markdown", List.of("quote 1"));
        CorpusPrecedentEntry entryWithPages = record.toCorpusPrecedentEntry(List.of(testPage));

        assertEquals("test_id", entryWithPages.authorityId());
        assertEquals("State v. Accused", entryWithPages.caseTitle());
        assertEquals(LegalDomain.CRIMINAL, entryWithPages.primaryDomain());
        assertEquals("Ratio test summary", entryWithPages.summary());
        assertEquals(1, entryWithPages.pages().size());
        assertEquals(1, entryWithPages.pages().get(0).pageNumber());

        CorpusPrecedentEntry lazyEntry = record.toCorpusPrecedentEntry();
        assertTrue(lazyEntry.pages().isEmpty(), "Lazy entry should have empty pages list to conserve memory");
    }

    @Test
    @DisplayName("PrecedentSearchCriteria factory methods and validation operate cleanly")
    void shouldConstructValidSearchCriteria() {
        PrecedentSearchCriteria criteria = PrecedentSearchCriteria.ofTuples(
                List.of("CRPC:439", "BNSS:483"), 10);

        assertEquals(2, criteria.expandedActSectionTuples().size());
        assertNull(criteria.preferredDomain());
        assertNull(criteria.propositionQueryText());
        assertEquals(10, criteria.limit());

        PrecedentSearchCriteria topicCriteria = PrecedentSearchCriteria.ofTopic("regular-bail", "prolonged-custody", 15);
        assertEquals("regular-bail", topicCriteria.topicSlug());
        assertEquals("prolonged-custody", topicCriteria.issueTag());
        assertEquals(15, topicCriteria.limit());
    }

    @Test
    @DisplayName("Milestone 1 Seeder Dedup Guard: Enriches existing R2-backed record instead of inserting duplicate stub")
    void shouldEnrichExistingR2RecordInsteadOfInsertingDuplicateStub() {
        PrecedentIndexRepository mockRepo = mock(PrecedentIndexRepository.class);
        PrecedentCatalogSeederConfig guardedSeeder = new PrecedentCatalogSeederConfig(corpusRegistry, mockRepo, crosswalkService);

        PrecedentIndexRecord existingR2Record = new PrecedentIndexRecord(
                "corp-edd9a73b2c13b171", "satender-kumar-antil-v-cbi:2022:SC", "2022_INSC_690", "shard1",
                "CATALOG_STUB", "SATENDER KUMAR ANTIL versus CENTRAL BUREAU OF INVESTIGATION & ANR.",
                "SC", "Supreme Court of India", 14, "2022-07-11", 2022, "[2022] 10 S.C.R. 351",
                List.of("(2022) 10 SCC 51"), LegalDomain.CRIMINAL, List.of(),
                List.of("CRPC:439"), List.of("regular-bail"), List.of("bail-guidelines"),
                "APPLICANT_OR_DEFENCE", "ALLOWED", "Existing ratio", List.of(), List.of(),
                null, true, "judgments/SC/corp-edd9a73b2c13b171.json.gz", "https://r2.../key", "sc-v1.0"
        );

        when(mockRepo.findExistingR2Counterpart(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(existingR2Record));

        int seeded = guardedSeeder.seedPrecedentCatalog();

        // Must NOT insert any duplicate stubs
        assertEquals(0, seeded, "Seeded count should be 0 because all matched entries enrich existing R2 records");
        verify(mockRepo, never()).upsertPrecedent(any(), any(), any(), any());
        verify(mockRepo, atLeastOnce()).enrichR2RecordWithCuratedData(eq("corp-edd9a73b2c13b171"), any());
    }
}
