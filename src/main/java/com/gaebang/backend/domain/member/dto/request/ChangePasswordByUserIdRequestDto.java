package com.gaebang.backend.domain.member.dto.request;

public record ChangePasswordByUserIdRequestDto(
    String newPassword
) {}