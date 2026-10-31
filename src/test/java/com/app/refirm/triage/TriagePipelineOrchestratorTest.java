package com.app.refirm.triage;

import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.EntityRole;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.triage.domain.StatuteType;
import com.app.refirm.triage.pipeline.BriefGenerator;
import com.app.refirm.triage.pipeline.FactExtractor;
import com.app.refirm.triage.pipeline.ForumResolver;
import com.app.refirm.triage.pipeline.StatuteRetriever;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.pipeline.impl.TriagePipelineOrchestratorImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriagePipelineOrchestratorTest {

    @Mock
    private FactExtractor factExtractor;

    @Mock
    private StatuteRetriever statuteRetriever;

    @Mock
    private BriefGenerator briefGenerator;

    @Test
    @DisplayName("processCase successfully executes the full four-step pipeline")
    void processCaseExecutesFullPipeline() {
        ForumResolver resolver = new ForumResolver() {
            @Override
            public boolean supports(LegalDomain domain) {
                return LegalDomain.CONSUMER_RIGHTS.equals(domain);
            }

            @Override
            public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
                return new ResolvedForum("District Consumer Disputes Redressal Commission, Delhi", "e-Daakhil", "CPA 2019");
            }
        };
        List<ForumResolver> resolvers = List.of(resolver);

        TriagePipelineOrchestratorImpl orchestrator = new TriagePipelineOrchestratorImpl(
                factExtractor,
                resolvers,
                statuteRetriever,
                briefGenerator
        );

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                List.of(new com.app.refirm.triage.domain.PartyEntity("Telecom Provider", EntityRole.OPPOSITE_PARTY, com.app.refirm.triage.domain.RelationshipType.OPPOSING_ENTITY, com.app.refirm.triage.domain.FinancialRole.NONE)),
                List.of("Telecom defect"),
                35000.0
        );

        List<StatutePin> pins = List.of(
                new StatutePin("Consumer Protection Act, 2019", "Section 2(11)", StatuteType.SUBSTANTIVE_BREACH, "Deficiency", "Operative text")
        );

        when(factExtractor.extract("Telecom issue narrative")).thenReturn(facts);
        when(statuteRetriever.retrieveStatutes(facts)).thenReturn(new StatuteRetriever.StatuteRetrievalResult(pins, List.of()));
        when(briefGenerator.generateDraft(any(DraftingContext.class))).thenReturn("# Legal Battle Plan");

        String result = orchestrator.processCase("Telecom issue narrative", "Delhi");

        assertThat(result).isEqualTo("# Legal Battle Plan");

        ArgumentCaptor<DraftingContext> captor = ArgumentCaptor.forClass(DraftingContext.class);
        verify(briefGenerator).generateDraft(captor.capture());

        DraftingContext captured = captor.getValue();
        assertThat(captured.userNarrative()).isEqualTo("Telecom issue narrative");
        assertThat(captured.facts()).isEqualTo(facts);
        assertThat(captured.statutePack()).isEqualTo(pins);
        assertThat(captured.targetForum().forumName()).contains("District Consumer Disputes Redressal Commission");
        assertThat(captured.targetForum().forumName()).contains("Delhi");
    }

    @Test
    @DisplayName("processCase falls back to default forum when no matching resolver supports domain")
    void processCaseFallsBackToDefaultForum() {
        ForumResolver resolver = new ForumResolver() {
            @Override
            public boolean supports(LegalDomain domain) {
                return LegalDomain.CONSUMER_RIGHTS.equals(domain);
            }

            @Override
            public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
                return new ResolvedForum("District Consumer Commission", "e-Daakhil", "CPA 2019");
            }
        };
        List<ForumResolver> resolvers = List.of(resolver);

        TriagePipelineOrchestratorImpl orchestrator = new TriagePipelineOrchestratorImpl(
                factExtractor,
                resolvers,
                statuteRetriever,
                briefGenerator
        );

        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CORPORATE,
                List.of(new com.app.refirm.triage.domain.PartyEntity("Company", EntityRole.OPPOSITE_PARTY, com.app.refirm.triage.domain.RelationshipType.OPPOSING_ENTITY, com.app.refirm.triage.domain.FinancialRole.NONE)),
                List.of("Shareholder dispute"),
                10000000.0
        );

        when(factExtractor.extract("Corporate dispute")).thenReturn(facts);
        when(statuteRetriever.retrieveStatutes(facts)).thenReturn(new StatuteRetriever.StatuteRetrievalResult(List.of(), List.of()));
        when(briefGenerator.generateDraft(any(DraftingContext.class))).thenReturn("# Corporate Legal Brief");

        String result = orchestrator.processCase("Corporate dispute", "Mumbai");

        assertThat(result).isEqualTo("# Corporate Legal Brief");

        ArgumentCaptor<DraftingContext> captor = ArgumentCaptor.forClass(DraftingContext.class);
        verify(briefGenerator).generateDraft(captor.capture());

        DraftingContext captured = captor.getValue();
        assertThat(captured.targetForum().forumName()).isEqualTo("Appropriate Statutory Tribunal / Civil Court");
        assertThat(captured.targetForum().filingMethod()).isEqualTo("Standard Filing");
    }
}
