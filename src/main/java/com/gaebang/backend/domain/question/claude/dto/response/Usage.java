package com.gaebang.backend.domain.question.claude.dto.response;

public record Usage(
        Integer input_tokens,
        Integer output_tokens
) {
}
