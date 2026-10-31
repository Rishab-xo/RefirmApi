package com.app.refirm.templates;

import com.app.refirm.templates.domain.TemplateAiBlockSpec;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateParticular;
import com.app.refirm.templates.service.TemplateFactResolver;
import com.app.refirm.templates.service.TemplateRegistryService;
import com.app.refirm.templates.service.TemplateRenderer;
import com.app.refirm.triage.domain.LegalDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Step 3.2 Automated Corpus Integrity Gate: 13-Domain Coverage, Bijectivity & 2026 Statutory Compliance")
class TemplateCorpusIntegrityTest {

    private TemplateRegistryService registryService;
    private TemplateRenderer renderer;

    private static final List<LegalDomain> ACTIVE_13_DOMAINS = List.of(
            LegalDomain.BANKING,
            LegalDomain.CIVIL_PROPERTY,
            LegalDomain.CONSTITUTIONAL,
            LegalDomain.CONSUMER,
            LegalDomain.CORPORATE,
            LegalDomain.CRIMINAL,
            LegalDomain.CYBER,
            LegalDomain.EMPLOYMENT,
            LegalDomain.ENVIRONMENTAL,
            LegalDomain.FAMILY,
            LegalDomain.IP,
            LegalDomain.TAXATION,
            LegalDomain.TENANCY
    );

    private static final List<String> BANNED_LEGACY_STRINGS = List.of(
            "Companies Act, 1956",
            "Consumer Protection Act, 1986",
            "District Consumer Forum",
            "Evidence Act, 1872",
            "65B",
            "Urban Land (Ceiling",
            "Idea Cellular",
            "ABC Bank"
    );

    private static final Pattern LEGACY_YEAR_PATTERN = Pattern.compile("of 19\\d\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern FACT_TOKEN_PATTERN = Pattern.compile("\\{\\{FACT:([^}]+)\\}\\}");
    private static final Pattern AI_BLOCK_TOKEN_PATTERN = Pattern.compile("\\{\\{AI_BLOCK:([^}]+)\\}\\}");

    @BeforeEach
    void setUp() {
        registryService = new TemplateRegistryService(new ObjectMapper());
        registryService.loadTemplates();
        renderer = new TemplateRenderer();
    }

    @Test
    @DisplayName("Gate 1: Total template count >= 40 and all 13 substantive domains have >= 3 templates")
    void testTotalTemplateCountAnd13DomainCoverage() {
        List<TemplateManifest> allTemplates = registryService.getAllTemplates();
        assertNotNull(allTemplates, "Registry templates list should not be null");
        assertTrue(allTemplates.size() >= 40,
                () -> "Expected >= 40 templates in registry, found: " + allTemplates.size());

        Map<LegalDomain, Long> domainCounts = allTemplates.stream()
                .collect(Collectors.groupingBy(TemplateManifest::primaryDomain, Collectors.counting()));

        for (LegalDomain domain : ACTIVE_13_DOMAINS) {
            long count = domainCounts.getOrDefault(domain, 0L);
            assertTrue(count >= 3,
                    () -> "Substantive legal domain " + domain.name() + " must have >= 3 templates, but found: " + count);
        }
    }

    @Test
    @DisplayName("Gate 2: Bijective 1-to-1 placeholder integrity across skeleton and manifest for all templates")
    void testBijectivePlaceholderIntegrityAcrossAllTemplates() {
        List<TemplateManifest> allTemplates = registryService.getAllTemplates();

        for (TemplateManifest manifest : allTemplates) {
            String tid = manifest.templateId();
            String skeleton = manifest.skeletonMarkdown();
            assertNotNull(skeleton, () -> "Template " + tid + " must have non-null skeletonMarkdown");
            assertFalse(skeleton.isBlank(), () -> "Template " + tid + " must have non-blank skeletonMarkdown");

            // 1. Verify FACT placeholders
            Set<String> skeletonFactKeys = new HashSet<>();
            Matcher factMatcher = FACT_TOKEN_PATTERN.matcher(skeleton);
            while (factMatcher.find()) {
                skeletonFactKeys.add(factMatcher.group(1));
            }

            Set<String> manifestFactKeys = manifest.particulars().stream()
                    .map(TemplateParticular::key)
                    .collect(Collectors.toSet());

            Set<String> missingInManifest = new HashSet<>(skeletonFactKeys);
            missingInManifest.removeAll(manifestFactKeys);
            assertTrue(missingInManifest.isEmpty(),
                    () -> "Template " + tid + " has {{FACT:...}} tokens in skeleton not declared in manifest particulars: " + missingInManifest);

            Set<String> unusedInSkeleton = new HashSet<>(manifestFactKeys);
            unusedInSkeleton.removeAll(skeletonFactKeys);
            assertTrue(unusedInSkeleton.isEmpty(),
                    () -> "Template " + tid + " has particulars declared in manifest that never appear in skeleton: " + unusedInSkeleton);

            // 2. Verify AI_BLOCK placeholders
            Set<String> skeletonAiKeys = new HashSet<>();
            Matcher aiMatcher = AI_BLOCK_TOKEN_PATTERN.matcher(skeleton);
            while (aiMatcher.find()) {
                skeletonAiKeys.add(aiMatcher.group(1));
            }

            Set<String> manifestAiKeys = manifest.aiBlocks().stream()
                    .map(TemplateAiBlockSpec::blockKey)
                    .collect(Collectors.toSet());

            Set<String> missingAiInManifest = new HashSet<>(skeletonAiKeys);
            missingAiInManifest.removeAll(manifestAiKeys);
            assertTrue(missingAiInManifest.isEmpty(),
                    () -> "Template " + tid + " has {{AI_BLOCK:...}} tokens in skeleton not declared in manifest aiBlocks: " + missingAiInManifest);

            Set<String> unusedAiInSkeleton = new HashSet<>(manifestAiKeys);
            unusedAiInSkeleton.removeAll(skeletonAiKeys);
            assertTrue(unusedAiInSkeleton.isEmpty(),
                    () -> "Template " + tid + " has aiBlocks declared in manifest that never appear in skeleton: " + unusedAiInSkeleton);
        }
    }

