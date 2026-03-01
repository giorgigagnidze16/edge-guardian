package com.edgeguardian.controller.dto;

public record CreateOtaArtifactRequest(
        String name,
        String version,
        String architecture,
        long size,
        String sha256,
        String ed25519Sig,
        String s3Key
) {}
