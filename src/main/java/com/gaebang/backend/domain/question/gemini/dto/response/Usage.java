package com.gaebang.backend.domain.question.gemini.dto.response;

public record Usage(
        Integer promptTokenCount,
        Integer candidatesTokenCount,
        Integer totalTokenCount
) {
}