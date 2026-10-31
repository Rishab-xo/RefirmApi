package com.app.refirm.triage.domain;

/**
 * Deterministically resolved dispute resolution forum and jurisdictional gateway.
 *
 * @param forumName          Designated statutory authority or tribunal (e.g., "District Consumer Disputes Redressal Commission")
 * @param filingMethod       Digital or physical filing mechanism (e.g., "e-Daakhil", "RERA Portal")
 * @param jurisdictionReason Pecuniary and territorial jurisdictional rationale
 */
public record ResolvedForum(
        String forumName,
        String filingMethod,
        String jurisdictionReason
) {}
