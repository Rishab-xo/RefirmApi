package com.app.refirm.core.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI Configuration for Refirm.
 * Configures OpenAI (gpt-4o-mini / text-embedding-3-small) as the primary models
 * while keeping Ollama available in the ApplicationContext for multi-model agentic workflows.
 */
@Configuration
public class AiConfig {

    @Bean
    @Primary
    public ChatModel primaryChatModel(
            @Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        return openAiChatModel;
    }

    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        // Tells Spring's PgVector auto-config to use OpenAI embeddings
        return openAiEmbeddingModel;
    }

    @Bean
    @Primary
    public ChatClient.Builder primaryChatClientBuilder(
            @Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel);
    }

    @Bean
    public org.springframework.ai.chat.memory.ChatMemory chatMemory() {
        return org.springframework.ai.chat.memory.MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean(name = "ollamaChatClientBuilder")
    public ChatClient.Builder ollamaChatClientBuilder(
            @Qualifier("ollamaChatModel") ChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel);
    }

    @Bean(name = "deepSeekChatModel")
    public ChatModel deepSeekChatModel(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.api-key}") String apiKey
    ) {
        org.springframework.ai.openai.api.OpenAiApi openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        return org.springframework.ai.openai.OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model("deepseek/deepseek-chat")
                        .build())
                .build();
    }

    @Bean(name = "deepSeekChatClientBuilder")
    public ChatClient.Builder deepSeekChatClientBuilder(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel);
    }
}

