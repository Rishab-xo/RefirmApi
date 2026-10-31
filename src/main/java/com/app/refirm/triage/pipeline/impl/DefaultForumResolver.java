package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.triage.domain.ResolvedForum;
import com.app.refirm.triage.pipeline.ForumResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fallback Forum Resolver that catches all unspecialized legal domains.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultForumResolver implements ForumResolver {

    @Override
    public boolean supports(LegalDomain domain) {
        return true;
    }

    @Override
    public ResolvedForum resolve(ExtractedFacts facts, String userLocation) {
        String locationSuffix = (userLocation != null && !userLocation.isBlank())
                ? " at " + userLocation.trim()
                : "";

        return new ResolvedForum(
                "Competent Civil Court / Designated Statutory Authority" + locationSuffix,
                "Designated Court Registry / Official e-Filing Portal",
                "General jurisdiction based on dispute territorial nexus and subject-matter cause of action."
        );
    }
}
