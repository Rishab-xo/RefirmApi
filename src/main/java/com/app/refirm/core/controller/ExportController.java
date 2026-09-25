package com.app.refirm.core.controller;

import com.app.refirm.core.service.DocumentExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/export")
public class ExportController {

    private final DocumentExportService documentExportService;

    public ExportController(DocumentExportService documentExportService) {
        this.documentExportService = documentExportService;
    }

    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@RequestBody Map<String, String> payload) {
        String html = payload.get("htmlContent");
        byte[] pdfBytes = documentExportService.generatePdfFromHtml(html);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Legal_Brief.pdf\"")
                .body(pdfBytes);
    }

    @PostMapping(value = "/docx", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> exportDocx(@RequestBody Map<String, String> payload) {
        String html = payload.get("htmlContent");
        byte[] docxBytes = documentExportService.generateDocxFromHtml(html);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Legal_Brief.docx\"")
                .body(docxBytes);
    }
}