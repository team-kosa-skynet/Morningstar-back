package com.gaebang.backend.domain.aianalysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AIAnalysisDto(
        List<ModelData> data
) {
    // 알 수 없는 필드를 무시하도록 설정
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelData(
            String id,
            String name,
            String slug,
            @JsonProperty("release_date") String releaseDate,
            @JsonProperty("model_creator") ModelCreator modelCreator,
            Evaluations evaluations,
            Pricing pricing,
            @JsonProperty("median_output_tokens_per_second") Double medianOutputTokensPerSecond,
            @JsonProperty("median_time_to_first_token_seconds") Double medianTimeToFirstTokenSeconds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelCreator(
            String id,
            String name,
            String slug
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evaluations(
            @JsonProperty("artificial_analysis_intelligence_index") Double artificialAnalysisIntelligenceIndex,
            @JsonProperty("artificial_analysis_coding_index") Double artificialAnalysisCodingIndex,
            @JsonProperty("artificial_analysis_math_index") Double artificialAnalysisMathIndex,
            @JsonProperty("mmlu_pro") Double mmluPro,
            Double gpqa,
            Double hle,
            Double livecodebench,
            Double scicode,
            @JsonProperty("math_500") Double math500,
            Double aime,
            @JsonProperty("aime_25") Double aime25,
            Double ifbench,
            Double lcr
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pricing(
            @JsonProperty("price_1m_blended_3_to_1") Double price1mBlended3To1,
            @JsonProperty("price_1m_input_tokens") Double price1mInputTokens,
            @JsonProperty("price_1m_output_tokens") Double price1mOutputTokens
    ) {}
}