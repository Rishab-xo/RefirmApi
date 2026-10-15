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
 * Configures OpenAI (gpt-6-luna / text-embedding-3-small) as the primary models
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
    @org.springframework.context.annotation.Scope(org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE)
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

    /**
     * Dedicated reasoning ChatModel bean for OpenAI reasoning models (o3-mini / o1).
     * Builds an isolated OpenAiChatModel with no default temperature, preventing
     * Spring AI's auto-configured temperature (0.7) from causing HTTP 400 parameter errors.
     */
    @Bean(name = "reasoningChatModel")
    public ChatModel reasoningChatModel(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.base-url:https://api.openai.com}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.api-key}") String apiKey,
            @org.springframework.beans.factory.annotation.Value("${refirm.ai.models.reasoning:o3-mini}") String reasoningModel
    ) {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(30));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(90));

        org.springframework.web.client.RestClient.Builder restClientBuilder =
                org.springframework.web.client.RestClient.builder().requestFactory(requestFactory);

        org.springframework.ai.openai.api.OpenAiApi openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        return org.springframework.ai.openai.OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(reasoningModel)
                        .build())
                .build();
    }

    @Bean(name = "reasoningChatClient")
    public ChatClient reasoningChatClient(
            @Qualifier("reasoningChatModel") ChatModel reasoningChatModel) {
        return ChatClient.builder(reasoningChatModel).build();
    }

    @Bean(name = "reasoningChatClientBuilder")
    @org.springframework.context.annotation.Scope(org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ChatClient.Builder reasoningChatClientBuilder(
            @Qualifier("reasoningChatModel") ChatModel reasoningChatModel) {
        return ChatClient.builder(reasoningChatModel);
    }
}

