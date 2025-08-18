package com.gaebang.backend.domain.interview.dto.response;

public record StartResponseDto(
        String sessionId,
        int questionIndex,
        String firstQuestion,
        TtsPayloadDto tts
) {
}