    @Test
    @DisplayName("Gate 3: All particulars across all templates must strictly use supported ledger source keys")
    void testSupportedLedgerSourceKeysAcrossAllTemplates() {
        List<TemplateManifest> allTemplates = registryService.getAllTemplates();

        for (TemplateManifest manifest : allTemplates) {
            String tid = manifest.templateId();
            for (TemplateParticular particular : manifest.particulars()) {
                String sourceKey = particular.ledgerSourceKey();
                assertNotNull(sourceKey, () -> "Template " + tid + " particular '" + particular.key() + "' has null ledgerSourceKey");
                assertTrue(TemplateFactResolver.SUPPORTED_LEDGER_KEYS.contains(sourceKey),
                        () -> "Template " + tid + " particular '" + particular.key() + "' uses unsupported ledgerSourceKey: '" + sourceKey + "'");
            }
        }
    }

    @Test
    @DisplayName("Gate 4: Zero repealed statutes, legacy forums, or hardcoded legacy strings")
    void testZeroRepealedStatutesOrLegacyHardcoding() {
        List<TemplateManifest> allTemplates = registryService.getAllTemplates();

        for (TemplateManifest manifest : allTemplates) {
            String tid = manifest.templateId();
            String skeleton = manifest.skeletonMarkdown() != null ? manifest.skeletonMarkdown() : "";
            String legalBasis = manifest.legalBasis() != null ? manifest.legalBasis() : "";
            String whenToUse = manifest.whenToUse() != null ? manifest.whenToUse() : "";

            String combined = (skeleton + " " + legalBasis + " " + whenToUse).toLowerCase();

            for (String banned : BANNED_LEGACY_STRINGS) {
                assertFalse(combined.contains(banned.toLowerCase()),
                        () -> "Template " + tid + " contains banned legacy string: '" + banned + "'");
            }

            Matcher legacyYearMatcher = LEGACY_YEAR_PATTERN.matcher(combined);
            assertFalse(legacyYearMatcher.find(),
                    () -> "Template " + tid + " contains legacy 20th century year pattern 'of 19..'");
        }
    }

    @Test
    @DisplayName("Gate 5: Zero-token render produces no raw {{FACT:...}} or {{AI_BLOCK:...}} curly braces")
    void testZeroTokenRenderProducesNoRawCurlyBraces() {
        List<TemplateManifest> allTemplates = registryService.getAllTemplates();

        for (TemplateManifest manifest : allTemplates) {
            String tid = manifest.templateId();
            TemplateRenderer.RenderResult result = renderer.render(manifest, Map.of(), Map.of());
            assertNotNull(result, () -> "Template " + tid + " rendering result must not be null");

            String rendered = result.renderedMarkdown();
            assertNotNull(rendered, () -> "Template " + tid + " renderedMarkdown must not be null");

            assertFalse(rendered.contains("{{FACT:"),
                    () -> "Template " + tid + " rendered text leaked raw {{FACT: token after zero-token render: " + rendered);
            assertFalse(rendered.contains("{{AI_BLOCK:"),
                    () -> "Template " + tid + " rendered text leaked raw {{AI_BLOCK: token after zero-token render: " + rendered);
        }
    }
}
