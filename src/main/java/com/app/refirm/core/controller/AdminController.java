package com.app.refirm.core.controller;

import com.app.refirm.core.service.LegalIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final LegalIngestionService ingestionService;

    public AdminController(LegalIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest-laws")
    public ResponseEntity<String> triggerIngestion() {
        String result = ingestionService.ingestStatutes();
        return ResponseEntity.ok(result);
    }
}