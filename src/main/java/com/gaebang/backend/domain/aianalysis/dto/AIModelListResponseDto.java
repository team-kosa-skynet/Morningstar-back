package com.gaebang.backend.domain.aianalysis.dto;

import com.gaebang.backend.domain.aianalysis.entity.AIModelIntegrated;
import java.util.List;
import java.util.stream.Collectors;


public record AIModelListResponseDto(
        Descriptions descriptions, // 설명 객체
        List<AIModelDataDto> models   // 데이터 리스트
) {

    public record Descriptions(
            String modelId,
            String modelName,
            String modelSlug,
            String releaseDate,
            String creatorId,
            String creatorName,
            String creatorSlug,
            String price1mInputTokens,
            String price1mOutputTokens,
            String price1mBlended,
            String artificialAnalysisIntelligenceIndex,
            String artificialAnalysisCodingIndex,
            String artificialAnalysisMathIndex,
            String mmluPro,
            String gpqa,
            String hle,
            String livecodebench,
            String scicode,
            String math500,
            String aime,
            String aime25,
            String ifbench,
            String lcr,
            String medianOutputTokensPerSecond,
            String medianTimeToFirstTokenSeconds
    ) {}

    public static AIModelListResponseDto from(List<AIModelIntegrated> modelEntities) {
        // 1. 엔티티 리스트를 데이터 DTO 리스트로 변환합니다.
        List<AIModelDataDto> modelDataList = modelEntities.stream()
                .map(AIModelDataDto::fromEntity)
                .collect(Collectors.toList());

        // 2. 모든 필드에 대한 설명 객체를 생성합니다.
        Descriptions descriptions = new Descriptions(
                "모델의 고유 식별자 (Primary Key)",
                "모델의 공식 명칭 (예: GPT-4o mini)",
                "API나 URL에서 사용되는 짧은 이름",
                "모델 출시일",
                "제작사의 고유 식별자",
                "제작사 이름 (예: OpenAI)",
                "제작사의 짧은 이름",
                "입력(Input) 100만 토큰당 가격 (단위: 달러)",
                "출력(Output) 100만 토큰당 가격 (단위: 달러)",
                "입/출력을 특정 비율로 혼합한 평균 가격",
                "종합 지능 지수 (소스 API의 자체 지표)",
                "종합 코딩 지수 (소스 API의 자체 지표)",
                "종합 수학 지수 (소스 API의 자체 지표)",
                "다양한 전문 분야(상식, 법률, 과학 등)의 문제 해결 능력",
                "검색으로 찾기 어려운 고난도 질문에 대한 추론 능력",
                "인간 수준의 평가(Human-Level Evaluation) 점수",
                "실시간 코딩 환경에서의 문제 해결 능력",
                "과학적 계산 및 분석 관련 코드 생성 능력",
                "500개의 수학 문제에 대한 해결 능력",
                "미국 수학 경시대회(AIME) 수준의 고난도 수학 문제 해결 능력",
                "AIME 벤치마크의 상위 25% 문제에 대한 점수",
                "복잡하고 세밀한 지시사항(Instruction)을 정확히 따르는 능력",
                "장문의 문맥을 이해하고 추론하는 능력 (Long-Context Reasoning)",
                "1초당 생성하는 토큰 수의 중앙값 (처리량)",
                "요청 후 첫 번째 토큰이 생성되기까지 걸리는 시간의 중앙값 (응답성)"
        );

        // 3. 최종 응답 객체로 조립하여 반환합니다.
        return new AIModelListResponseDto(descriptions, modelDataList);
    }
}