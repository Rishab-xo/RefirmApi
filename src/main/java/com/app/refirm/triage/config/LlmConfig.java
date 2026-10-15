package com.app.refirm.triage.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                .build()).build();
    }

    @Bean(name = "conversationChatClient")
    public ChatClient conversationChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(OpenAiChatOptions.builder()
                .model(conversationModel)
                .build()).build();
    }

    @Bean(name = "lunaChatClient")
    public ChatClient lunaChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(OpenAiChatOptions.builder()
                .model("gpt-6-luna")
                .build()).build();
    }
}