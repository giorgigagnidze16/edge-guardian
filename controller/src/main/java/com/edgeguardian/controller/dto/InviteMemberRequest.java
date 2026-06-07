package com.edgeguardian.controller.dto;

public record InviteMemberRequest(
        String email,
        String role
) {}
