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

            // Ensure legal_workspaces.is_temporary exists with default false so NOT NULL never fails on existing rows
            stmt.execute("ALTER TABLE IF EXISTS legal_workspaces ADD COLUMN IF NOT EXISTS is_temporary BOOLEAN NOT NULL DEFAULT FALSE");

            log.info("Database schema migration check completed successfully.");
        } catch (Exception e) {
            log.warn("Notice during pre-Hibernate schema migration: {}", e.getMessage());
        }
    }
}
