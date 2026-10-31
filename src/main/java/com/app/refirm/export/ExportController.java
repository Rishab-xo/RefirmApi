package com.app.refirm.export;

import com.app.refirm.core.service.DocumentExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1.0/export", "/export"})
public class ExportController {

    private final PdfExportService pdfExportService;
    private final DocumentExportService documentExportService;

    public ExportController(PdfExportService pdfExportService, DocumentExportService documentExportService) {
        this.pdfExportService = pdfExportService;
        this.documentExportService = documentExportService;
    }

    public record ExportPdfRequest(String markdownBrief, String htmlContent) {
        public ExportPdfRequest(String markdownBrief) {
            this(markdownBrief, null);
        }
    }

    @PostMapping("/pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestBody ExportPdfRequest request) {
        byte[] pdfBytes;
        if (request.markdownBrief() != null && !request.markdownBrief().isBlank()) {
            pdfBytes = pdfExportService.generateLegalPdf(request.markdownBrief());
        } else if (request.htmlContent() != null && !request.htmlContent().isBlank()) {
            pdfBytes = documentExportService.generatePdfFromHtml(request.htmlContent());
        } else {
            pdfBytes = pdfExportService.generateLegalPdf("");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Refirm_Legal_Strategy.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(pdfBytes);
    }

    @PostMapping(value = "/docx", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> exportDocx(@RequestBody Map<String, String> payload) {
        String html = payload.get("htmlContent");
        byte[] docxBytes = documentExportService.generateDocxFromHtml(html != null ? html : "");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Legal_Brief.docx\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(docxBytes);
    }
}
