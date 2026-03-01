package com.edgeguardian.controller.dto;

public record AddMemberRequest(
        Long userId,
        String role
) {}
