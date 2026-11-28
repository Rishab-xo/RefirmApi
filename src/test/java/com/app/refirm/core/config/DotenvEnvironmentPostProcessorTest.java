package com.app.refirm.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Loads R2 properties from fallback .env without overriding explicit non-blank properties")
    void loadsFallbackDotenvWithoutOverridingExplicitProperties() throws Exception {
        Path envFile = tempDir.resolve("precedent.env");
        Files.writeString(envFile, """
                # Cloudflare R2 config
                R2_ACCOUNT_ID="acc-from-file"
                R2_ACCESS_KEY_ID='key-from-file'
                R2_BUCKET_NAME=refirm-precedents
                """, StandardCharsets.UTF_8);

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("explicit", Map.of(
                "R2_BUCKET_NAME", "override-bucket",
                "R2_ACCOUNT_ID", "   "
        )));

        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor(List.of(envFile));
        processor.postProcessEnvironment(env, new SpringApplication(Object.class));

        assertEquals("override-bucket", env.getProperty("R2_BUCKET_NAME"));
        assertEquals("acc-from-file", env.getProperty("R2_ACCOUNT_ID"));
        assertEquals("key-from-file", env.getProperty("R2_ACCESS_KEY_ID"));
    }

    @Test
    @DisplayName("Skips loading local .env files when test profile is active")
    void skipsLoadingUnderTestProfile() throws Exception {
        Path envFile = tempDir.resolve("precedent.env");
        Files.writeString(envFile, "R2_BUCKET_NAME=refirm-precedents\n", StandardCharsets.UTF_8);

        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("test");

        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor(List.of(envFile));
        processor.postProcessEnvironment(env, new SpringApplication(Object.class));

        assertNull(env.getProperty("R2_BUCKET_NAME"));
    }
}
