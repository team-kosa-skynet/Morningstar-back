package com.gaebang.backend.domain.question.common.dto.response;

import java.util.List;

/**
 * AI 제공업체별 모델 목록 정보
 */
public record ProviderModelsDto(
        String defaultModel,
        List<ModelDetailDto> models
) {
}