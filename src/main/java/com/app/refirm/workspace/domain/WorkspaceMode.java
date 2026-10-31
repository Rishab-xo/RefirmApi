package com.app.refirm.workspace.domain;

/**
 * Operational mode of a Refirm LegalWorkspace.
 * <ul>
 *   <li>{@code QUICK_CONSULT}: Fast intake / chat consult mode.</li>
 *   <li>{@code CASE_PROJECT}: Dedicated multi-feature case matter workspace.</li>
 * </ul>
 */
public enum WorkspaceMode {
    QUICK_CONSULT,
    CASE_PROJECT
}
