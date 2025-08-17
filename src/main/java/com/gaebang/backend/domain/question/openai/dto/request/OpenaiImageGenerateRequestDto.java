package com.gaebang.backend.domain.question.openai.dto.request;

import jakarta.validation.constraints.NotBlank;

public record OpenaiImageGenerateRequestDto(
        @NotBlank(message = "프롬프트는 필수입니다")
        String prompt
) {
}