package com.app.refirm.inspector.service;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * SOLID Content Store Interface for loading precedent drawer pages.
 * Decouples reading logic from underlying storage (Cloudflare R2 or PostgreSQL Table 2 fallback).
 */
public interface PrecedentContentStore {
    Optional<List<DrawerPage>> loadPages(String authorityId, @Nullable String r2ObjectKey);
}
