package com.app.refirm.triage.domain;

/**
 * Represents a single fetched statutory provision pinned with parent context to prevent context loss.
 *
 * @param actName       Full formal title of the Act (e.g., "Consumer Protection Act, 2019")
 * @param sectionNumber Explicit Section / Sub-clause identifier (e.g., "Section 2(11)")
 * @param type          Functional statutory classification
 * @param parentContext Surrounding legislative chapter, part, or statutory heading
 * @param operativeText Verbatim operative statutory text
 */
public record StatutePin(
        String actName,
        String sectionNumber,
        StatuteType type,
        String parentContext,
        String operativeText
) {
    public StatutePin(String actName, String sectionNumber, String operativeText) {
        this(actName, sectionNumber, StatuteType.OPERATIVE, "", operativeText);
    }

    public String markdownContent() {
        return operativeText != null ? operativeText : "";
    }
}
