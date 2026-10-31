package com.app.refirm.templates;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.service.TemplateFactResolver;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.templates.service.TemplateRenderer;
import com.app.refirm.triage.domain.*;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Step 3: 0-Token Auto-Fill & Deterministic Splicing Tests")
class ZeroTokenTemplateAutoFillTest {

    private TemplateRegistryService registryService;
    private TemplateFactResolver factResolver;
    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        registryService = new TemplateRegistryService(new ObjectMapper());
        registryService.loadTemplates();
        factResolver = new TemplateFactResolver();
        renderer = new TemplateRenderer();
    }

    @Test
    @DisplayName("Should extract facts from CaseFactLedger & DraftingContext with 0 tokens and leave missing as [FILL: ...]")
    void testZeroTokenFactResolutionAndMissingPlaceholders() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);

        PartyEntity claimant = new PartyEntity("Sunita Rao", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.PAID_CONSIDERATION);
        PartyEntity op = new PartyEntity("QuickCart Logistics Ltd.", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER,
                claimant,
                null,
                List.of(op),
                List.of(),
                28500.0
        );
        ExtractedFactsDTO ruleFacts = new ExtractedFactsDTO(
                com.app.refirm.rules.model.LegalDomain.CONSUMER,
                28500L,
                "Bengaluru",
                "Vendor",
                null
        );
        workspace.setFactLedger(CaseFactLedger.initial(facts, ruleFacts));

        DraftingContext draftingContext = new DraftingContext(
                "Defective laptop delivered",
                facts,
                List.of(),
                new ResolvedForum("District Consumer Disputes Redressal Commission, Bengaluru Urban", "e-Daakhil", "Sec 34 CPA 2019")
        );
        workspace.setLatestDraftingContext(draftingContext);

        TemplateManifest manifest = registryService.getTemplateOrThrow("consumer_complaint_sec35_edaakhil");

        Map<String, String> resolved = factResolver.resolveParticularsFromWorkspace(manifest, workspace);

        assertEquals("Sunita Rao", resolved.get("complainant_name"));
        assertEquals("QuickCart Logistics Ltd.", resolved.get("opposite_party_name"));
        assertEquals("28500", resolved.get("dispute_amount"));
        assertEquals("District Consumer Disputes Redressal Commission, Bengaluru Urban", resolved.get("court_name"));
        assertEquals("Bengaluru", resolved.get("complainant_address"));

        // Render with partial values
        TemplateRenderer.RenderResult renderResult = renderer.render(manifest, resolved, Collections.emptyMap());

        assertNotNull(renderResult.renderedMarkdown());
        assertTrue(renderResult.renderedMarkdown().contains("Sunita Rao"));
        assertTrue(renderResult.renderedMarkdown().contains("QuickCart Logistics Ltd."));

        // Missing fields should have placeholder [FILL: ...]
        assertTrue(renderResult.missingParticularKeys().contains("opposite_party_address"));
        assertTrue(renderResult.renderedMarkdown().contains("[FILL: Opposite Party Complete Address]"));
    }

    @Test
    @DisplayName("Should handle ₹, $, and backslash characters in values without regex crashes")
    void testCurrencySymbolsWithoutRegexCrashes() {
        TemplateManifest manifest = registryService.getTemplateOrThrow("consumer_legal_notice_deficiency");

        Map<String, String> filled = Map.of(
                "sender_advocate_name", "Adv. R. Sharma",
                "advocate_chamber", "Chamber #402, High Court Annex",
                "notice_date", "17 October 2026",
                "client_name", "Amit Verma",
                "client_address", "Flat $101, Prestige Towers",
                "opposite_party_name", "Mega Retail Ltd.",
                "opposite_party_address", "Plot ₹50/2, Cyber City",
                "dispute_amount", "₹2,50,000 (US $3,000)",
                "notice_period_days", "15"
        );

        assertDoesNotThrow(() -> {
            TemplateRenderer.RenderResult result = renderer.render(manifest, filled, Collections.emptyMap());
            assertNotNull(result);
            assertTrue(result.renderedMarkdown().contains("₹2,50,000 (US $3,000)"));
            assertTrue(result.renderedMarkdown().contains("Flat $101, Prestige Towers"));
            assertTrue(result.renderedMarkdown().contains("Plot ₹50/2, Cyber City"));
        }, "Template rendering must never throw regex syntax errors or illegal group reference exceptions");
    }

    @Test
    @DisplayName("Should resolve perspectiveClient.name perspective-aware (Claimant vs Opposite Party)")
    void testPerspectiveAwareResolution() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);

        PartyEntity claimant = new PartyEntity("Rajesh Kumar", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.PAID_CONSIDERATION);
        PartyEntity op = new PartyEntity("Apex Finance Bank Ltd.", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.PROMOTER_OR_MERCHANT);

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER,
                claimant,
                null,
                List.of(op),
                List.of(),
                50000.0
        );
        workspace.setFactLedger(CaseFactLedger.initial(facts, null));

        TemplateManifest noticeManifest = registryService.getTemplateOrThrow("consumer_legal_notice_deficiency");

        // 1. In CLAIMANT perspective, perspectiveClient.name -> Rajesh Kumar
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);
        Map<String, String> claimantParticulars = factResolver.resolveParticularsFromWorkspace(noticeManifest, workspace);
        assertEquals("Rajesh Kumar", claimantParticulars.get("client_name"));

        // 2. In OPPOSITE_PARTY perspective, perspectiveClient.name -> Apex Finance Bank Ltd.
        workspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT);
        Map<String, String> opParticulars = factResolver.resolveParticularsFromWorkspace(noticeManifest, workspace);
        assertEquals("Apex Finance Bank Ltd.", opParticulars.get("client_name"));
    }
}
