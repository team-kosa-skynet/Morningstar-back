package com.gaebang.backend.domain.member.dto.request;

public record PasswordRequestDto(
    String currentPassword,

    String newPassword,

    String confirmPassword
) {}