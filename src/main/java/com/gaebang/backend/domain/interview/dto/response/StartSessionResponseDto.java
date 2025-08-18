package com.gaebang.backend.domain.interview.dto.response;

import java.util.List;
import java.util.UUID;

public record StartSessionResponseDto(
        UUID sessionId,
        String greeting,
        String firstQuestion,
        String questionIntent,
        List<String> answerGuides,
        int totalQuestions,
        int currentIndex,
        TtsPayloadDto tts
) {
}
