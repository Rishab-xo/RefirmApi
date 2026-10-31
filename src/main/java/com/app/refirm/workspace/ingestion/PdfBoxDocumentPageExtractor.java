package com.app.refirm.workspace.ingestion;

import com.app.refirm.workspace.domain.ExtractedPage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard page-by-page extractor using Apache PDFBox for PDFs,
 * with native fallback for markdown and plain text files.
 */
@Component
public class PdfBoxDocumentPageExtractor implements DocumentPageExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxDocumentPageExtractor.class);

    private static final int TEXT_PAGE_CHAR_LIMIT = 3000;

    @Override
    public List<ExtractedPage> extractPages(String fileName, byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            return List.of();
        }

        String name = fileName != null ? fileName.toLowerCase() : "";

        if (name.endsWith(".pdf")) {
            return extractPdfPages(fileBytes);
        } else {
            return extractTextPages(fileBytes);
        }
    }

    private List<ExtractedPage> extractPdfPages(byte[] fileBytes) {
        List<ExtractedPage> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(fileBytes))) {
            int total = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String raw = stripper.getText(doc);
                String normalized = normalizeText(raw);
                pages.add(new ExtractedPage(p, normalized));
            }
        } catch (Exception e) {
            log.warn("Failed to extract pages using PDFBox, falling back to text stream: {}", e.getMessage());
            return extractTextPages(fileBytes);
        }
        return pages;
    }

    private List<ExtractedPage> extractTextPages(byte[] fileBytes) {
        String fullText = new String(fileBytes, StandardCharsets.UTF_8);
        List<ExtractedPage> pages = new ArrayList<>();

        if (fullText.contains("---PAGE---") || fullText.contains("---page---")) {
            String[] splits = fullText.split("(?i)---page---");
            int pageNum = 1;
            for (String split : splits) {
                String clean = normalizeText(split);
                if (!clean.isBlank() || splits.length == 1) {
                    pages.add(new ExtractedPage(pageNum++, clean));
                }
            }
        } else if (fullText.length() > TEXT_PAGE_CHAR_LIMIT) {
            int pageNum = 1;
            int start = 0;
            while (start < fullText.length()) {
                int end = Math.min(start + TEXT_PAGE_CHAR_LIMIT, fullText.length());
                String chunk = fullText.substring(start, end);
                pages.add(new ExtractedPage(pageNum++, normalizeText(chunk)));
                start = end;
            }
        } else {
            pages.add(new ExtractedPage(1, normalizeText(fullText)));
        }

        return pages;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("(?m)^[ \\t]+$", "")
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
