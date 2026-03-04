package com.edgeguardian.controller.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Service
public class ArtifactStorageService {

    private final Path storageDir;

    public ArtifactStorageService(
            @Value("${edgeguardian.controller.ota.storage-dir:./data/ota-artifacts}") String storageDir) {
        this.storageDir = Path.of(storageDir);
    }

    public record StorageResult(String storagePath, long size, String sha256) {}

    public StorageResult store(Long orgId, String name, String version,
                               String architecture, InputStream data) throws IOException {
        var dir = storageDir.resolve(String.valueOf(orgId))
                .resolve(name).resolve(version).resolve(architecture);
        Files.createDirectories(dir);

        var tempFile = Files.createTempFile(dir, "upload-", ".tmp");
        var digest = sha256Digest();

        long size;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tempFile), digest)) {
            size = data.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        var target = dir.resolve("artifact.bin");
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);

        var sha256 = HexFormat.of().formatHex(digest.digest());
        var storagePath = storageDir.relativize(target).toString().replace('\\', '/');

        log.info("Artifact stored: path={}, size={}, sha256={}", storagePath, size, sha256);
        return new StorageResult(storagePath, size, sha256);
    }

    public InputStream load(String storagePath) throws IOException {
        return Files.newInputStream(storageDir.resolve(storagePath));
    }

    public long fileSize(String storagePath) throws IOException {
        return Files.size(storageDir.resolve(storagePath));
    }

    public void delete(String storagePath) throws IOException {
        Files.deleteIfExists(storageDir.resolve(storagePath));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
