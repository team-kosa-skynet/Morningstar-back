package com.gaebang.backend.domain.interview.dto.response;

import java.util.UUID;

public record StartSessionResponseDto(
        UUID sessionId,
        String greeting,
        String firstQuestion,
        int totalQuestions,
        TtsPayloadDto tts
) {
}
