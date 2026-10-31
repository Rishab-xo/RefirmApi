package com.app.refirm.core.service;

import com.app.refirm.export.PdfExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfExportServiceTest {

    private PdfExportService pdfExportService;

    @BeforeEach
    void setUp() {
        pdfExportService = new PdfExportService();
    }

    @Test
    @DisplayName("Should generate valid non-empty PDF from standard brief markdown")
    void shouldGeneratePdfFromMarkdown() {
        String markdown = """
                ## CASE VALIDATION & DE-ESCALATION
                The consumer has suffered significant mental agony and financial harm.
                
                ## STRATEGIC MERITS, STATUTES & PRECEDENTS
                Under **Section 2(7)(ii)** of the Consumer Protection Act, 2019, the user has locus standi.
                *Charan Singh v. Healing Touch Hospital* (2000) justifies compensation.
                
                ## CONCRETE FINANCIAL STRATEGY
                - Actual Damages: ₹45,000
                - Conservative Bracket: ₹20,000 to ₹50,000
                - Deterrent Bracket: ₹1,00,000 to ₹2,50,000
                
                ## FORENSIC EVIDENCE PROTOCOL
                - Demand Section 63 certificate under Bharatiya Sakshya Adhiniyam, 2023.
                
                ## EXECUTABLE ACTION PLAN & ARTIFACTS
                1. Lock Down Evidence.
                2. Chargeback Script: "Dispute transaction of ₹45,000 for Defective Services."
                """;

        byte[] pdfBytes = pdfExportService.generatePdfFromMarkdown(markdown);

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(100);
        // PDF magic bytes: %PDF-
        assertThat(new String(pdfBytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("Should strip markdown code fences without preformatted wrapping")
    void shouldStripCodeFences() {
        String markdownWithFence = """
                ```markdown
                ## CASE VALIDATION & DE-ESCALATION
                Validating consumer distress.
                ```
                """;

        byte[] pdfBytes = pdfExportService.generatePdfFromMarkdown(markdownWithFence);

        assertThat(pdfBytes).isNotNull();
        assertThat(new String(pdfBytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("Should safely handle raw ampersands without SAX XML parse exceptions")
    void shouldHandleRawAmpersands() {
        String markdownWithAmpersand = """
                ## STRATEGIC MERITS & STATUTES
                Dispute involving R & D Ltd. & Flipkart & Amazon India.
                Terms & Conditions violated.
                """;

        byte[] pdfBytes = pdfExportService.generatePdfFromMarkdown(markdownWithAmpersand);

        assertThat(pdfBytes).isNotNull();
        assertThat(new String(pdfBytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("Should reject null or blank markdown")
    void shouldRejectBlankMarkdown() {
        assertThatThrownBy(() -> pdfExportService.generatePdfFromMarkdown(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> pdfExportService.generatePdfFromMarkdown("   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> pdfExportService.generatePdfFromMarkdown(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
