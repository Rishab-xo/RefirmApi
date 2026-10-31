package com.app.refirm.workspace.util;

/**
 * Deterministic utility to sanitize LLM responses before JSON deserialization or Markdown rendering.
 * Strips markdown code fences (```json ... ``` or ``` ... ```) and extracts outermost JSON structures
 * ({...} or [...]) preventing JsonParseException from leading/trailing conversational prose.
 */
public final class JsonFenceSanitizer {

    private JsonFenceSanitizer() {}

    /**
     * Sanitizes a raw LLM output string to extract a clean JSON payload.
     * 1. Strips markdown fences (```json or ```).
     * 2. Extracts the outermost {...} object or [...] array if prose surrounds it.
     */
    public static String sanitizeJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();

        // If enclosed in markdown fences, extract content between them
        if (text.contains("```")) {
            int firstFence = text.indexOf("```");
            int lastFence = text.lastIndexOf("```");
            if (firstFence != -1 && lastFence > firstFence) {
                String inner = text.substring(firstFence + 3, lastFence).trim();
                if (inner.toLowerCase().startsWith("json")) {
                    inner = inner.substring(4).trim();
                }
                text = inner;
            }
        }

        // Extract outermost JSON block {...} or [...]
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        int firstBracket = text.indexOf('[');
        int lastBracket = text.lastIndexOf(']');

        boolean hasBraces = firstBrace != -1 && lastBrace > firstBrace;
        boolean hasBrackets = firstBracket != -1 && lastBracket > firstBracket;

        if (hasBraces && hasBrackets) {
            if (firstBrace < firstBracket && lastBrace > lastBracket) {
                return text.substring(firstBrace, lastBrace + 1).trim();
            } else if (firstBracket < firstBrace && lastBracket > lastBrace) {
                return text.substring(firstBracket, lastBracket + 1).trim();
            }
        }
        if (hasBraces) {
            return text.substring(firstBrace, lastBrace + 1).trim();
        }
        if (hasBrackets) {
            return text.substring(firstBracket, lastBracket + 1).trim();
        }

        return text.trim();
    }

    /**
     * Sanitizes markdown text blocks by stripping surrounding ```markdown or ``` fences.
     */
    public static String sanitizeMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```markdown")) {
            trimmed = trimmed.substring("```markdown".length()).trim();
        } else if (trimmed.startsWith("```md")) {
            trimmed = trimmed.substring("```md".length()).trim();
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3).trim();
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }
}
