package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the MinIO S3-compatible object store
 * used for OTA artifact storage.
 */
@ConfigurationProperties(prefix = "edgeguardian.controller.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket
) {
}
