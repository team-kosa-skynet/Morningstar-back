package com.gaebang.backend.domain.aianalysis.dto;

import com.gaebang.backend.domain.aianalysis.entity.AIModelIntegrated;
import com.gaebang.backend.global.util.DataFormatter;

public record AIModelDataDto(
        // 기본 정보
        String modelId,
        String modelName,
        String modelSlug,
        String releaseDate,

        // 제작사 정보
        String creatorId,
        String creatorName,
        String creatorSlug,

        // 가격 정보
        Double price1mInputTokens,
        Double price1mOutputTokens,
        Double price1mBlended,

        // 성능 평가 점수
        Double artificialAnalysisIntelligenceIndex,
        Double artificialAnalysisCodingIndex,
        Double artificialAnalysisMathIndex,
        Double mmluPro,
        Double gpqa,
        Double hle,
        Double livecodebench,
        Double scicode,
        Double math500,
        Double aime,
        Double aime25,
        Double ifbench,
        Double lcr,

        // 속도 지표
        Double medianOutputTokensPerSecond,
        Double medianTimeToFirstTokenSeconds
) {

    public static AIModelDataDto fromEntity(AIModelIntegrated entity) {
        return new AIModelDataDto(
                entity.getModelId(),
                entity.getModelName(),
                entity.getModelSlug(),
                DataFormatter.getFormattedDate(entity.getReleaseDate()),
                entity.getCreatorId(),
                entity.getCreatorName(),
                entity.getCreatorSlug(),
                entity.getPrice1mInputTokens(),
                entity.getPrice1mOutputTokens(),
                entity.getPrice1mBlended(),
                entity.getArtificialAnalysisIntelligenceIndex(),
                entity.getArtificialAnalysisCodingIndex(),
                entity.getArtificialAnalysisMathIndex(),
                entity.getMmluPro(),
                entity.getGpqa(),
                entity.getHle(),
                entity.getLivecodebench(),
                entity.getScicode(),
                entity.getMath500(),
                entity.getAime(),
                entity.getAime25(),
                entity.getIfbench(),
                entity.getLcr(),
                entity.getMedianOutputTokensPerSecond(),
                entity.getMedianTimeToFirstTokenSeconds()
        );
    }
}