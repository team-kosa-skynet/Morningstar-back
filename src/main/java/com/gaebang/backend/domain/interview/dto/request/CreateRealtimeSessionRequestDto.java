package com.gaebang.backend.domain.interview.dto.request;

public record CreateRealtimeSessionRequestDto(
        String jobPosition,
        Long userId,
        String instructions // 옵션
) {
}
