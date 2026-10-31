package com.app.refirm.triage;

import com.app.refirm.rules.registry.StatuteRegistry;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.pipeline.impl.DslForumResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DslForumResolverTest {

    @Mock
    private StatuteRegistry statuteRegistry;

    private DslForumResolver forumResolver;

    @BeforeEach
    void setUp() {
        forumResolver = new DslForumResolver(statuteRegistry);
    }

    @Test
    @DisplayName("Consumer loss <= 50 Lakhs routes to District Commission (DCDRC)")
    void routesToDistrictCommission() {
        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                null,
                null,
                List.of(),
                List.of(),
                450000.0 // 4.5 Lakhs
        );

        ResolvedForum forum = forumResolver.resolve(facts, "Mumbai");

        assertThat(forum.forumName()).contains("District Consumer Disputes Redressal Commission (DCDRC) (Mumbai)");
        assertThat(forum.filingMethod()).contains("e-Daakhil");
        assertThat(forum.jurisdictionReason()).contains("District Commission pecuniary limit (<= Rs. 50 Lakhs)");
    }

    @Test
    @DisplayName("Consumer loss between 50 Lakhs and 2 Crores routes to State Commission (SCDRC)")
    void routesToStateCommission() {
        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                null,
                null,
                List.of(),
                List.of(),
                7500000.0 // 75 Lakhs
        );

        ResolvedForum forum = forumResolver.resolve(facts, "Delhi");

        assertThat(forum.forumName()).contains("State Consumer Disputes Redressal Commission (SCDRC) (Delhi)");
        assertThat(forum.filingMethod()).contains("Section 47 Consumer Complaint");
        assertThat(forum.jurisdictionReason()).contains("State Commission pecuniary limit (Rs. 50 Lakhs to Rs. 2 Crores)");
    }

    @Test
    @DisplayName("Consumer loss > 2 Crores routes to National Commission (NCDRC), New Delhi")
    void routesToNationalCommission() {
        ExtractedFacts facts = new ExtractedFacts(
                LegalDomain.CONSUMER_RIGHTS,
                null,
                null,
                List.of(),
                List.of(),
                25000000.0 // 2.5 Crores
        );

        ResolvedForum forum = forumResolver.resolve(facts, "Chennai");

        assertThat(forum.forumName()).contains("National Consumer Disputes Redressal Commission (NCDRC), New Delhi");
        assertThat(forum.filingMethod()).contains("Section 58 Consumer Complaint");
        assertThat(forum.jurisdictionReason()).contains("Claim of Rs. 2.5E7 exceeds Rs. 2 Crores");
    }

    @Test
    @DisplayName("Null facts or domain resolves to default fallback")
    void fallbackOnNullFacts() {
        ResolvedForum forum1 = forumResolver.resolve(null, null);
        assertThat(forum1.forumName()).isEqualTo("Appropriate District Commission / Court");

        ExtractedFacts nullDomainFacts = new ExtractedFacts(null, null, null, Collections.emptyList(), Collections.emptyList(), 0.0);
        ResolvedForum forum2 = forumResolver.resolve(nullDomainFacts, null);
        assertThat(forum2.forumName()).isEqualTo("Appropriate District Commission / Court");
    }
}
