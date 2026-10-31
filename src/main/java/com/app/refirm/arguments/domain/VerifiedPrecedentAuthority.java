package com.app.refirm.arguments.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Immutable representation of a verified precedent authority derived from uploaded case judgments
 * or verified legal corpora.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerifiedPrecedentAuthority(
        String authorityId,
        String caseTitleAndCitation,
        List<String> propositionsSupported,
        String ratioSummary,
        String sourceType
) {
    public VerifiedPrecedentAuthority {
        authorityId = authorityId != null ? authorityId : "";
        caseTitleAndCitation = caseTitleAndCitation != null ? caseTitleAndCitation : "";
        propositionsSupported = propositionsSupported != null ? List.copyOf(propositionsSupported) : List.of();
        ratioSummary = ratioSummary != null ? ratioSummary : "";
        sourceType = sourceType != null ? sourceType : "VERIFIED_CORPUS";
    }
}
