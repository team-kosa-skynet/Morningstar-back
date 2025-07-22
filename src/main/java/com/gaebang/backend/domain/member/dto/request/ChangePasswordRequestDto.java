package com.gaebang.backend.domain.member.dto.request;

public record ChangePasswordRequestDto(
    String currentPassword,

    String newPassword
) {}