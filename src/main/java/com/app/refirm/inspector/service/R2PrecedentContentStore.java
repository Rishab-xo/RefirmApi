package com.app.refirm.inspector.service;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Cloudflare R2 implementation of PrecedentContentStore.
 * Loads compressed JSON drawer pages (.json.gz) directly from Cloudflare R2 object storage.
 * Gracefully falls back to Table 2 (legal_precedent_okf_content) if key is missing or blank.
 */
@Service
@ConditionalOnProperty(name = "refirm.r2.bucket-name")
public class R2PrecedentContentStore implements PrecedentContentStore {

    private static final Logger log = LoggerFactory.getLogger(R2PrecedentContentStore.class);

    private final String accountId;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String bucketName;
    private final ObjectMapper objectMapper;
    private final PrecedentIndexRepository precedentIndexRepository;

    private S3Client s3Client;

    public R2PrecedentContentStore(
            @Value("${refirm.r2.account-id:}") String accountId,
            @Value("${refirm.r2.access-key-id:}") String accessKeyId,
            @Value("${refirm.r2.secret-access-key:}") String secretAccessKey,
            @Value("${refirm.r2.bucket-name:}") String bucketName,
            ObjectMapper objectMapper,
            PrecedentIndexRepository precedentIndexRepository
    ) {
        this.accountId = accountId;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.bucketName = bucketName;
        this.objectMapper = objectMapper;
        this.precedentIndexRepository = precedentIndexRepository;
    }

    @PostConstruct
    public void init() {
        if (accountId == null || accountId.isBlank() || accessKeyId == null || accessKeyId.isBlank()) {
            log.warn("Cloudflare R2 properties incomplete. R2 client initialization deferred.");
            return;
        }
        String endpoint = "https://" + accountId + ".r2.cloudflarestorage.com";
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false)
                        .build())
                .build();
        log.info("Initialized Cloudflare R2 S3Client for bucket: {}", bucketName);
    }

    public void setS3Client(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public Optional<List<DrawerPage>> loadPages(String authorityId, @Nullable String r2ObjectKey) {
        if (s3Client != null && r2ObjectKey != null && !r2ObjectKey.isBlank() && bucketName != null && !bucketName.isBlank()) {
            try {
                GetObjectRequest getReq = GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(r2ObjectKey)
                        .build();

                try (ResponseInputStream<GetObjectResponse> rawIn = s3Client.getObject(getReq)) {
                    InputStream in = rawIn;
                    if (r2ObjectKey.endsWith(".gz")) {
                        in = new GZIPInputStream(rawIn);
                    }
                    List<DrawerPage> pages = objectMapper.readValue(in, new TypeReference<List<DrawerPage>>() {});
                    if (pages != null && !pages.isEmpty()) {
                        return Optional.of(pages);
                    }
                }
            } catch (NoSuchKeyException e) {
                log.warn("R2 key not found: {}. Falling back to PostgreSQL Table 2 for {}", r2ObjectKey, authorityId);
            } catch (Exception e) {
                log.warn("Failed to load drawer pages from R2 for key {}: {}. Falling back to PostgreSQL Table 2",
                        r2ObjectKey, e.getMessage());
            }
        }

        return loadFromPostgresFallback(authorityId);
    }

    private Optional<List<DrawerPage>> loadFromPostgresFallback(String authorityId) {
        if (precedentIndexRepository == null || authorityId == null || authorityId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<DrawerPage> pages = precedentIndexRepository.findDrawerPagesByAuthorityId(authorityId);
            if (pages != null && !pages.isEmpty()) {
                return Optional.of(pages);
            }
        } catch (Exception e) {
            log.error("Failed PostgreSQL Table 2 fallback for authorityId {}: {}", authorityId, e.getMessage());
        }
        return Optional.empty();
    }
}
