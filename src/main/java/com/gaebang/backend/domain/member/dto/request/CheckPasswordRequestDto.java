package com.gaebang.backend.domain.member.dto.request;

public record CheckPasswordRequestDto(
    String currentPassword
) {}