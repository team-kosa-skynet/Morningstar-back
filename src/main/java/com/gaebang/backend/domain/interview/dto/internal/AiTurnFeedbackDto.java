package com.gaebang.backend.domain.interview.dto.internal;

import java.util.Map;

public record AiTurnFeedbackDto(
        String coachingTips,
        Map<String, Integer> scoreDelta,
        String responseId
) {
}
