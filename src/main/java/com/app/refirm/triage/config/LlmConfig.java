package com.app.refirm.triage.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class LlmConfig {

    @Value("${refirm.ai.models.extraction:gpt-6-luna}")
    private String extractionModel;

    @Value("${refirm.ai.models.conversation:gpt-6-luna}")
    private String conversationModel;

    @Bean(name = "extractionChatClient")
    public ChatClient extractionChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(OpenAiChatOptions.builder()
                .model(extractionModel)
                .temperature(1.0)
                .build()).build();
    }

    @Primary
    @Bean(name = "conversationChatClient")
    public ChatClient conversationChatClient(ChatClient.Builder builder, Environment env) {
        ChatClient.Builder clientBuilder = builder.defaultOptions(OpenAiChatOptions.builder()
                .model(conversationModel)
                .temperature(1.0)
                .build());

        if (env != null && (env.acceptsProfiles(Profiles.of("dev")) || "true".equalsIgnoreCase(env.getProperty("refirm.ai.advisor.logger.enabled")))) {
            clientBuilder.defaultAdvisors(new SimpleLoggerAdvisor());
        }

        return clientBuilder.build();
    }

    @Bean(name = "lunaChatClient")
    public ChatClient lunaChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(OpenAiChatOptions.builder()
                .model("gpt-6-luna")
                .temperature(1.0)
                .build()).build();
    }
}