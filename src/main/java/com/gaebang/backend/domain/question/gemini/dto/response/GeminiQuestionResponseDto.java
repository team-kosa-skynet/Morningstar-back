package com.gaebang.backend.domain.question.gemini.dto.response;

import java.util.List;

public record GeminiQuestionResponseDto(
        List<Candidate> candidates,
        Usage usageMetadata
) {
}