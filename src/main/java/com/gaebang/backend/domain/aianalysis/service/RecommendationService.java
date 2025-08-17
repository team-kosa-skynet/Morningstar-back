package com.gaebang.backend.domain.aianalysis.service;

import com.gaebang.backend.domain.aianalysis.entity.AIModelIntegrated;
import com.gaebang.backend.domain.aianalysis.recommendation.Answers;
import com.gaebang.backend.domain.aianalysis.recommendation.Model;
import com.gaebang.backend.domain.aianalysis.recommendation.ModelScoreCalculator;
import com.gaebang.backend.domain.aianalysis.recommendation.Weights;
import com.gaebang.backend.domain.aianalysis.repository.AIAnalysisRepository;
import com.gaebang.backend.global.util.ResponseDTO;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RecommendationService {

    private final AIAnalysisRepository aiModelRepository;
    private final ModelScoreCalculator modelScoreCalculator;

    public RecommendationService(AIAnalysisRepository aiModelRepository, ModelScoreCalculator modelScoreCalculator) {
        this.aiModelRepository = aiModelRepository;
        this.modelScoreCalculator = modelScoreCalculator;
    }

    public ResponseDTO<List<Model>> recommendModels(Answers answers) {

        List<AIModelIntegrated> allRawModels = aiModelRepository.findAll();
        List<Model> catalog = modelScoreCalculator.normalizeAndCreateModelList(allRawModels);
        Weights weights = createWeightsFromAnswers(answers);

        // 사용자의 주요 목적에 따라 필수 점수가 없는 모델을 사전에 필터링
        List<Model> filteredCatalog = catalog.stream()
                .filter(model -> {
                    // 사용자가 코딩을 원하는데 모델의 코딩 점수가 0점이면 제외
                    if (answers.getPurpose() == Answers.Purpose.CODING && model.getScores().getCode() <= 0) {
                        return false;
                    }
                    // 사용자가 수학을 원하는데 모델의 수학 점수가 0점이면 제외
                    if (answers.getPurpose() == Answers.Purpose.MATH && model.getScores().getMath() <= 0) {
                        return false;
                    }
                    return true; // 그 외에는 모두 통과
                })
                .toList();

        // 사전 필터링된 리스트를 사용하고 결과를 변수에 할당
        List<Model> recommendedModels = filteredCatalog.stream()
                //.filter(m -> answers.getAllowOpenSource() == null || answers.getAllowOpenSource() || !m.isOpenSource())
                .peek(m -> m.getScores().calculateFinalScore(weights))
                .sorted(Comparator.comparingDouble((Model m) -> m.getScores().getFinalScore()).reversed())
                .limit(answers.getTopK())
                .toList();

        return ResponseDTO.okWithData(recommendedModels, "AI 추천 성공");
    }

    // 사용자 답변을 해석해 가중치를 동적으로 할당
    private Weights createWeightsFromAnswers(Answers answers) {
        Weights w = new Weights();
        // 기본 가중치는 모두 1.0으로 설정
        w.setCost(1.0);
        w.setSpeed(1.0);
        w.setMath(1.0);
        w.setCode(1.0);
        w.setKnowledge(1.0);
        w.setReasoning(1.0);
        w.setRecency(1.0);

        // 1. 주요 목적(Purpose)에 따라 가중치 증폭
        switch (answers.getPurpose()) {
            case CODING -> {
                w.setCode(w.getCode() * 2.0);
                w.setReasoning(w.getReasoning() * 1.5);
            }
            case MATH -> w.setMath(w.getMath() * 2.5);
            case KNOWLEDGE -> w.setKnowledge(w.getKnowledge() * 2.0);
        }

        // 2. 정확도 vs 속도
        switch (answers.getAccuracyVsSpeed()) {
            case ACCURACY -> {
                w.setKnowledge(w.getKnowledge() * 1.5);
                w.setMath(w.getMath() * 1.5);
                w.setCode(w.getCode() * 1.5);
                w.setReasoning(w.getReasoning() * 1.5);
                w.setSpeed(w.getSpeed() * 0.7); // 속도 중요도 감소
            }
            case SPEED -> w.setSpeed(w.getSpeed() * 2.0);
            case BALANCED -> { /* 기본 가중치 유지 */ }
        }

        // 3. 비용 중요도
        switch (answers.getCostPriority()) {
            case CHEAP -> w.setCost(w.getCost() * 2.5);
            case EXPENSIVE -> w.setCost(w.getCost() * 0.5); // 비싸도 괜찮으면 비용 가중치 감소
            case BALANCED -> { /* 기본 가중치 유지 */ }
        }

        // 4. 기타 boolean 플래그
        if (answers.isLongReasoning()) {
            w.setReasoning(w.getReasoning() * 1.5);
        }
        if (answers.isNeedLowLatency()) {
            w.setSpeed(w.getSpeed() * 1.5); // 낮은 지연시간이 필요하면 속도 가중치 증가
        }
        if (answers.isRecentnessMatters()) {
            w.setRecency(w.getRecency() * 2.0);
        }

        return w;
    }
}