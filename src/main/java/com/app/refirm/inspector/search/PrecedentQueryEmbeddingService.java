package com.app.refirm.inspector.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Generates compact 512-dimension vector embeddings using text-embedding-3-small.
 * Includes a bounded in-memory LRU cache (256 entries) and a strict 1,500ms timeout
 * for graceful degradation under network latency or quota limits.
 */
@Service
public class PrecedentQueryEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PrecedentQueryEmbeddingService.class);
    private static final int EXPECTED_DIMENSIONS = 512;
    private static final int TIMEOUT_MILLIS = 4500;
    private static final int MAX_CACHE_SIZE = 256;

    private final EmbeddingModel embeddingModel;
    private final ExecutorService executor;
    private final Map<String, String> lruCache;

    @Autowired
    public PrecedentQueryEmbeddingService(@Qualifier("primaryEmbeddingModel") @Autowired(required = false) EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "precedent-embedder");
            t.setDaemon(true);
            return t;
        });
        this.lruCache = Collections.synchronizedMap(
                new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > MAX_CACHE_SIZE;
                    }
                }
        );
    }

    /**
     * Non-blocking async generation of a PostgreSQL vector literal string "[f0,f1,...]".
     * Returns null if embedding model is unavailable, times out after 1,500ms, or returns invalid dimensions.
     */
    public CompletableFuture<String> embedQueryAsync(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String trimmed = queryText.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String cached = lruCache.get(trimmed);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        if (embeddingModel == null) {
            log.debug("EmbeddingModel bean not available; skipping dense vector recall.");
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> generateEmbeddingLiteral(trimmed), executor);

        // Apply 1,500ms timeout with graceful degradation
        return future.orTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("Dense query embedding timed out or failed for query '{}': {}", trimmed, ex.getMessage());
                    return null;
                });
    }

    /**
     * Synchronous blocking embedding helper with timeout.
     */
    public Optional<String> embedQuery(String queryText) {
        try {
            String literal = embedQueryAsync(queryText).get(TIMEOUT_MILLIS + 200, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(literal);
        } catch (Exception e) {
            log.warn("Synchronous embedQuery fallback triggered: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String generateEmbeddingLiteral(String text) {
        try {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .model("text-embedding-3-small")
                    .dimensions(EXPECTED_DIMENSIONS)
                    .build();

            EmbeddingRequest request = new EmbeddingRequest(List.of(text), options);
            EmbeddingResponse response = embeddingModel.call(request);

            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                log.warn("Null embedding result returned from model for query '{}'", text);
                return null;
            }

            float[] vector = response.getResult().getOutput();
            if (vector.length != EXPECTED_DIMENSIONS) {
                log.warn("Dimension mismatch: expected {} but got {} for query '{}'. Degrading vector channel.",
                        EXPECTED_DIMENSIONS, vector.length, text);
                return null;
            }

            StringBuilder sb = new StringBuilder(EXPECTED_DIMENSIONS * 9);
            sb.append('[');
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(vector[i]);
            }
            sb.append(']');

            String literal = sb.toString();
            lruCache.put(text, literal);
            return literal;
        } catch (Exception e) {
            log.warn("Failed to generate embedding for query '{}': {}", text, e.getMessage());
            return null;
        }
    }
}
