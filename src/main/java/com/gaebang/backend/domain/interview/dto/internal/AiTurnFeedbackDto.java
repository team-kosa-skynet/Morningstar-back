package com.gaebang.backend.domain.interview.dto.internal;

import java.util.Map;

public record AiTurnFeedbackDto(
        String coachingTips,
        String responseId
) {
}
