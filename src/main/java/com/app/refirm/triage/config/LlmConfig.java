package com.app.refirm.triage.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean(name = "extractionChatClient")
    public ChatClient extractionChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(OpenAiChatOptions.builder()
                .model("gpt-6-luna")
                .temperature(0.0)
                .build()).build();
    }

    @Bean(name = "conversationChatClient")
    public ChatClient conversationChatClient(ChatClient.Builder builder) {
        return builder.defaultOptions(OpenAiChatOptions.builder()
                .model("gpt-4o-mini")
                .temperature(0.6)
                .build()).build();
    }
}