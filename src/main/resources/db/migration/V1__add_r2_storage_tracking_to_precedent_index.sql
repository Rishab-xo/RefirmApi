-- Migration V1: Add R2 storage tracking & parser_version to legal_precedent_index
ALTER TABLE IF EXISTS legal_precedent_index
    ADD COLUMN IF NOT EXISTS r2_object_key TEXT,
    ADD COLUMN IF NOT EXISTS r2_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS has_full_text BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS parser_version VARCHAR(32) DEFAULT 'sc-v1.0';

CREATE INDEX IF NOT EXISTS idx_lpi_court_parser_version
    ON legal_precedent_index (court_code, parser_version);
