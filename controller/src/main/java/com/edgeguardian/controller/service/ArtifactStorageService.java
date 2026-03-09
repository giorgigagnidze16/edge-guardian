package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stores and retrieves OTA artifacts in a MinIO (S3-compatible) object store.
 * Objects are keyed by {@code orgId/name/version/architecture/artifact.bin}.
 */
@Slf4j
@Service
public class ArtifactStorageService {

    private final MinioClient minioClient;
    private final String bucket;

    public ArtifactStorageService(MinioClient minioClient, MinioProperties props) {
        this.minioClient = minioClient;
        this.bucket = props.bucket();
    }

    public record StorageResult(String storagePath, long size, String sha256) {}

    /**
     * Stores an artifact binary, computing its SHA-256 checksum along the way.
     * The input stream is first buffered to a temp file so the size and digest
     * are known before the upload to MinIO begins.
     */
    public StorageResult store(Long orgId, String name, String version,
                               String architecture, InputStream data) throws IOException {
        String objectKey = orgId + "/" + name + "/" + version + "/" + architecture + "/artifact.bin";

        File tempFile = File.createTempFile("ota-upload-", ".tmp");
        try {
            MessageDigest digest = sha256Digest();
            long size;
            try (DigestOutputStream out = new DigestOutputStream(
                    new FileOutputStream(tempFile), digest)) {
                size = data.transferTo(out);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());

            try (FileInputStream fileStream = new FileInputStream(tempFile)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(fileStream, size, -1)
                        .contentType("application/octet-stream")
                        .build());
            }

            log.info("Artifact stored in MinIO: key={}, size={}, sha256={}", objectKey, size, sha256);
            return new StorageResult(objectKey, size, sha256);
        } catch (Exception e) {
            throw new IOException("Failed to store artifact in MinIO", e);
        } finally {
            if (!tempFile.delete()) {
                log.warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * Returns an {@link InputStream} for the artifact at the given storage path.
     * The caller is responsible for closing the stream.
     */
    public InputStream load(String storagePath) throws IOException {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(storagePath)
                    .build());
        } catch (Exception e) {
            throw new IOException("Failed to load artifact from MinIO: " + storagePath, e);
        }
    }

    /**
     * Returns the size in bytes of the artifact at the given storage path.
     */
    public long fileSize(String storagePath) throws IOException {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(storagePath)
                    .build()).size();
        } catch (Exception e) {
            throw new IOException("Failed to stat artifact in MinIO: " + storagePath, e);
        }
    }

    /**
     * Deletes the artifact at the given storage path.
     */
    public void delete(String storagePath) throws IOException {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storagePath)
                    .build());
        } catch (Exception e) {
            throw new IOException("Failed to delete artifact from MinIO: " + storagePath, e);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
