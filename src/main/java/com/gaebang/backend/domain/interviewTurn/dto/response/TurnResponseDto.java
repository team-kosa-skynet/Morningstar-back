package com.gaebang.backend.domain.interviewTurn.dto.response;

import java.util.List;

public record TurnResponseDto(
        String nextQuestion,
        List<String> coachingTips,
        int scoreDelta,
        boolean done,
        TtsPayloadDto tts
) {
}
