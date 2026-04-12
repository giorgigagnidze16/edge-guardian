package com.edgeguardian.controller.service.result;

public record StorageResult(String storagePath, long size, String sha256) {
}
