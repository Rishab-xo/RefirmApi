package com.app.refirm.core.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient(){

       return MinioClient.builder()
               .endpoint(endpoint)
               .credentials(accessKey,secretKey)
               .region("us-east-1") // Explicitly set the region for Filebase S3
               .build();
    }

}
