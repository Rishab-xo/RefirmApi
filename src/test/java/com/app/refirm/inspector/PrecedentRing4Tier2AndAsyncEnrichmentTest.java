package com.app.refirm.inspector;

import com.app.refirm.arguments.service.PrecedentAuthorityProvider;
import com.app.refirm.inspector.domain.HydratedJudgmentDocument;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.dto.InspectorDTOs.InAppJudgmentDrawerResponse;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.service.AuthorityDrawerService;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import com.app.refirm.inspector.service.PrecedentAsyncEnrichmentService;
import com.app.refirm.inspector.service.PrecedentAsyncEnrichmentService.DigestWindow;
import com.app.refirm.inspector.service.Tier2VaquillReadThroughClient;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.repository.WorkspaceSavedAuthorityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Step 5 (Ring 4): Tier 2 Vaquill Read-Through, Lazy Hydration & Async IRAC Enrichment Tests")
class PrecedentRing4Tier2AndAsyncEnrichmentTest {

    private ObjectMapper objectMapper;
    private PrecedentIndexRepository precedentIndexRepository;
    private Tier2VaquillReadThroughClient tier2Client;
    private PrecedentAsyncEnrichmentService asyncEnrichmentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        precedentIndexRepository = mock(PrecedentIndexRepository.class);
    }

    @Test
    @DisplayName("Stampede Guard: De-duplicates concurrent async enrichment triggers for the same authorityId")
    void testInFlightStampedeGuard() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch allowWorkerToFinish = new CountDownLatch(1);

        ExecutorService blockingExecutor = Executors.newSingleThreadExecutor();
        ExecutorService customExecutor = new ExecutorService() {
            @Override
            public void shutdown() { blockingExecutor.shutdown(); }
            @Override
            public List<Runnable> shutdownNow() { return blockingExecutor.shutdownNow(); }
            @Override
            public boolean isShutdown() { return blockingExecutor.isShutdown(); }
            @Override
            public boolean isTerminated() { return blockingExecutor.isTerminated(); }
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return blockingExecutor.awaitTermination(timeout, unit);
            }
            @Override
            public <T> Future<T> submit(Callable<T> task) { return blockingExecutor.submit(task); }
            @Override
            public <T> Future<T> submit(Runnable task, T result) { return blockingExecutor.submit(task, result); }
            @Override
            public Future<?> submit(Runnable task) {
                return blockingExecutor.submit(() -> {
                    workerStarted.countDown();
                    try {
                        allowWorkerToFinish.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}
                    task.run();
                });
            }
            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) { return List.of(); }
            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return List.of(); }
            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks) { return null; }
            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return null; }
            @Override
            public void execute(Runnable command) { blockingExecutor.execute(command); }
        };

        asyncEnrichmentService = new PrecedentAsyncEnrichmentService(
                precedentIndexRepository, null, null, objectMapper, customExecutor, false);

        PrecedentIndexRecord stub = createSampleRecord("auth-stampede-1", "CATALOG_STUB");

        // First trigger enters the stampede guard
        asyncEnrichmentService.triggerAsyncEnrichment(stub, null);
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
        assertTrue(asyncEnrichmentService.getInFlightAuthorityIds().contains("auth-stampede-1"));

        // Second trigger for the same authorityId must be rejected by in-flight guard
        asyncEnrichmentService.triggerAsyncEnrichment(stub, null);

        // Allow worker to finish
        allowWorkerToFinish.countDown();
        blockingExecutor.shutdown();
        assertTrue(blockingExecutor.awaitTermination(2, TimeUnit.SECONDS));

        // After completion, the guard set must be clear
        assertFalse(asyncEnrichmentService.getInFlightAuthorityIds().contains("auth-stampede-1"));
    }

    @Test
    @DisplayName("Whole-Judgment Window: HEAD <= 2500 chars, HIT extracts section citations, TAIL contains operative holding")
    void testAssembleHeadHitTailWindow() {
        asyncEnrichmentService = new PrecedentAsyncEnrichmentService(
                precedentIndexRepository, null, null, objectMapper, Executors.newSingleThreadExecutor(), false);

        String longHeadText = "A".repeat(3000) + "\n\nParties: Union of India vs Citizen.";
        String hitText = "[PARA 15] Dealing with Section 439 of CrPC, prolonged detention without trial is impermissible.";
        String tailText = "In conclusion, appeal is allowed. Held: Appellant is enlarged on bail.\n\nOrdered accordingly.";

        List<DrawerPage> pages = List.of(
                new DrawerPage(1, longHeadText, List.of()),
                new DrawerPage(2, hitText, List.of("prolonged detention without trial is impermissible")),
                new DrawerPage(3, tailText, List.of("Held: Appellant is enlarged on bail"))
        );

        DigestWindow window = asyncEnrichmentService.assembleHeadHitTailWindow(pages, List.of("CRPC:439"));

        assertNotNull(window);
        assertTrue(window.head().length() <= 2500, "HEAD must be bounded to <= 2500 chars");
        assertTrue(window.hit().contains("prolonged detention"), "HIT must contain the middle statutory/ratio passage");
        assertTrue(window.tail().contains("Held: Appellant is enlarged on bail"), "TAIL must contain the final operative order");
    }

    @Test
    @DisplayName("Validation Guardrail: Never overwrites a valid stub when LLM output has missing or empty IRAC components")
    void testValidationGuardrailRejectsInvalidLlmOutput() {
        ChatClient mockChatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        // LLM returns invalid JSON missing 'rule' and 'conclusion'
        when(mockChatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("{\"issue\": \"Whether bail should be granted\", \"rule\": \"\", \"conclusion\": \"\"}");

        asyncEnrichmentService = new PrecedentAsyncEnrichmentService(
                precedentIndexRepository, null, mockChatClient, objectMapper, Executors.newSingleThreadExecutor(), false);

        PrecedentIndexRecord stub = createSampleRecord("auth-invalid-llm-1", "CATALOG_STUB");
        List<DrawerPage> pages = List.of(new DrawerPage(1, "Page 1 Content\n\nHeld: Bail granted.", List.of()));

        boolean success = asyncEnrichmentService.performEnrichment(stub, pages);

        // Validation guardrail must reject this extraction and NOT call upsertPrecedent
        assertFalse(success, "Enrichment must fail when rule or conclusion are blank");
        verify(precedentIndexRepository, never()).upsertPrecedent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Tier 2 Client Circuit Breaker: Offline endpoint degrades gracefully in < 3.5s returning empty")
    void testTier2CircuitBreakerDegradesGracefully() {
        // Pointing to a non-existent port to simulate offline remote endpoint
        tier2Client = new Tier2VaquillReadThroughClient(true, "http://127.0.0.1:54321", objectMapper);

        long start = System.currentTimeMillis();
        Optional<HydratedJudgmentDocument> docOpt = tier2Client.fetchFullDocumentByDocId("default", "missing-doc-123");
        List<HydratedJudgmentDocument> searchHits = tier2Client.searchColdMissFallback(List.of("CRPC:439"), "bail parity", 5);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(docOpt.isEmpty(), "Offline Tier 2 fetch must return Optional.empty()");
        assertTrue(searchHits.isEmpty(), "Offline Tier 2 search must return empty list");
        assertTrue(elapsed < 4000, "Circuit breaker must degrade in < 4.0s without hanging");
    }

    @Test
    @DisplayName("Tier 2 Parsing: Reconstructs HydratedJudgmentDocument with DrawerPages and holding from JSON")
    void testTier2DocumentParsingAndPageSplitting() {
        tier2Client = new Tier2VaquillReadThroughClient(true, "https://datasets-server.huggingface.co", objectMapper);

        String sampleJson = """
            {
              "rows": [
                {
                  "row": {
                    "doc_id": "sc-2022-doc-99",
                    "chunk_index": 0,
                    "chunk_text": "In the Supreme Court of India.\\n\\nState vs Accused.\\n\\nDispute regarding section 439.",
                    "case_title": "State vs Accused",
                    "primary_citation": "2022 INSC 99",
                    "court_code": "SC",
                    "decision_year": 2022
                  }
                },
                {
                  "row": {
                    "doc_id": "sc-2022-doc-99",
                    "chunk_index": 1,
                    "chunk_text": "The high court erred in denying bail.\\n\\nHeld: In conclusion, appeal allowed and accused is released on bail.",
                    "case_title": "State vs Accused",
                    "primary_citation": "2022 INSC 99",
                    "court_code": "SC",
                    "decision_year": 2022
                  }
                }
              ]
            }
            """;

        Optional<HydratedJudgmentDocument> result = tier2Client.parseDocumentFromResponse(sampleJson, "sc-2022-doc-99");

        assertTrue(result.isPresent());
        HydratedJudgmentDocument doc = result.get();
        assertEquals("sc-2022-doc-99", doc.docId());
        assertEquals("State vs Accused", doc.caseTitle());
        assertEquals("2022 INSC 99", doc.primaryCitation());
        assertFalse(doc.pages().isEmpty());
        assertTrue(doc.finalHoldingSentence().toLowerCase().contains("appeal allowed")
                || doc.finalHoldingSentence().toLowerCase().contains("held"));
    }

    @Test
    @DisplayName("AuthorityDrawerService: Lazily hydrates missing Table 2 pages via Tier 2 and triggers write-behind enrichment")
    void testDrawerLazyHydrationAndAsyncTrigger() {
        LegalWorkspaceRepository workspaceRepo = mock(LegalWorkspaceRepository.class);
        WorkspaceDocumentRepository docRepo = mock(WorkspaceDocumentRepository.class);
        WorkspaceSavedAuthorityRepository savedRepo = mock(WorkspaceSavedAuthorityRepository.class);
        PrecedentAuthorityProvider provider = mock(PrecedentAuthorityProvider.class);
        CorpusPrecedentRegistryService corpusRegistry = mock(CorpusPrecedentRegistryService.class);
        PariMateriaCrosswalkService crosswalk = mock(PariMateriaCrosswalkService.class);
        Tier2VaquillReadThroughClient mockTier2 = mock(Tier2VaquillReadThroughClient.class);
        PrecedentAsyncEnrichmentService mockAsync = mock(PrecedentAsyncEnrichmentService.class);

        UUID wsId = UUID.randomUUID();
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(wsId);
        workspace.setClerkUserId("advocate_1");
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setPrimaryDomain(com.app.refirm.triage.domain.LegalDomain.CRIMINAL);
        when(workspaceRepo.findByWorkspaceIdAndClerkUserId(wsId, "advocate_1")).thenReturn(Optional.of(workspace));

        PrecedentIndexRecord stub = createSampleRecord("auth-lazy-1", "CATALOG_STUB");
        when(precedentIndexRepository.findByAuthorityId("auth-lazy-1")).thenReturn(Optional.of(stub));
        // Table 2 pages are missing initially
        when(precedentIndexRepository.findDrawerPagesByAuthorityId("auth-lazy-1")).thenReturn(List.of());

        // Tier 2 returns 2 hydrated pages
        List<DrawerPage> hydratedPages = List.of(
                new DrawerPage(1, "Page 1 Content", List.of()),
                new DrawerPage(2, "Page 2 Content. Held: Appeal allowed.", List.of("Held: Appeal allowed."))
        );
        HydratedJudgmentDocument doc = new HydratedJudgmentDocument(
                "parquet-doc-1", stub.caseTitle(), stub.primaryCitation(), "SC", 2022,
                hydratedPages, "Held: Appeal allowed.", "Full text"
        );
        when(mockTier2.fetchFullDocumentByDocId(eq("default"), eq("parquet-doc-1"))).thenReturn(Optional.of(doc));

        AuthorityDrawerService drawerService = new AuthorityDrawerService(
                workspaceRepo, docRepo, savedRepo, provider, corpusRegistry, null,
                precedentIndexRepository, crosswalk, mockTier2, mockAsync
        );

        InAppJudgmentDrawerResponse response = drawerService.getDrawer(wsId, "advocate_1", "auth-lazy-1");

        assertNotNull(response);
        assertEquals("auth-lazy-1", response.authorityId());
        assertEquals(2, response.fullTextPages().size());

        // Verify lazy hydration persisted pages to Table 2
        verify(precedentIndexRepository).saveDrawerPages(eq("auth-lazy-1"), eq(hydratedPages));

        // Verify async write-behind enrichment was triggered for CATALOG_STUB
        verify(mockAsync).triggerAsyncEnrichment(eq(stub), eq(hydratedPages));
    }

    private PrecedentIndexRecord createSampleRecord(String authorityId, String status) {
        return new PrecedentIndexRecord(
                authorityId,
                "sc-2022-sample-v-union",
                "parquet-doc-1",
                "default",
                status,
                "Sample vs Union of India",
                "SC",
                "Supreme Court of India",
                10,
                "2022-04-10",
                2022,
                "2022 INSC 101",
                List.of(),
                LegalDomain.CRIMINAL,
                List.of(),
                List.of("CRPC:439", "BNSS:483"),
                List.of("regular_bail"),
                List.of("parity"),
                "CLAIMANT_PETITIONER",
                "ALLOWED",
                "Bail must be granted when parity and prolonged custody are demonstrated.",
                List.of("Parity in regular bail", "Prolonged incarceration"),
                List.of("bail", "parity"),
                null
        );
    }
}
