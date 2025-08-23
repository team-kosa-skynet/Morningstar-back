package com.gaebang.backend.domain.question.common.controller;

import com.gaebang.backend.domain.question.common.dto.response.ModelInfoResponseDto;
import com.gaebang.backend.domain.question.common.service.ModelInfoService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 모델 정보 조회 REST API Controller
 * 프론트엔드에서 사용할 AI 모델 목록과 각 모델의 지원 기능을 제공
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelInfoController {

    private final ModelInfoService modelInfoService;

    /**
     * 전체 AI 모델 정보 조회
     * 각 제공업체(Claude, Gemini, OpenAI)별 지원 모델과 파일 업로드 지원 여부를 반환
     * 
     * @return AI 모델 정보 (모델명, 파일 지원 여부, 설명 포함)
     */
    @GetMapping("/info")
    public ResponseEntity<ResponseDTO<ModelInfoResponseDto>> getModelInfo() {
        log.info("AI 모델 정보 조회 API 호출");

        ModelInfoResponseDto modelInfo = modelInfoService.getAllModelInfo();

        ResponseDTO<ModelInfoResponseDto> response = ResponseDTO.okWithData(modelInfo);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}