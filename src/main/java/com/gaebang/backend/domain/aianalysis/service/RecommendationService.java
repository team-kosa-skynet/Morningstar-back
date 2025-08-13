package com.gaebang.backend.domain.aianalysis.service;

import com.gaebang.backend.domain.aianalysis.recommendation.Answers;
import com.gaebang.backend.domain.aianalysis.recommendation.Model;
import com.gaebang.backend.domain.aianalysis.recommendation.Weights;
import com.gaebang.backend.domain.aianalysis.entity.AIModelIntegrated;
import com.gaebang.backend.domain.aianalysis.repository.AIAnalysisRepository;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final AIAnalysisRepository modelRepository;

    public ResponseDTO<List<Model>> recommend(Answers answers) {
        List<Model> catalog = buildModelCatalogFromRepo();
        Weights w = mapAnswersToWeights(answers);

        // allowOpenSource가 null이면 필터링 안함
        List<Model> recommendedModels = catalog.stream()
                .filter(m -> answers.getAllowOpenSource() == null || answers.getAllowOpenSource() || !m.isOpenSource())
                .peek(m -> m.getScores().calculateFinalScore(w))
                .sorted((m1, m2) -> Double.compare(m2.getScores().getFinalScore(), m1.getScores().getFinalScore()))
                .limit(answers.getTopK())
                .toList();

        String message = """
                AI 모델 추천에 성공했습니다.

                [점수 설명]
                - cost: 비용 점수 (1.0에 가까울수록 저렴)
                - speed: 속도 점수 (1.0에 가까울수록 빠름)
                - math: 수학/논리 문제 해결 능력 점수
                - code: 코딩/프로그래밍 관련 성능 점수
                - knowledge: 일반 지식 및 사실 기반 질문 응답 능력 점수
                - reasoning: 복잡한 추론(Reasoning) 능력 점수
                - recency: 최신 정보 반영 정도 (1.0에 가까울수록 최신)
                """;

        return ResponseDTO.okWithData(recommendedModels, message);
    }

    private List<Model> buildModelCatalogFromRepo() {
        return modelRepository.findTop100ByOrderByArtificialAnalysisIntelligenceIndexDesc().stream()
                .map(e -> new Model(
                        e.getModelName(),
                        e.getCreatorName(),
                        false,
                        e.getReleaseDate() != null ? e.getReleaseDate().toString() : "N/A",
                        mapCost(e),
                        mapSpeed(e),
                        mapMath(e),
                        mapCode(e),
                        mapKnowledge(e),
                        mapReasoning(e),
                        mapRecency(e)
                ))
                .toList();
    }

    private double mapCost(AIModelIntegrated e) {
        if (e.getPrice1mBlended() == null) return 0.5;
        double maxPrice = 10.0;
        return Math.max(0, 1 - (e.getPrice1mBlended() / maxPrice));
    }

    // 속도 계산 개선: TPS + First Token 속도 모두 반영
    private double mapSpeed(AIModelIntegrated e) {
        double tpsScore = e.getMedianOutputTokensPerSecond() == null ? 0.5 : Math.min(1, e.getMedianOutputTokensPerSecond() / 300.0);
        double firstTokenScore = e.getMedianTimeToFirstTokenSeconds() == null ? 0.5 : Math.max(0, 1 - e.getMedianTimeToFirstTokenSeconds() / 60.0);
        return (tpsScore + firstTokenScore) / 2.0;
    }

    private double mapMath(AIModelIntegrated e) {
        return normalizeAverage(e.getArtificialAnalysisMathIndex(), e.getMath500(), e.getAime());
    }

    private double mapCode(AIModelIntegrated e) {
        return normalizeAverage(e.getArtificialAnalysisCodingIndex(), e.getLivecodebench(), e.getScicode());
    }

    private double mapKnowledge(AIModelIntegrated e) {
        return normalizeAverage(e.getMmluPro(), e.getGpqa(), e.getHle());
    }

    private double mapReasoning(AIModelIntegrated e) {
        return normalizeAverage(e.getArtificialAnalysisIntelligenceIndex(), e.getGpqa(), e.getHle());
    }

    private double mapRecency(AIModelIntegrated e) {
        if (e.getReleaseDate() == null) return 0.0;
        LocalDate now = LocalDate.now();
        long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(e.getReleaseDate(), now);
        // 미래 출시일은 1.0로 처리
        if (daysDiff < 0) return 1.0;
        return Math.max(0, 1 - (daysDiff / 730.0));
    }

    private double normalizeAverage(Double... values) {
        double sum = 0;
        int count = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                count++;
            }
        }
        if (count == 0) return 0.0;
        double avg = sum / count;
        return Math.min(1.0, avg / 100.0);
    }

    private Weights mapAnswersToWeights(Answers answers) {
        Weights w = new Weights();

        // 목적 기반 가중치
        switch (answers.getPurpose()) {
            case CODING -> w.code = 0.4;
            case MATH -> w.math = 0.4;
            case KNOWLEDGE -> w.knowledge = 0.4;
        }

        // 속도 vs 정확도
        if (answers.getAccuracyVsSpeed() == Answers.AccuracyVsSpeed.SPEED) {
            w.speed = 0.3;
        } else if (answers.getAccuracyVsSpeed() == Answers.AccuracyVsSpeed.ACCURACY) {
            w.reasoning += 0.2; // 정확도는 reasoning에도 가중치
        }

        // 가격 우선순위
        if (answers.getCostPriority() == Answers.CostPriority.CHEAP) {
            w.cost += 0.3;
        } else if (answers.getCostPriority() == Answers.CostPriority.EXPENSIVE) {
            w.cost += 0.05; // 비싼 모델 허용이면 비용 가중치 낮게
        }

        // longReasoning 반영
        if (answers.isLongReasoning()) {
            w.reasoning += 0.2;
        }

        // monthlyTokens가 null이면 반영 안함
        if (answers.getMonthlyTokens() != null && answers.getMonthlyTokens() > 10) {
            w.cost += 0.1; // 사용량 많으면 비용 중요도 증가
        }

        // 최신성 중요
        if (answers.isRecentnessMatters()) {
            w.recency += 0.2;
        }

        // 낮은 지연시간(needLowLatency) → 속도 가중치 증가
        if (answers.isNeedLowLatency()) {
            w.speed += 0.2;
        }

        // 가중치 합계 1.0 맞추기
        double total = w.cost + w.speed + w.math + w.code + w.knowledge + w.reasoning + w.recency;
        if (total > 0) {
            w.cost /= total;
            w.speed /= total;
            w.math /= total;
            w.code /= total;
            w.knowledge /= total;
            w.reasoning /= total;
            w.recency /= total;
        }

        return w;
    }
}
