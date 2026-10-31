package com.app.refirm.templates;

import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateRecommendation;
import com.app.refirm.templates.service.TemplateRecommendationService;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Step 3: Template Registry & Smart Procedural Recommendation Tests")
class TemplateRegistryAndRecommendationTest {

    private TemplateRegistryService registryService;
    private TemplateRecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        registryService = new TemplateRegistryService(new ObjectMapper());
        registryService.loadTemplates();
        recommendationService = new TemplateRecommendationService(registryService);
    }

    @Test
    @DisplayName("Should load statutory master templates cleanly from classpath")
    void testRegistryLoadsAllTemplates() {
        List<TemplateManifest> all = registryService.getAllTemplates();
        assertTrue(all.size() >= 10, "Registry must contain at least 10 templates");

        assertNotNull(registryService.getTemplateOrThrow("consumer_legal_notice_deficiency"));
        assertNotNull(registryService.getTemplateOrThrow("consumer_complaint_sec35_edaakhil"));
        assertNotNull(registryService.getTemplateOrThrow("consumer_appeal_sec41_state_comm"));
        assertNotNull(registryService.getTemplateOrThrow("written_statement_opposite_party"));
        assertNotNull(registryService.getTemplateOrThrow("counter_affidavit_general"));
        assertNotNull(registryService.getTemplateOrThrow("supporting_affidavit_general"));
        assertNotNull(registryService.getTemplateOrThrow("bsa_sec63_electronic_evidence_cert"));
        assertNotNull(registryService.getTemplateOrThrow("civil_suit_recovery_rent_surety"));
        assertNotNull(registryService.getTemplateOrThrow("commercial_works_contractor_agreement"));
        assertNotNull(registryService.getTemplateOrThrow("rera_flat_promoter_purchaser_agreement"));
    }

    @Test
    @DisplayName("Should recommend Claimant notices & complaint for CONSUMER + CLAIMANT_PETITIONER workspace")
    void testRecommenderForConsumerClaimant() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);
        workspace.setPartyPerspective(PartyPerspective.CLAIMANT_PETITIONER);

        List<TemplateRecommendation> recs = recommendationService.recommendForWorkspace(workspace);
        assertFalse(recs.isEmpty());

        // Top match scores should be at least 90 (50 domain + 40 perspective + stage boost)
        int topScore = recs.get(0).matchScore();
        assertTrue(topScore >= 90, "Top score must be >= 90");

        List<String> topIds = recs.stream()
                .filter(r -> r.matchScore() >= 90)
                .map(r -> r.template().templateId())
                .toList();

        assertTrue(topIds.contains("consumer_legal_notice_deficiency"),
                "Claimant legal notice should be among top recommendations");
        assertTrue(topIds.contains("consumer_complaint_sec35_edaakhil"),
                "Claimant complaint should be among top recommendations");
    }

    @Test
    @DisplayName("Should recommend Written Statement at the top when switched to OPPOSITE_PARTY_RESPONDENT")
    void testRecommenderForConsumerOppositeParty() {
        LegalWorkspace workspace = new LegalWorkspace();
        workspace.setPrimaryDomain(LegalDomain.CONSUMER);
        workspace.setPartyPerspective(PartyPerspective.OPPOSITE_PARTY_RESPONDENT);

        List<TemplateRecommendation> recs = recommendationService.recommendForWorkspace(workspace);
        assertFalse(recs.isEmpty());

        TemplateRecommendation topRec = recs.get(0);
        assertEquals("written_statement_opposite_party", topRec.template().templateId(),
                "Written Statement / Reply must be the #1 recommendation for Opposite Party");
        assertTrue(topRec.matchScore() >= 90, "Match score must be >= 90 (50 domain + 40 perspective + stage boost)");
        assertNotNull(topRec.variantComparisonNote());
    }
}
