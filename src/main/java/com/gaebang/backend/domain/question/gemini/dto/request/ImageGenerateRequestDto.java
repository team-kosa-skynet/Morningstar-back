package com.gaebang.backend.domain.question.gemini.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ImageGenerateRequestDto(
        @NotBlank(message = "프롬프트는 필수입니다")
        String prompt
) {
}