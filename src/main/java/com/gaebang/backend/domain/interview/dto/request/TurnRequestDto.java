package com.gaebang.backend.domain.interview.dto.request;

import java.util.UUID;

public record TurnRequestDto(
        UUID sessionId,
        String oaiSessionId,     // 선택
        String systemEvent,      // "start" | null
        String jobPosition,      // 선택(있으면 덮어쓰기)
        String userTranscript,   // 음성 전사 텍스트
        int durationMs           // 발화 길이
) {
}
