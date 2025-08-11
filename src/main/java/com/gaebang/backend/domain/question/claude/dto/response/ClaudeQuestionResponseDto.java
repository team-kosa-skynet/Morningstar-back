package com.gaebang.backend.domain.question.claude.dto.response;

import java.util.List;

public record ClaudeQuestionResponseDto(
        List<Content> content,
        String id,
        String model,
        String role,
        String stop_reason,
        String stop_sequence,
        String type,
        Usage usage
) {
}
