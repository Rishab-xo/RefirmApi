package com.app.refirm.workspace.ingestion;

import com.app.refirm.workspace.domain.ExtractedPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Quality gate validating extracted text against minimum volume and alphanumeric density thresholds.
 * Rejects corrupt scans, blurry OCR output, or unreadable symbol noise.
 */
@Component
public class OcrQualityGate {

    public static final int MIN_ALPHANUMERIC_CHARS_PER_DOC = 40;
    public static final double MIN_ALPHANUMERIC_RATIO = 0.35;

    public record QualityCheckResult(
            boolean passed,
            String failureReason,
            List<ExtractedPage> sanitizedPages
    ) {}

    /**
     * Evaluates the text quality across extracted document pages.
     */
    public QualityCheckResult evaluate(List<ExtractedPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return new QualityCheckResult(false, "No pages extracted from document", List.of());
        }

        int totalAlphanumeric = 0;
        int totalPrintable = 0;

        for (ExtractedPage p : pages) {
            String text = p.rawText();
            if (text == null) continue;
            for (char c : text.toCharArray()) {
                if (Character.isLetterOrDigit(c)) {
                    totalAlphanumeric++;
                    totalPrintable++;
                } else if (!Character.isWhitespace(c) && !Character.isISOControl(c)) {
                    totalPrintable++;
                }
            }
        }

        if (totalAlphanumeric < MIN_ALPHANUMERIC_CHARS_PER_DOC) {
            return new QualityCheckResult(
                    false,
                    "Document contains insufficient text (< 40 alphanumeric characters)",
                    pages
            );
        }

        double ratio = (double) totalAlphanumeric / (totalPrintable > 0 ? totalPrintable : 1);
        if (ratio < MIN_ALPHANUMERIC_RATIO) {
            return new QualityCheckResult(
                    false,
                    String.format("Document text quality is too low (excessive noise or corrupted scan, ratio %.2f < %.2f)",
                            ratio, MIN_ALPHANUMERIC_RATIO),
                    pages
            );
        }

        List<ExtractedPage> sanitized = new ArrayList<>(pages.size());
        for (ExtractedPage p : pages) {
            String clean = sanitizePageText(p.rawText());
            sanitized.add(new ExtractedPage(p.pageNumber(), clean));
        }

        return new QualityCheckResult(true, null, sanitized);
    }

    private String sanitizePageText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
    }
}
