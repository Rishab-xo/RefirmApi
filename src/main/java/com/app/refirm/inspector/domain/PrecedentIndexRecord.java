package com.app.refirm.inspector.domain;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.rules.model.LegalDomain;
import com.app.refirm.workspace.domain.IracBrief;

import java.util.Collections;
import java.util.List;

/**
 * Immutable domain record representing a row in Table 1 (legal_precedent_index).
 * High-speed in-memory representation for hybrid search, scoring, and AI grounding.
 */
public record PrecedentIndexRecord(
        String authorityId,
        String canonicalDedupKey,
        String parquetDocId,
        String parquetShard,
        String enrichmentStatus,
        String caseTitle,
        String courtCode,
        String courtName,
        int courtTierWeight,
        String judgmentDate,
        int decisionYear,
        String primaryCitation,
        List<String> equivalentCitations,
        LegalDomain primaryDomain,
        List<String> secondaryDomains,
        List<String> actSectionTuples,
        List<String> topicSlugs,
        List<String> issueTags,
        String supportingPosture,
        String disposalNature,
        String twoLineRatio,
        List<String> legalPropositions,
        List<String> triggerKeywords,
        IracBrief iracBrief,
        boolean hasFullText,
        String r2ObjectKey,
        String r2Url,
        String parserVersion,
        int citedByCount,
        Integer benchCount,
        int hcCitedByCount,
        String goodLawStatus,
        String overruledByAuthorityId
) {
    public PrecedentIndexRecord {
        authorityId = authorityId != null ? authorityId : "";
        canonicalDedupKey = canonicalDedupKey != null ? canonicalDedupKey : "";
        parquetDocId = parquetDocId != null ? parquetDocId : "";
        parquetShard = parquetShard != null ? parquetShard : "";
        enrichmentStatus = (enrichmentStatus != null && !enrichmentStatus.isBlank()) ? enrichmentStatus : "CATALOG_STUB";
        caseTitle = caseTitle != null ? caseTitle : "";
        courtCode = (courtCode != null && !courtCode.isBlank()) ? courtCode : "SC";
        courtName = courtName != null ? courtName : "Supreme Court of India";
        courtTierWeight = courtTierWeight > 0 ? courtTierWeight : 10;
        judgmentDate = judgmentDate != null ? judgmentDate : "";
        primaryCitation = primaryCitation != null ? primaryCitation : "";
        equivalentCitations = equivalentCitations != null ? List.copyOf(equivalentCitations) : Collections.emptyList();
        primaryDomain = primaryDomain != null ? primaryDomain : LegalDomain.UNKNOWN;
        secondaryDomains = secondaryDomains != null ? List.copyOf(secondaryDomains) : Collections.emptyList();
        actSectionTuples = actSectionTuples != null ? List.copyOf(actSectionTuples) : Collections.emptyList();
        topicSlugs = topicSlugs != null ? List.copyOf(topicSlugs) : Collections.emptyList();
        issueTags = issueTags != null ? issueTags : Collections.emptyList();
        supportingPosture = (supportingPosture != null && !supportingPosture.isBlank()) ? supportingPosture : "BOTH_SIDES";
        disposalNature = disposalNature != null ? disposalNature : "";
        twoLineRatio = twoLineRatio != null ? twoLineRatio : "";
        legalPropositions = legalPropositions != null ? List.copyOf(legalPropositions) : Collections.emptyList();
        triggerKeywords = triggerKeywords != null ? List.copyOf(triggerKeywords) : Collections.emptyList();
        iracBrief = iracBrief != null ? iracBrief : new IracBrief("", List.of(), List.of(), "", "");
        r2ObjectKey = r2ObjectKey != null ? r2ObjectKey : "";
        r2Url = r2Url != null ? r2Url : "";
        parserVersion = (parserVersion != null && !parserVersion.isBlank()) ? parserVersion : "sc-v1.0";
        citedByCount = Math.max(citedByCount, 0);
        hcCitedByCount = Math.max(hcCitedByCount, 0);
        goodLawStatus = (goodLawStatus != null && !goodLawStatus.isBlank()) ? goodLawStatus : "GOOD_LAW";
    }

    public PrecedentIndexRecord(
            String authorityId,
            String canonicalDedupKey,
            String parquetDocId,
            String parquetShard,
            String enrichmentStatus,
            String caseTitle,
            String courtCode,
            String courtName,
            int courtTierWeight,
            String judgmentDate,
            int decisionYear,
            String primaryCitation,
            List<String> equivalentCitations,
            LegalDomain primaryDomain,
            List<String> secondaryDomains,
            List<String> actSectionTuples,
            List<String> topicSlugs,
            List<String> issueTags,
            String supportingPosture,
            String disposalNature,
            String twoLineRatio,
            List<String> legalPropositions,
            List<String> triggerKeywords,
            IracBrief iracBrief,
            boolean hasFullText,
            String r2ObjectKey,
            String r2Url,
            String parserVersion,
            int citedByCount
    ) {
        this(authorityId, canonicalDedupKey, parquetDocId, parquetShard, enrichmentStatus,
             caseTitle, courtCode, courtName, courtTierWeight, judgmentDate, decisionYear,
             primaryCitation, equivalentCitations, primaryDomain, secondaryDomains,
             actSectionTuples, topicSlugs, issueTags, supportingPosture, disposalNature,
             twoLineRatio, legalPropositions, triggerKeywords, iracBrief,
             hasFullText, r2ObjectKey, r2Url, parserVersion, citedByCount,
             null, 0, "GOOD_LAW", null);
    }

    public PrecedentIndexRecord(
            String authorityId,
            String canonicalDedupKey,
            String parquetDocId,
            String parquetShard,
            String enrichmentStatus,
            String caseTitle,
            String courtCode,
            String courtName,
            int courtTierWeight,
            String judgmentDate,
            int decisionYear,
            String primaryCitation,
            List<String> equivalentCitations,
            LegalDomain primaryDomain,
            List<String> secondaryDomains,
            List<String> actSectionTuples,
            List<String> topicSlugs,
            List<String> issueTags,
            String supportingPosture,
            String disposalNature,
            String twoLineRatio,
            List<String> legalPropositions,
            List<String> triggerKeywords,
            IracBrief iracBrief,
            boolean hasFullText,
            String r2ObjectKey,
            String r2Url,
            String parserVersion
    ) {
        this(authorityId, canonicalDedupKey, parquetDocId, parquetShard, enrichmentStatus,
             caseTitle, courtCode, courtName, courtTierWeight, judgmentDate, decisionYear,
             primaryCitation, equivalentCitations, primaryDomain, secondaryDomains,
             actSectionTuples, topicSlugs, issueTags, supportingPosture, disposalNature,
             twoLineRatio, legalPropositions, triggerKeywords, iracBrief,
             hasFullText, r2ObjectKey, r2Url, parserVersion, 0);
    }

    public PrecedentIndexRecord(
            String authorityId,
            String canonicalDedupKey,
            String parquetDocId,
            String parquetShard,
            String enrichmentStatus,
            String caseTitle,
            String courtCode,
            String courtName,
            int courtTierWeight,
            String judgmentDate,
            int decisionYear,
            String primaryCitation,
            List<String> equivalentCitations,
            LegalDomain primaryDomain,
            List<String> secondaryDomains,
            List<String> actSectionTuples,
            List<String> topicSlugs,
            List<String> issueTags,
            String supportingPosture,
            String disposalNature,
            String twoLineRatio,
            List<String> legalPropositions,
            List<String> triggerKeywords,
            IracBrief iracBrief,
            boolean hasFullText,
            String r2ObjectKey,
            String r2Url
    ) {
        this(authorityId, canonicalDedupKey, parquetDocId, parquetShard, enrichmentStatus,
             caseTitle, courtCode, courtName, courtTierWeight, judgmentDate, decisionYear,
             primaryCitation, equivalentCitations, primaryDomain, secondaryDomains,
             actSectionTuples, topicSlugs, issueTags, supportingPosture, disposalNature,
             twoLineRatio, legalPropositions, triggerKeywords, iracBrief,
             hasFullText, r2ObjectKey, r2Url, "sc-v1.0", 0);
    }

    public PrecedentIndexRecord(
            String authorityId,
            String canonicalDedupKey,
            String parquetDocId,
            String parquetShard,
            String enrichmentStatus,
            String caseTitle,
            String courtCode,
            String courtName,
            int courtTierWeight,
            String judgmentDate,
            int decisionYear,
            String primaryCitation,
            List<String> equivalentCitations,
            LegalDomain primaryDomain,
            List<String> secondaryDomains,
            List<String> actSectionTuples,
            List<String> topicSlugs,
            List<String> issueTags,
            String supportingPosture,
            String disposalNature,
            String twoLineRatio,
            List<String> legalPropositions,
            List<String> triggerKeywords,
            IracBrief iracBrief
    ) {
        this(authorityId, canonicalDedupKey, parquetDocId, parquetShard, enrichmentStatus,
             caseTitle, courtCode, courtName, courtTierWeight, judgmentDate, decisionYear,
             primaryCitation, equivalentCitations, primaryDomain, secondaryDomains,
             actSectionTuples, topicSlugs, issueTags, supportingPosture, disposalNature,
             twoLineRatio, legalPropositions, triggerKeywords, iracBrief, false, "", "", "sc-v1.0", 0);
    }

    /**
     * Converts PrecedentIndexRecord into CorpusPrecedentEntry for 100% backward compatibility
     * with existing InspectorService, PrecedentAuthorityProvider, and AuthorityDrawerService.
     */
    public CorpusPrecedentEntry toCorpusPrecedentEntry(List<DrawerPage> lazyPages) {
        return new CorpusPrecedentEntry(
                authorityId,
                primaryDomain,
                secondaryDomains,
                triggerKeywords,
                caseTitle,
                courtName,
                judgmentDate,
                primaryCitation,
                equivalentCitations,
                twoLineRatio,
                legalPropositions,
                iracBrief,
                lazyPages != null ? lazyPages : List.of()
        );
    }

    public CorpusPrecedentEntry toCorpusPrecedentEntry() {
        return toCorpusPrecedentEntry(List.of());
    }
}
