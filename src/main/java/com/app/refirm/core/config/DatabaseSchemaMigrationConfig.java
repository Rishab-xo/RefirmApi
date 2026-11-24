package com.app.refirm.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Executes idempotent pre-Hibernate DDL schema updates.
 * Guarantees that columns like is_temporary exist with appropriate defaults
 * on existing tables with data before Hibernate schema migrator runs.
 * Also provisions the 2-Table PostgreSQL precedent storage (legal_precedent_index & legal_precedent_okf_content),
 * GIN indexes, and the weighted tsvector full-text search trigger.
 */
@Configuration
public class DatabaseSchemaMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaMigrationConfig.class);

    public DatabaseSchemaMigrationConfig(DataSource dataSource) {
        runPreHibernateMigrations(dataSource);
    }

    private void runPreHibernateMigrations(DataSource dataSource) {
        log.info("Running pre-Hibernate database schema migration check...");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Ensure legal_workspaces.is_temporary exists with default false so NOT NULL never fails on existing rows
            stmt.execute("ALTER TABLE IF EXISTS legal_workspaces ADD COLUMN IF NOT EXISTS is_temporary BOOLEAN NOT NULL DEFAULT FALSE");

            // 2. Table 1: Hot Search & AI Grounding Index
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS legal_precedent_index (
                    authority_id VARCHAR(128) PRIMARY KEY,
                    canonical_dedup_key VARCHAR(255) UNIQUE NOT NULL,
                    parquet_doc_id VARCHAR(192),
                    parquet_shard VARCHAR(128),
                    enrichment_status VARCHAR(32) NOT NULL DEFAULT 'CATALOG_STUB',
                    case_title TEXT NOT NULL,
                    court_code VARCHAR(64) NOT NULL DEFAULT 'SC',
                    court_name VARCHAR(128) NOT NULL,
                    court_tier_weight INT NOT NULL DEFAULT 10,
                    judgment_date VARCHAR(32),
                    decision_year INT NOT NULL DEFAULT 0,
                    primary_citation VARCHAR(160) NOT NULL,
                    equivalent_citations TEXT[] NOT NULL DEFAULT '{}',
                    primary_domain VARCHAR(64) NOT NULL,
                    secondary_domains TEXT[] NOT NULL DEFAULT '{}',
                    act_section_tuples TEXT[] NOT NULL DEFAULT '{}',
                    topic_slugs TEXT[] NOT NULL DEFAULT '{}',
                    issue_tags TEXT[] NOT NULL DEFAULT '{}',
                    supporting_posture VARCHAR(32) NOT NULL DEFAULT 'BOTH_SIDES',
                    disposal_nature VARCHAR(64),
                    two_line_ratio TEXT NOT NULL,
                    legal_propositions TEXT[] NOT NULL DEFAULT '{}',
                    trigger_keywords TEXT[] NOT NULL DEFAULT '{}',
                    irac_json JSONB,
                    search_vector tsvector,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
            """);

            // 3. Table 2: Cold/Lazy Split-Reader Full Verbatim OKF Content
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS legal_precedent_okf_content (
                    authority_id VARCHAR(128) PRIMARY KEY REFERENCES legal_precedent_index(authority_id) ON DELETE CASCADE,
                    okf_markdown_full TEXT,
                    pages_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                    source_url TEXT,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
            """);

            // 4. Fast GIN & B-Tree Indexes on Table 1
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lpi_act_section_tuples_gin ON legal_precedent_index USING GIN (act_section_tuples)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lpi_search_vector_gin ON legal_precedent_index USING GIN (search_vector)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lpi_equiv_citations_gin ON legal_precedent_index USING GIN (equivalent_citations)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lpi_topic_slugs_gin ON legal_precedent_index USING GIN (topic_slugs)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lpi_issue_tags_gin ON legal_precedent_index USING GIN (issue_tags)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lpi_primary_domain ON legal_precedent_index (primary_domain)");

            // 5. Weighted tsvector trigger function & trigger
            stmt.execute("""
                CREATE OR REPLACE FUNCTION lpi_search_vector_update() RETURNS trigger AS $$
                BEGIN
                    NEW.search_vector :=
                        setweight(to_tsvector('english', coalesce(NEW.case_title, '') || ' ' || coalesce(NEW.primary_citation, '') || ' ' || array_to_string(NEW.legal_propositions, ' ') || ' ' || array_to_string(NEW.trigger_keywords, ' ')), 'A') ||
                        setweight(to_tsvector('english', coalesce(NEW.two_line_ratio, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(NEW.irac_json->>'issue', '') || ' ' || coalesce(NEW.irac_json->>'rule', '') || ' ' || coalesce(NEW.irac_json->>'application', '') || ' ' || coalesce(NEW.irac_json->>'conclusion', '')), 'C');
                    NEW.updated_at := NOW();
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql;
            """);

            stmt.execute("DROP TRIGGER IF EXISTS trg_lpi_search_vector_update ON legal_precedent_index");
            stmt.execute("""
                CREATE TRIGGER trg_lpi_search_vector_update
                BEFORE INSERT OR UPDATE ON legal_precedent_index
                FOR EACH ROW EXECUTE FUNCTION lpi_search_vector_update()
            """);

            log.info("Database schema migration check completed successfully.");
        } catch (Exception e) {
            log.warn("Notice during pre-Hibernate schema migration: {}", e.getMessage());
        }
    }
}
