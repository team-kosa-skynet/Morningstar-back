package com.gaebang.backend.domain.interviewTurn.dto.response;

import java.util.Map;

public record NextTurnResponseDto(
        String nextQuestion,
        String coachingTips,
        Map<String, Integer> scoreDelta,
        boolean done
) {
}
