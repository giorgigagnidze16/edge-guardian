package com.edgeguardian.controller.dto;

public record AgentReleaseInfo(String version, String sha256, String ed25519Sig) {
}
