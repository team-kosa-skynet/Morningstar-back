package com.gaebang.backend.domain.aianalysis.service;

import com.gaebang.backend.domain.aianalysis.dto.AIAnalysisDto;
import com.gaebang.backend.domain.aianalysis.dto.AIModelListResponseDto;
import com.gaebang.backend.domain.aianalysis.entity.AIModelIntegrated;
import com.gaebang.backend.domain.aianalysis.repository.AIAnalysisRepository;
import com.gaebang.backend.global.util.ResponseDTO;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;


@Service
@Transactional
public class AIAnalysisService {

    private final AIAnalysisRepository aiAnalysisRepository;
    private static final Logger log = LoggerFactory.getLogger(AIAnalysisService.class);
    private final WebClient webClient;

    private static final Dotenv dotenv = Dotenv.load();
    private static final String apiUrl = dotenv.get("AI_ANALYSIS_URL");
    private static final String apiKey = dotenv.get("AI_ANALYSIS_KEY");

    public AIAnalysisService(WebClient.Builder webClientBuilder, AIAnalysisRepository repository) {
        this.webClient = webClientBuilder
                .baseUrl(apiUrl)
                .defaultHeader("x-api-key", apiKey)
                .build();
        this.aiAnalysisRepository = repository;
    }

    /**
     * 외부 API에서 모델 정보를 가져와 DB에 저장하거나 업데이트합니다.
     */
    public Mono<Void> fetchAndSaveModels() {
        log.info("AI 모델 정보 동기화를 시작합니다...");

        return webClient.get()
                .uri("")
                .retrieve()
                .bodyToMono(AIAnalysisDto.class) // bodyToMono에 통합 DTO 클래스를 지정
                .doOnSuccess(apiResponse -> {
                    if (apiResponse != null && apiResponse.data() != null) {
                        List<AIModelIntegrated> modelsToSave = apiResponse.data().stream()
                                .map(this::mapDtoToEntity) // mapDtoToEntity는 이제 AIAnalysisDto.ModelData를 인자로 받습니다.
                                .collect(Collectors.toList());

                        aiAnalysisRepository.saveAll(modelsToSave);
                        log.info("{}개의 모델 정보가 성공적으로 동기화되었습니다.", modelsToSave.size());
                    } else {
                        log.warn("API로부터 유효한 데이터를 받지 못했습니다.");
                    }
                })
                .doOnError(error -> log.error("API 호출 중 오류 발생: {}", error.getMessage()))
                .then();
    }

    // 파라미터 타입을 AIAnalysisDto의 중첩 레코드인 ModelData로 변경합니다.
    private AIModelIntegrated mapDtoToEntity(AIAnalysisDto.ModelData dto) {
        AIModelIntegrated entity = new AIModelIntegrated();

        // --- 기본 정보 매핑 ---
        entity.setModelId(dto.id());
        entity.setModelName(dto.name());
        entity.setModelSlug(dto.slug());
        if (dto.releaseDate() != null) {
            entity.setReleaseDate(LocalDate.parse(dto.releaseDate()));
        }

        // --- 제작사 정보 매핑 (Null-safe) ---
        if (dto.modelCreator() != null) {
            entity.setCreatorId(dto.modelCreator().id());
            entity.setCreatorName(dto.modelCreator().name());
            entity.setCreatorSlug(dto.modelCreator().slug());
        }

        // --- 가격 정보 매핑 (Null-safe) ---
        if (dto.pricing() != null) {
            entity.setPrice1mInputTokens(dto.pricing().price1mInputTokens());
            entity.setPrice1mOutputTokens(dto.pricing().price1mOutputTokens());
            entity.setPrice1mBlended(dto.pricing().price1mBlended3To1());
        }

        // --- 평가 지표 매핑 (Null-safe) ---
        if (dto.evaluations() != null) {
            entity.setArtificialAnalysisIntelligenceIndex(dto.evaluations().artificialAnalysisIntelligenceIndex());
            entity.setArtificialAnalysisCodingIndex(dto.evaluations().artificialAnalysisCodingIndex());
            entity.setArtificialAnalysisMathIndex(dto.evaluations().artificialAnalysisMathIndex());
            entity.setMmluPro(dto.evaluations().mmluPro());
            entity.setGpqa(dto.evaluations().gpqa());
            entity.setHle(dto.evaluations().hle());
            entity.setLivecodebench(dto.evaluations().livecodebench());
            entity.setScicode(dto.evaluations().scicode());
            entity.setMath500(dto.evaluations().math500());
            entity.setAime(dto.evaluations().aime());
            entity.setAime25(dto.evaluations().aime25());
            entity.setIfbench(dto.evaluations().ifbench());
            entity.setLcr(dto.evaluations().lcr());
        }

        // --- 속도 지표 매핑 ---
        entity.setMedianOutputTokensPerSecond(dto.medianOutputTokensPerSecond());
        entity.setMedianTimeToFirstTokenSeconds(dto.medianTimeToFirstTokenSeconds());

        return entity;
    }

    public ResponseDTO<AIModelListResponseDto> getAIListByIntelligence() {

        List<AIModelIntegrated> aiModelIntegratedList =
                aiAnalysisRepository.findTop100ByOrderByArtificialAnalysisIntelligenceIndexDesc();

        AIModelListResponseDto responseData = AIModelListResponseDto.from(aiModelIntegratedList);

        return ResponseDTO.okWithData(responseData,
                "종합 지능순이 높은 순으로 AI리스트 조회에 성공했습니다");
    }
}