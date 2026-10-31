package com.app.refirm.triage;

import com.app.refirm.triage.domain.*;
import com.app.refirm.triage.pipeline.BriefGenerator;
import com.app.refirm.triage.pipeline.FactExtractor;
import com.app.refirm.triage.pipeline.ForumResolver;
import com.app.refirm.triage.pipeline.StatuteRetriever;
import com.app.refirm.triage.pipeline.impl.DefaultForumResolver;
import com.app.refirm.triage.pipeline.impl.TwoPassPipelineOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwoPassPipelineOrchestratorTest {

    @Mock
    private FactExtractor factExtractor;

    @Mock
    private StatuteRetriever statuteRetriever;

    @Mock
    private BriefGenerator briefGenerator;

    private ForumResolver consumerResolver;
    private ForumResolver reraResolver;
    private ForumResolver defaultResolver;

    @BeforeEach
    void setUp() {
        consumerResolver = new ForumResolver() {
            @Override
            public boolean supports(LegalDomain domain) {
                return LegalDomain.CONSUMER_RIGHTS.equals(domain);
            }

            @Override
            public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
                return new ResolvedForum(
                        "District Consumer Disputes Redressal Commission, " + userLocation,
                        "e-Daakhil",
                        "Statutory jurisdiction under CPA 2019"
                );
            }
        };

        reraResolver = new ForumResolver() {
            @Override
            public boolean supports(LegalDomain domain) {
                return LegalDomain.REAL_ESTATE.equals(domain);
            }

            @Override
            public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
                return new ResolvedForum(
                        "Real Estate Regulatory Authority (RERA), " + userLocation,
                        "State RERA Portal",
                        "Statutory jurisdiction under Section 31 of RERA 2016"
                );
            }
        };

        defaultResolver = new DefaultForumResolver();
    }

    @Test
    @DisplayName("Strategy Pattern selects consumer resolver for CONSUMER_RIGHTS")
    void selectsConsumerForumResolverDynamically() {
        List<ForumResolver> resolvers = List.of(consumerResolver, reraResolver, defaultResolver);

        TwoPassPipelineOrchestrator orchestrator = new TwoPassPipelineOrchestrator(
                factExtractor,
                resolvers,
                statuteRetriever,
                briefGenerator
        );

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                List.of(new PartyEntity("Telecom Provider", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE)),
                List.of("Deficient telecom service"),
                150000.0
        );

        List<StatutePin> pins = List.of(
                new StatutePin("Consumer Protection Act, 2019", "Section 2(11)", StatuteType.SUBSTANTIVE_BREACH, "Chapter I: Preliminary", "Deficiency means any fault, imperfection or shortcoming...")
        );

        when(factExtractor.extract("My telecom provider failed to deliver service")).thenReturn(facts);
        when(statuteRetriever.retrieveStatutes(facts)).thenReturn(new StatuteRetriever.StatuteRetrievalResult(pins, List.of()));

        DraftingContext context = orchestrator.assembleContext("My telecom provider failed to deliver service", "Pune");

        assertThat(context).isNotNull();
        assertThat(context.userNarrative()).isEqualTo("My telecom provider failed to deliver service");
        assertThat(context.facts().domain()).isEqualTo(LegalDomain.CONSUMER_RIGHTS);
        assertThat(context.statutePack()).hasSize(1);
        assertThat(context.targetForum().forumName()).contains("District Consumer Disputes Redressal Commission");
        assertThat(context.targetForum().forumName()).contains("Pune");
        assertThat(context.targetForum().filingMethod()).isEqualTo("e-Daakhil");
    }

    @Test
    @DisplayName("Strategy Pattern selects rera resolver for REAL_ESTATE")
    void selectsReraForumResolverDynamically() {
        List<ForumResolver> resolvers = List.of(consumerResolver, reraResolver, defaultResolver);

        TwoPassPipelineOrchestrator orchestrator = new TwoPassPipelineOrchestrator(
                factExtractor,
                resolvers,
                null,
                null
        );

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.REAL_ESTATE,
                List.of(new PartyEntity("Builder", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE)),
                List.of("Possession delayed by 3 years"),
                7500000.0
        );

        when(factExtractor.extract("Builder delayed possession")).thenReturn(facts);

        DraftingContext context = orchestrator.assembleContext("Builder delayed possession", "Noida");

        assertThat(context.facts().domain()).isEqualTo(LegalDomain.REAL_ESTATE);
        assertThat(context.targetForum().forumName()).contains("Real Estate Regulatory Authority (RERA)");
        assertThat(context.targetForum().forumName()).contains("Noida");
        assertThat(context.statutePack()).isEmpty();
    }

    @Test
    @DisplayName("Strategy Pattern falls back to DefaultForumResolver for unspecialized domains")
    void fallsBackToDefaultForumResolver() {
        List<ForumResolver> resolvers = List.of(consumerResolver, reraResolver, defaultResolver);

        TwoPassPipelineOrchestrator orchestrator = new TwoPassPipelineOrchestrator(
                factExtractor,
                resolvers,
                null,
                null
        );

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.EMPLOYMENT,
                List.of(
                        new PartyEntity("Employer", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE),
                        new PartyEntity("Employee", EntityRole.CONSUMER, RelationshipType.PRIMARY_CLAIMANT, FinancialRole.NONE)
                ),
                List.of("Unlawful termination without notice"),
                500000.0
        );

        when(factExtractor.extract("Terminated without severance")).thenReturn(facts);

        DraftingContext context = orchestrator.assembleContext("Terminated without severance", "Hyderabad");

        assertThat(context.targetForum().forumName()).contains("Competent Civil Court / Designated Statutory Authority");
        assertThat(context.targetForum().forumName()).contains("Hyderabad");
    }

    @Test
    @DisplayName("Full pipeline execution delegates to BriefGenerator if present")
    void executesFullPipelineWithBriefGenerator() {
        List<ForumResolver> resolvers = List.of(consumerResolver, defaultResolver);

        TwoPassPipelineOrchestrator orchestrator = new TwoPassPipelineOrchestrator(
                factExtractor,
                resolvers,
                statuteRetriever,
                briefGenerator
        );

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                List.of(new PartyEntity("Other Party", EntityRole.OPPOSITE_PARTY, RelationshipType.OPPOSING_ENTITY, FinancialRole.NONE)),
                List.of(),
                10000.0
        );

        when(factExtractor.extract(any())).thenReturn(facts);
        when(statuteRetriever.retrieveStatutes(any())).thenReturn(new StatuteRetriever.StatuteRetrievalResult(List.of(), List.of()));
        when(briefGenerator.generateDraft(any(DraftingContext.class))).thenReturn("# Generated Legal Brief");

        String result = orchestrator.executePipeline("Sample narrative", "Chennai");

        assertThat(result).isEqualTo("# Generated Legal Brief");
        verify(briefGenerator).generateDraft(any(DraftingContext.class));
    }
}
