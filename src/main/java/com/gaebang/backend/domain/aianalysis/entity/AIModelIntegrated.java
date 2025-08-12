package com.gaebang.backend.domain.aianalysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class AIModelIntegrated {

    @Id
    @Column(name = "model_id")
    private String modelId; // 모델의 고유 식별자 (Primary Key)

    @Column(name = "model_name", nullable = false)
    private String modelName; // 모델의 공식 명칭 (예: GPT-4o mini)

    @Column(name = "model_slug")
    private String modelSlug; // API나 URL에서 사용되는 짧은 이름

    @Column(name = "release_date")
    private LocalDate releaseDate; // 모델 출시일

    // --- 제작사 정보 ---
    @Column(name = "creator_id")
    private String creatorId; // 제작사의 고유 식별자

    @Column(name = "creator_name")
    private String creatorName; // 제작사 이름 (예: OpenAI)

    @Column(name = "creator_slug")
    private String creatorSlug; // 제작사의 짧은 이름

    // --- 가격 정보 (100만 토큰 기준) ---
    @Column(name = "price_1m_input_tokens")
    private Double price1mInputTokens; // 입력(Input) 100만 토큰당 가격 (단위: 달러)

    @Column(name = "price_1m_output_tokens")
    private Double price1mOutputTokens; // 출력(Output) 100만 토큰당 가격 (단위: 달러)

    @Column(name = "price_1m_blended")
    private Double price1mBlended; // 입/출력을 특정 비율로 혼합한 평균 가격

    // --- 성능 평가(Evaluation) 벤치마크 점수 ---
    @Column(name = "artificial_analysis_intelligence_index")
    private Double artificialAnalysisIntelligenceIndex; // 종합 지능 지수 (소스 API의 자체 지표)

    @Column(name = "artificial_analysis_coding_index")
    private Double artificialAnalysisCodingIndex; // 종합 코딩 지수 (소스 API의 자체 지표)

    @Column(name = "artificial_analysis_math_index")
    private Double artificialAnalysisMathIndex; // 종합 수학 지수 (소스 API의 자체 지표)

    @Column(name = "mmlu_pro")
    private Double mmluPro; // 다양한 전문 분야(상식, 법률, 과학 등)의 문제 해결 능력

    @Column(name = "gpqa")
    private Double gpqa; // 검색으로 찾기 어려운 고난도 질문에 대한 추론 능력

    @Column(name = "hle")
    private Double hle; // 인간 수준의 평가(Human-Level Evaluation) 점수

    @Column(name = "livecodebench")
    private Double livecodebench; // 실시간 코딩 환경에서의 문제 해결 능력

    @Column(name = "scicode")
    private Double scicode; // 과학적 계산 및 분석 관련 코드 생성 능력

    @Column(name = "math_500")
    private Double math500; // 500개의 수학 문제에 대한 해결 능력

    @Column(name = "aime")
    private Double aime; // 미국 수학 경시대회(AIME) 수준의 고난도 수학 문제 해결 능력

    @Column(name = "aime_25")
    private Double aime25; // AIME 벤치마크의 상위 25% 문제에 대한 점수

    @Column(name = "ifbench")
    private Double ifbench; // 복잡하고 세밀한 지시사항(Instruction)을 정확히 따르는 능력

    @Column(name = "lcr")
    private Double lcr; // 장문의 문맥을 이해하고 추론하는 능력 (Long-Context Reasoning)

    // --- 속도 관련 지표 ---
    @Column(name = "median_output_tokens_per_second")
    private Double medianOutputTokensPerSecond; // 1초당 생성하는 토큰 수의 중앙값 (처리량)

    @Column(name = "median_time_to_first_token_seconds")
    private Double medianTimeToFirstTokenSeconds; // 요청 후 첫 번째 토큰이 생성되기까지 걸리는 시간의 중앙값 (응답성)
}