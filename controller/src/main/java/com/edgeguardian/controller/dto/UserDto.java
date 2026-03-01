package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.User;

import java.time.Instant;

public record UserDto(
        Long id,
        String email,
        String displayName,
        String avatarUrl,
        Instant createdAt
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getCreatedAt()
        );
    }
}
