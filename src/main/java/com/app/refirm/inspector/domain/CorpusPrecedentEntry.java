package com.app.refirm.inspector.domain;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.workspace.domain.IracBrief;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * Immutable record representing a verified precedent loaded dynamically from the classpath
 * precedent catalog (okf-precedents/verified_precedents_catalog.json).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorpusPrecedentEntry(
        String authorityId,
        LegalDomain primaryDomain,
        List<String> secondaryDomains,
        List<String> triggerKeywords,
        String caseTitle,
        String courtName,
        String judgmentDate,
        String primaryCitation,
        List<String> equivalentCitations,
        String summary,
        List<String> keyPropositions,
        IracBrief iracBrief,
        List<DrawerPage> pages
) {
    public CorpusPrecedentEntry {
        authorityId = authorityId != null ? authorityId : "";
        primaryDomain = primaryDomain != null ? primaryDomain : LegalDomain.UNKNOWN;
        secondaryDomains = secondaryDomains != null ? List.copyOf(secondaryDomains) : Collections.emptyList();
        triggerKeywords = triggerKeywords != null ? List.copyOf(triggerKeywords) : Collections.emptyList();
        caseTitle = caseTitle != null ? caseTitle : "";
        courtName = courtName != null ? courtName : "Supreme Court of India";
        judgmentDate = judgmentDate != null ? judgmentDate : "";
        primaryCitation = primaryCitation != null ? primaryCitation : "";
        equivalentCitations = equivalentCitations != null ? List.copyOf(equivalentCitations) : Collections.emptyList();
        summary = summary != null ? summary : "";
        keyPropositions = keyPropositions != null ? List.copyOf(keyPropositions) : Collections.emptyList();
        iracBrief = iracBrief != null ? iracBrief : new IracBrief("", List.of(), List.of(), "", "");
        pages = pages != null ? List.copyOf(pages) : Collections.emptyList();
    }
}
