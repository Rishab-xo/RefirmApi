package com.app.refirm.inspector.service;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Fallback implementation of PrecedentContentStore reading directly from
 * Table 2 (legal_precedent_okf_content).
 * Active when Cloudflare R2 properties are not present (offline/test profile).
 */
@Service
@ConditionalOnMissingBean(PrecedentContentStore.class)
public class PostgresFallbackPrecedentContentStore implements PrecedentContentStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresFallbackPrecedentContentStore.class);

    private final PrecedentIndexRepository precedentIndexRepository;

    public PostgresFallbackPrecedentContentStore(PrecedentIndexRepository precedentIndexRepository) {
        this.precedentIndexRepository = precedentIndexRepository;
    }

    @Override
    public Optional<List<DrawerPage>> loadPages(String authorityId, @Nullable String r2ObjectKey) {
        if (precedentIndexRepository == null || authorityId == null || authorityId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<DrawerPage> pages = precedentIndexRepository.findDrawerPagesByAuthorityId(authorityId);
            if (pages != null && !pages.isEmpty()) {
                return Optional.of(pages);
            }
        } catch (Exception e) {
            log.error("PostgresFallbackPrecedentContentStore failed for authorityId {}: {}", authorityId, e.getMessage());
        }
        return Optional.empty();
    }
}
