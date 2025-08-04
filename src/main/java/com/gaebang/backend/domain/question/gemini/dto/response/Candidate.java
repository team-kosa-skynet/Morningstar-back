package com.gaebang.backend.domain.question.gemini.dto.response;

import java.util.List;

public record Candidate(
        Content content,
        String finishReason,
        Integer index,
        List<SafetyRating> safetyRatings
) {
}