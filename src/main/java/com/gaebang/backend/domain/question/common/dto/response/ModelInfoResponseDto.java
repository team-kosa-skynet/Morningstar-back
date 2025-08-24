package com.gaebang.backend.domain.question.common.dto.response;

/**
 * 전체 AI 모델 정보 응답 DTO
 */
public record ModelInfoResponseDto(
        ProviderModelsDto claude,
        ProviderModelsDto gemini,
        ProviderModelsDto openai
) {
}