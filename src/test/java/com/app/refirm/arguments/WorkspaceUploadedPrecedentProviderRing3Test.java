package com.app.refirm.arguments;

import com.app.refirm.arguments.domain.VerifiedPrecedentAuthority;
import com.app.refirm.arguments.service.WorkspaceUploadedPrecedentProvider;
import com.app.refirm.inspector.domain.PrecedentIndexRecord;
import com.app.refirm.inspector.domain.PrecedentSearchCriteria;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.inspector.service.PariMateriaCrosswalkService;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WorkspaceUploadedPrecedentProviderRing3Test {

    private PariMateriaCrosswalkService crosswalkService;
    private CorpusPrecedentRegistryService corpusRegistry;
    private PrecedentIndexRepository precedentRepository;
    private WorkspaceUploadedPrecedentProvider provider;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        crosswalkService = new PariMateriaCrosswalkService(objectMapper);
        crosswalkService.init();

        corpusRegistry = new CorpusPrecedentRegistryService(objectMapper);
        corpusRegistry.init();

        precedentRepository = Mockito.mock(PrecedentIndexRepository.class);

        provider = new WorkspaceUploadedPrecedentProvider(
                null,
                corpusRegistry,
                precedentRepository,
                crosswalkService
        );
    }

    @Test
    @DisplayName("resolveVerifiedPrecedents enriches ratioSummary with [Supports: ...] and Pari Materia badge for modern BNSS 483")
    void shouldEnrichRatioSummaryWithPostureAndPariMateriaBadge() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID());
        workspace.setClerkUserId("test_advocate");
        workspace.setPrimaryDomain(com.app.refirm.triage.domain.LegalDomain.CRIMINAL);

        // Mock Table 1 returning Satender Kumar Antil (decided under CrPC Section 439)
        PrecedentIndexRecord antilRecord = new PrecedentIndexRecord(
                "satender_kumar_antil_2022",
                "satender-kumar-antil-v-cbi:2022:SC",
                "",
                "",
                "IRAC_ENRICHED",
                "Satender Kumar Antil v. CBI",
                "SC",
                "Supreme Court of India",
                12,
                "2022-07-11",
                2022,
                "2022 INSC 690",
                List.of("(2022) 10 SCC 51"),
                LegalDomain.CRIMINAL,
                List.of(),
                List.of("CRPC:439", "CRPC:41A", "CRPC:437"),
                List.of("regular-bail"),
                List.of("prolonged-custody", "parity"),
                "APPLICANT_OR_DEFENCE",
                "ALLOWED",
                "Bail is the rule and jail is the exception under Indian criminal jurisprudence.",
                List.of("Bail guideline directions"),
                List.of("bail", "439", "41a"),
                null
        );

        when(precedentRepository.searchPrecedents(any(PrecedentSearchCriteria.class)))
                .thenReturn(List.of(antilRecord));

        // Modern 2026 statutory tuple: BNSS:483 (pari materia to CrPC 439)
        List<VerifiedPrecedentAuthority> authorities = provider.resolveVerifiedPrecedents(
                workspace,
                "urgent regular bail under Section 483 BNSS",
                List.of("BNSS:483"),
                false
        );

        assertFalse(authorities.isEmpty(), "Authorities list should not be empty");
        VerifiedPrecedentAuthority auth = authorities.get(0);

        assertEquals("satender_kumar_antil_2022", auth.authorityId());
        assertEquals("VERIFIED_CORPUS", auth.sourceType());
        assertTrue(auth.caseTitleAndCitation().contains("Satender Kumar Antil"));

        // Verify [Supports: ...] posture formatting
        assertTrue(auth.ratioSummary().contains("[Supports: APPLICANT_OR_DEFENCE]"),
                "Must format posture prefix deterministically: " + auth.ratioSummary());

        // Verify Pari Materia badge enrichment: [Decided under Sec 439 CrPC — Pari Materia to Sec 483 BNSS]
        assertTrue(auth.ratioSummary().contains("Pari Materia to Sec 483 BNSS")
                        && auth.ratioSummary().contains("Sec 439 CrPC"),
                "Must enrich with deterministic crosswalk badge: " + auth.ratioSummary());

        // Verify underlying ratio text is appended
        assertTrue(auth.ratioSummary().contains("Bail is the rule and jail is the exception"),
                "Must preserve underlying ratio text: " + auth.ratioSummary());
    }

    @Test
    @DisplayName("Precedent Contamination Guard: Standalone query ignores workspace documents")
    void shouldEnforceContaminationGuardInStandaloneMode() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID());
        workspace.setClerkUserId("test_advocate");
        workspace.setPrimaryDomain(com.app.refirm.triage.domain.LegalDomain.CRIMINAL);

        when(precedentRepository.searchPrecedents(any(PrecedentSearchCriteria.class)))
                .thenReturn(List.of());

        // Standalone query: isStandaloneQuery = true
        List<VerifiedPrecedentAuthority> authorities = provider.resolveVerifiedPrecedents(
                workspace,
                "general query",
                List.of(),
                true
        );

        // Fallback to in-memory corpus catalog when DB returns empty
        assertNotNull(authorities);
        for (VerifiedPrecedentAuthority auth : authorities) {
            assertEquals("VERIFIED_CORPUS", auth.sourceType(),
                    "Standalone query should only return verified corpus, never workspace documents");
        }
    }
}
