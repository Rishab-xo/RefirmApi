package com.app.refirm.workspace.controller.dto;

/**
 * Actionable recommendation card rendered in "What's next?" (Wireframe 1B).
 */
public record NextStepSuggestionCard(
        String id,
        String iconType,        // 'DRAFT' | 'ARGS' | 'BOOK_RESEARCH' | 'CHAT'
        String label,           // e.g. "Consumer Complaint Sec 35 (v1: Free)"
        String actionType,      // 'INITIALIZE_TEMPLATE_DRAFT' | 'OPEN_ARGUMENT_SHEET' | 'RUN_FOLLOWUP_PROMPT'
        String templateId,      // Populated when actionType === 'INITIALIZE_TEMPLATE_DRAFT'
        String followupPrompt,  // Populated when actionType === 'RUN_FOLLOWUP_PROMPT'
        String creditCostBadge  // e.g. "v1: Free (0 Credits)"
) {}
