package com.gaebang.backend.domain.ai.recommendation;

import com.gaebang.backend.domain.ai.entity.AIModelIntegrated;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ModelScoreCalculator {

    public List<Model> normalizeAndCreateModelList(List<AIModelIntegrated> allRawModels) {
        if (allRawModels == null || allRawModels.isEmpty()) {
            return List.of();
        }

        // 각 지표의 최소/최대값 미리 계산
        ScoreRange costRange = calculateRange(allRawModels, AIModelIntegrated::getPrice1mBlended);
        ScoreRange speedTokensPerSecondRange = calculateRange(allRawModels, AIModelIntegrated::getMedianOutputTokensPerSecond);
        ScoreRange speedFirstTokenRange = calculateRange(allRawModels, AIModelIntegrated::getMedianTimeToFirstTokenSeconds);
        ScoreRange recencyRange = calculateRange(allRawModels, m -> m.getReleaseDate() != null ? (double) ChronoUnit.DAYS.between(m.getReleaseDate(), LocalDate.now()) : null);

        ScoreRange knowledgeRange = calculateRange(allRawModels, this::calculateRawKnowledgeScore);
        ScoreRange codeRange = calculateRange(allRawModels, this::calculateRawCodeScore);
        ScoreRange mathRange = calculateRange(allRawModels, this::calculateRawMathScore);
        ScoreRange reasoningRange = calculateRange(allRawModels, this::calculateRawReasoningScore);

        // 각 모델을 순회하며 정규화된 Model DTO 생성
        return allRawModels.stream()
                .map(rawModel -> {
                    double costScore = normalizeInverted(rawModel.getPrice1mBlended(), costRange);
                    double speedScore = calculateCombinedSpeedScore(rawModel, speedTokensPerSecondRange, speedFirstTokenRange);
                    Double daysSinceRelease = rawModel.getReleaseDate() != null ? (double) ChronoUnit.DAYS.between(rawModel.getReleaseDate(), LocalDate.now()) : null;
                    double recencyScore = normalizeInverted(daysSinceRelease, recencyRange);

                    double knowledgeScore = normalize(calculateRawKnowledgeScore(rawModel), knowledgeRange);
                    double codeScore = normalize(calculateRawCodeScore(rawModel), codeRange);
                    double mathScore = normalize(calculateRawMathScore(rawModel), mathRange);
                    double reasoningScore = normalize(calculateRawReasoningScore(rawModel), reasoningRange);

                    boolean isOpenSource = determineOpenSource(rawModel.getCreatorName());

                    Model model = new Model();
                    model.setName(rawModel.getModelName());
                    model.setCreator(rawModel.getCreatorName());
                    model.setReleaseDate(rawModel.getReleaseDate() != null ? rawModel.getReleaseDate().toString() : "N/A");

                    Model.Scores scores = model.getScores();
                    scores.setCost(costScore);
                    scores.setSpeed(speedScore);
                    scores.setRecency(recencyScore);
                    scores.setKnowledge(knowledgeScore);
                    scores.setCode(codeScore);
                    scores.setMath(mathScore);
                    scores.setReasoning(reasoningScore);

                    return model;
                })
                .collect(Collectors.toList());
    }

    // --- 각 영역별 원시 점수 계산 (여러 벤치마크 점수 평균) ---
    private double calculateRawKnowledgeScore(AIModelIntegrated m) {
        return Stream.of(m.getMmluPro(), m.getGpqa(), m.getHle())
                .filter(Objects::nonNull)
                .mapToDouble(d -> d)
                .average()
                .orElse(0.0);
    }

    private double calculateRawCodeScore(AIModelIntegrated m) {
        return Stream.of(m.getLivecodebench(), m.getScicode())
                .filter(Objects::nonNull)
                .mapToDouble(d -> d)
                .average()
                .orElse(0.0);
    }

    private double calculateRawMathScore(AIModelIntegrated m) {
        return Stream.of(m.getMath500(), m.getAime(), m.getAime25())
                .filter(Objects::nonNull)
                .mapToDouble(d -> d)
                .average()
                .orElse(0.0);
    }

    private double calculateRawReasoningScore(AIModelIntegrated m) {
        return Stream.of(m.getIfbench(), m.getLcr())
                .filter(Objects::nonNull)
                .mapToDouble(d -> d)
                .average()
                .orElse(0.0);
    }

    // --- 속도 점수 계산 (처리량 + 응답성) ---
    private double calculateCombinedSpeedScore(AIModelIntegrated rawModel, ScoreRange tpsRange, ScoreRange tttfRange) {
        double tpsScore = normalize(rawModel.getMedianOutputTokensPerSecond(), tpsRange);
        double tttfScore = normalizeInverted(rawModel.getMedianTimeToFirstTokenSeconds(), tttfRange);
        return (tpsScore * 0.5) + (tttfScore * 0.5);
    }

    // 오픈소스 여부 판단
    private boolean determineOpenSource(String creatorName) {
        if (creatorName == null) return false;
        return List.of("Meta", "Mistral AI", "EleutherAI", "Technology Innovation Institute").contains(creatorName);
    }

    // --- 정규화 헬퍼 메서드들 ---
    private record ScoreRange(double min, double max) {}

    private ScoreRange calculateRange(List<AIModelIntegrated> models, Function<AIModelIntegrated, Double> func) {
        DoubleSummaryStatistics stats = models.stream()
                .map(func)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

        if (stats.getCount() == 0) {
            return new ScoreRange(0.0, 1.0); // 모든 값이 null이면 기본 범위 반환
        }

        // min과 max를 stats에서 직접 가져와 범위를 일치시킴
        return new ScoreRange(stats.getMin(), stats.getMax());
    }

    private double normalize(Double value, ScoreRange range) {
        if (value == null || range.max() == range.min()) return 0.0;
        return (value - range.min()) / (range.max() - range.min()) * 100.0;
    }

    private double normalizeInverted(Double value, ScoreRange range) {
        if (value == null || range.max() == range.min()) return 0.0;
        return (range.max() - value) / (range.max() - range.min()) * 100.0;
    }
}