package com.gaebang.backend.domain.question.common.service;

import com.gaebang.backend.domain.question.common.dto.response.ModelDetailDto;
import com.gaebang.backend.domain.question.common.dto.response.ModelInfoResponseDto;
import com.gaebang.backend.domain.question.common.dto.response.ProviderModelsDto;
import com.gaebang.backend.domain.question.common.entity.AiModel;
import com.gaebang.backend.domain.question.common.repository.AiModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 모델 정보 조회 서비스
 * DB에서 AI 모델 정보를 조회하여 제공업체별로 구성
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ModelInfoService {

    private final AiModelRepository aiModelRepository;

    /**
     * 전체 AI 모델 정보 조회
     * DB에서 활성화된 모든 모델을 조회하여 제공업체별로 구성
     */
    public ModelInfoResponseDto getAllModelInfo() {
        log.info("전체 AI 모델 정보 조회 요청 (DB 기반)");

        // DB에서 활성화된 모든 모델 조회
        List<AiModel> activeModels = aiModelRepository.findAllActiveModelsOrderedByProviderAndDefault();

        // 제공업체별로 그룹화
        Map<String, List<AiModel>> modelsByProvider = activeModels.stream()
                .collect(Collectors.groupingBy(AiModel::getProvider));

        // 각 제공업체별 DTO 생성
        ProviderModelsDto claudeModels = buildProviderModels("claude", modelsByProvider.get("claude"));
        ProviderModelsDto geminiModels = buildProviderModels("gemini", modelsByProvider.get("gemini"));
        ProviderModelsDto openaiModels = buildProviderModels("openai", modelsByProvider.get("openai"));

        ModelInfoResponseDto response = new ModelInfoResponseDto(
                claudeModels,
                geminiModels,
                openaiModels
        );

        int claudeCount = claudeModels != null ? claudeModels.models().size() : 0;
        int geminiCount = geminiModels != null ? geminiModels.models().size() : 0;
        int openaiCount = openaiModels != null ? openaiModels.models().size() : 0;

        log.info("AI 모델 정보 조회 완료 - Claude: {}개, Gemini: {}개, OpenAI: {}개", 
                claudeCount, geminiCount, openaiCount);

        return response;
    }

    /**
     * 제공업체별 모델 정보 구성
     */
    private ProviderModelsDto buildProviderModels(String provider, List<AiModel> models) {
        if (models == null || models.isEmpty()) {
            log.warn("제공업체 '{}'에 대한 활성화된 모델이 없습니다", provider);
            return null;
        }

        // 기본 모델 찾기
        String defaultModel = models.stream()
                .filter(AiModel::getIsDefault)
                .findFirst()
                .map(AiModel::getModelName)
                .orElse(models.get(0).getModelName()); // 기본 모델이 없으면 첫 번째 모델을 기본으로

        // 모델 상세 정보 구성
        List<ModelDetailDto> modelDetails = models.stream()
                .map(model -> new ModelDetailDto(
                        model.getModelName(),
                        model.getSupportsFiles(),
                        model.getIsCreateImage()
                ))
                .toList();

        return new ProviderModelsDto(defaultModel, modelDetails);
    }
}