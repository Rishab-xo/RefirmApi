package com.app.refirm.templates.service;

import com.app.refirm.templates.domain.TemplateAiBlockSpec;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateParticular;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic template splicing engine.
 * Substitutes scalar facts and AI narrative blocks into the template skeleton.
 * Strictly uses literal String.replace() to prevent regex crashes on currency symbols ($ or ₹).
 */
@Component
public class TemplateRenderer {

    public record RenderResult(
            String renderedMarkdown,
            List<String> missingParticularKeys
    ) {}

    /**
     * Renders a complete document by substituting scalar particulars and AI blocks
     * into the manifest's immutable skeleton markdown.
     */
    public RenderResult render(
            TemplateManifest manifest,
            Map<String, String> filledParticulars,
            Map<String, String> generatedAiBlocks
    ) {
        if (manifest == null || manifest.skeletonMarkdown() == null) {
            return new RenderResult("", List.of());
        }

        String rendered = manifest.skeletonMarkdown();
        List<String> missingKeys = new ArrayList<>();

        // 1. Splice Particulars
        if (manifest.particulars() != null) {
            for (TemplateParticular particular : manifest.particulars()) {
                String token = "{{FACT:" + particular.key() + "}}";
                String value = filledParticulars != null ? filledParticulars.get(particular.key()) : null;

                if (value != null && !value.trim().isEmpty()) {
                    rendered = rendered.replace(token, value.trim());
                } else {
                    rendered = rendered.replace(token, "[FILL: " + particular.label() + "]");
                    missingKeys.add(particular.key());
                }
            }
        }

        // 2. Splice AI Narrative Blocks
        if (manifest.aiBlocks() != null) {
            for (TemplateAiBlockSpec block : manifest.aiBlocks()) {
                String token = "{{AI_BLOCK:" + block.blockKey() + "}}";
                String blockContent = generatedAiBlocks != null ? generatedAiBlocks.get(block.blockKey()) : null;

                if (blockContent != null && !blockContent.trim().isEmpty()) {
                    rendered = rendered.replace(token, blockContent.trim());
                } else {
                    rendered = rendered.replace(
                            token,
                            "[AI DRAFT BLOCK: " + block.title() + " — Click 'Auto-Draft Narrative Blocks' to populate]"
                    );
                }
            }
        }

        // 3. Safety sweep for any orphaned/undeclared {{FACT:key}} or {{AI_BLOCK:key}} placeholders
        rendered = sweepOrphanedPlaceholders(rendered);

        return new RenderResult(rendered, missingKeys);
    }

    /**
     * Deterministic literal scan and replacement of any undeclared {{FACT:...}} or {{AI_BLOCK:...}}
     * placeholders into user-safe placeholders without regex or replaceAll.
     */
    private String sweepOrphanedPlaceholders(String text) {
        if (text == null || (!text.contains("{{FACT:") && !text.contains("{{AI_BLOCK:"))) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = text.length();
        while (i < len) {
            if (text.startsWith("{{FACT:", i)) {
                int close = text.indexOf("}}", i + 7);
                if (close != -1) {
                    String key = text.substring(i + 7, close).trim();
                    sb.append("[FILL: ").append(key).append("]");
                    i = close + 2;
                    continue;
                }
            } else if (text.startsWith("{{AI_BLOCK:", i)) {
                int close = text.indexOf("}}", i + 11);
                if (close != -1) {
                    String key = text.substring(i + 11, close).trim();
                    sb.append("[AI DRAFT BLOCK: ").append(key).append("]");
                    i = close + 2;
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }
}
