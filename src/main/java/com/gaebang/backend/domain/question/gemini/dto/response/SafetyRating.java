package com.gaebang.backend.domain.question.gemini.dto.response;

public record SafetyRating(
        String category,
        String probability
) {
}