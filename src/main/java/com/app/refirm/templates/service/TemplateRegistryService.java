package com.app.refirm.templates.service;

import com.app.refirm.templates.domain.TemplateCategory;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateStage;
import com.app.refirm.triage.domain.LegalDomain;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Registry holding the unmodifiable catalog of master legal templates.
 * Loads the master templates from classpath and modular directory bundles on startup.
 */
@Service
public class TemplateRegistryService {

    private static final Logger log = LoggerFactory.getLogger(TemplateRegistryService.class);

    private final ObjectMapper objectMapper;

    @Value("classpath:okf-templates/master_templates.json")
    private Resource templatesResource;

    private Map<String, TemplateManifest> templatesById = Collections.emptyMap();
    private List<TemplateManifest> templatesList = Collections.emptyList();

    @Autowired
    public TemplateRegistryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TemplateRegistryService(ObjectMapper objectMapper, Resource templatesResource) {
        this.objectMapper = objectMapper;
        this.templatesResource = templatesResource;
        loadTemplates();
    }

    @PostConstruct
    public void loadTemplates() {
        Map<String, TemplateManifest> registry = new LinkedHashMap<>();

        // 1. Maintain full support for loading master_templates.json
        try {
            InputStream is = (templatesResource != null && templatesResource.exists())
                    ? templatesResource.getInputStream()
                    : getClass().getClassLoader().getResourceAsStream("okf-templates/master_templates.json");

            if (is != null) {
                try (is) {
                    List<TemplateManifest> list = objectMapper.readValue(is, new TypeReference<List<TemplateManifest>>() {});
                    for (TemplateManifest m : list) {
                        registry.put(m.templateId(), m);
                    }
                    log.info("Loaded {} master hybrid OKF templates from master_templates.json.", list.size());
                }
            } else {
                log.warn("master_templates.json not found on classpath.");
            }
        } catch (IOException e) {
            log.error("Failed to load master_templates.json: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to load master legal templates", e);
        }

        // 2. Production JAR-safe modular template bundle scanning
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Set<Resource> resources = new LinkedHashSet<>();
            try {
                Collections.addAll(resources, resolver.getResources("classpath*:okf-templates/*/*/manifest.json"));
            } catch (Exception ignored) {}
            try {
                Collections.addAll(resources, resolver.getResources("classpath*:okf-templates/**/manifest.json"));
            } catch (Exception ignored) {}

            for (Resource res : resources) {
                if (res != null && res.exists() && res.isReadable()) {
                    try (InputStream in = res.getInputStream()) {
                        TemplateManifest manifest = objectMapper.readValue(in, TemplateManifest.class);
                        if (manifest.skeletonMarkdown() == null || manifest.skeletonMarkdown().isBlank()) {
                            Resource skeletonRes = res.createRelative("skeleton.md");
                            if (skeletonRes.exists() && skeletonRes.isReadable()) {
                                try (InputStream sin = skeletonRes.getInputStream()) {
                                    String skeletonText = StreamUtils.copyToString(sin, StandardCharsets.UTF_8);
                                    manifest = manifest.withSkeletonMarkdown(skeletonText);
                                }
                            }
                        }
                        registry.put(manifest.templateId(), manifest);
                        log.info("Loaded modular OKF template: {} [{}]", manifest.templateId(), manifest.title());
                    } catch (Exception ex) {
                        log.error("Failed to load modular template from {}: {}", res.getDescription(), ex.getMessage(), ex);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error scanning modular template bundles: {}", e.getMessage());
        }

        this.templatesById = Collections.unmodifiableMap(registry);
        this.templatesList = List.copyOf(registry.values());
        log.info("Total templates loaded into registry: {}", this.templatesList.size());
    }

    public List<TemplateManifest> getAllTemplates() {
        return templatesList;
    }

    public List<TemplateManifest> getByCategory(TemplateCategory category) {
        if (category == null) return templatesList;
        return templatesList.stream()
                .filter(t -> t.category() == category)
                .toList();
    }

    public List<TemplateManifest> getByDomain(LegalDomain domain) {
        if (domain == null) return templatesList;
        return templatesList.stream()
                .filter(t -> t.primaryDomain() == domain)
                .toList();
    }

    public List<TemplateManifest> getByDomainAndStage(LegalDomain domain, TemplateStage stage) {
        return templatesList.stream()
                .filter(t -> (domain == null || t.primaryDomain() == domain) &&
                             (stage == null || t.stage() == stage))
                .toList();
    }

    public Optional<TemplateManifest> findTemplate(String templateId) {
        if (templateId == null) return Optional.empty();
        return Optional.ofNullable(templatesById.get(templateId));
    }

    public TemplateManifest getTemplateOrThrow(String templateId) {
        return findTemplate(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found with ID: " + templateId));
    }
}
