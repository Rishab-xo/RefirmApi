package com.app.refirm.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customises Spring Boot's auto-configured {@code ObjectMapper} to be tolerant of
 * unknown enum values encountered during Rules Engine JSON deserialization.
 * <p>
 * <strong>Why a customizer and not a new {@code @Bean ObjectMapper}?</strong><br>
 * Declaring a new {@code @Primary ObjectMapper} bean would shadow Spring Boot's
 * auto-configured one and break the existing Jackson infrastructure (Spring AI, JPA, etc.).
 * {@link Jackson2ObjectMapperBuilderCustomizer} surgically adds our features to the
 * shared auto-configured mapper without replacing it.
 * <p>
 * <strong>Features enabled:</strong>
 * <ul>
 *   <li>{@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} — activates the
 *       {@code @JsonEnumDefaultValue} on {@code LegalDomain.UNKNOWN}, routing any
 *       unrecognised domain token to the fallback sentinel instead of throwing.</li>
 *   <li>{@code MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS} — allows case-variant
 *       domain strings (e.g., {@code "tenancy"} or {@code "Tenancy"}) to resolve correctly.</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer rulesEngineDeserializationCustomizer() {
        return builder -> builder
                .featuresToEnable(
                        DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE,
                        MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS
                );
    }
}
