package com.gaebang.backend.domain.interviewTurn.dto.response;

public record StartResponseDto(
        String sessionId,
        int questionIndex,
        String firstQuestion,
        TtsPayloadDto tts
) {
}
