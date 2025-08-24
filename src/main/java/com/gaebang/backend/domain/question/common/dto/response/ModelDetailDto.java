package com.gaebang.backend.domain.question.common.dto.response;

/**
 * 개별 AI 모델의 상세 정보
 */
public record ModelDetailDto(
        String name,
        boolean supportsFiles
) {
}