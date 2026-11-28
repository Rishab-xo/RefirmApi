package com.app.refirm.core.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads local .env configuration entries (such as Cloudflare R2 credentials) for local dev launches
 * without requiring IDE-specific run configuration duplication.
 * Skipped under the "test" profile and never overrides explicit non-blank environment/system properties.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "refirmLocalDotenv";
    private static final List<Path> DEFAULT_CANDIDATE_FILES = List.of(
            Path.of(".env"),
            Path.of("..", ".env"),
            Path.of("..", "precedent-etl", ".env"),
            Path.of("ParichayApi", ".env"),
            Path.of("precedent-etl", ".env")
    );

    private final List<Path> candidateFiles;

    public DotenvEnvironmentPostProcessor() {
        this(DEFAULT_CANDIDATE_FILES);
    }

    DotenvEnvironmentPostProcessor(List<Path> candidateFiles) {
        this.candidateFiles = candidateFiles != null ? List.copyOf(candidateFiles) : List.of();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null || environment.acceptsProfiles(Profiles.of("test"))) {
            return;
        }

        Map<String, Object> loaded = new LinkedHashMap<>();
        for (Path candidate : candidateFiles) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                continue;
            }
            parseDotenvFile(normalized, environment, loaded);
        }

        if (!loaded.isEmpty()) {
            // Only missing or blank properties are placed into `loaded`, so addFirst cleanly fills blank overrides.
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, loaded));
        }
    }

    private static void parseDotenvFile(Path file, ConfigurableEnvironment environment, Map<String, Object> target) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return;
        }

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eqIdx = line.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = line.substring(0, eqIdx).trim();
            String val = stripMatchingQuotes(line.substring(eqIdx + 1).trim());
            if (key.isEmpty() || val.isEmpty() || target.containsKey(key)) {
                continue;
            }
            String existing = environment.getProperty(key);
            if (existing == null || existing.isBlank()) {
                target.put(key, val);
            }
        }
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
