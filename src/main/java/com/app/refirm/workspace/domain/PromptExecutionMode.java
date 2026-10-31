package com.app.refirm.workspace.domain;

/**
 * Execution mode for prompt submission in Refirm workspaces.
 * In AUTO mode, the safe router executes Triage, Query, Research, or Arguments,
 * and NEVER auto-triggers an expensive 3,000-word Draft without explicit user directive.
 */
public enum PromptExecutionMode {
    AUTO,
    DRAFT,
    RESEARCH,
    ARGUMENTS,
    QUERY,
    REVIEW
}
