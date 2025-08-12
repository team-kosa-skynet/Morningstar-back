package com.gaebang.backend.domain.question.openai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * OpenAI 질문 요청 DTO
 * 모델은 쿼리 파라미터로 받으므로 content만 포함
 */
public record OpenaiQuestionRequestDto(
        @NotBlank(message = "질문 내용을 입력해주세요")
        @Size(max = 10000, message = "질문은 10000자 이하로 입력해주세요")
        String content
) {
}
