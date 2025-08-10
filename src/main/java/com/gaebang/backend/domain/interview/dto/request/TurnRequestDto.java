package com.gaebang.backend.domain.interview.dto.request;

import java.util.UUID;

public record TurnRequestDto(
        UUID sessionId,
        String userTranscript,    // 사용자가 방금 말한 전사
        Integer durationMs        // 선택: 발화 길이(ms)
) {
}
