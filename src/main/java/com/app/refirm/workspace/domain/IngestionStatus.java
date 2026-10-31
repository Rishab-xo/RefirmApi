package com.app.refirm.workspace.domain;

/**
 * Processing status for page-level OCR, markdown conversion, and OKF manifest extraction.
 */
public enum IngestionStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED_LOW_QUALITY
}
