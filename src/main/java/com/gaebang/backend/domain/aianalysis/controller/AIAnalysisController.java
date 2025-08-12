package com.gaebang.backend.domain.aianalysis.controller;

import com.gaebang.backend.domain.aianalysis.service.AIAnalysisService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("api/analysis")
@RequiredArgsConstructor
public class AIAnalysisController {

    private final AIAnalysisService aiAnalysisService;

    @PostMapping("/default-data")
    public Mono<ResponseEntity<ResponseDTO<Void>>> saveDefaultData() {
        return aiAnalysisService.fetchAndSaveModels()
                .then(Mono.fromCallable(() -> {
                    // .then()을 사용해 서비스 작업이 끝난 후에 응답 객체를 생성
                    ResponseDTO<Void> response = ResponseDTO.okWithMessage("AI Analysis Successfully saved");
                    return ResponseEntity
                            .status(response.getCode())
                            .body(response);
                }));
    }
}