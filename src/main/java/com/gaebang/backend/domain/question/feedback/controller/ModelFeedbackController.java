package com.gaebang.backend.domain.question.feedback.controller;

import com.gaebang.backend.domain.question.feedback.dto.request.SubmitFeedbackRequestDto;
import com.gaebang.backend.domain.question.feedback.dto.response.FeedbackOptionsResponseDto;
import com.gaebang.backend.domain.question.feedback.dto.response.SubmitFeedbackResponseDto;
import com.gaebang.backend.domain.question.feedback.service.ModelFeedbackService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Slf4j
public class ModelFeedbackController {
    
    private final ModelFeedbackService modelFeedbackService;
    
    /**
     * 특정 모델에 대한 피드백 옵션 조회
     */
    @GetMapping("/options")
    public ResponseEntity<ResponseDTO<FeedbackOptionsResponseDto>> getFeedbackOptions(
            @RequestParam String modelName
    ) {
        log.info("피드백 옵션 조회 API 호출 - 모델: {}", modelName);
        
        FeedbackOptionsResponseDto response = modelFeedbackService.getFeedbackOptions(modelName);
        
        return ResponseEntity.ok(ResponseDTO.success(response));
    }
    
    /**
     * 모델 피드백 제출
     */
    @PostMapping("/submit")
    public ResponseEntity<ResponseDTO<SubmitFeedbackResponseDto>> submitFeedback(
            @RequestBody @Valid SubmitFeedbackRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("피드백 제출 API 호출 - 모델: {}, 카테고리: {}", 
                requestDto.modelName(), requestDto.feedbackCategory());
        
        SubmitFeedbackResponseDto response = modelFeedbackService.submitFeedback(requestDto, principalDetails);
        
        return ResponseEntity.ok(ResponseDTO.success(response));
    }
    
    /**
     * 특정 모델의 긍정적 피드백 수 조회
     */
    @GetMapping("/stats/positive")
    public ResponseEntity<ResponseDTO<Long>> getPositiveFeedbackCount(
            @RequestParam String modelName
    ) {
        log.info("긍정 피드백 수 조회 API 호출 - 모델: {}", modelName);
        
        Long count = modelFeedbackService.getPositiveFeedbackCount(modelName);
        
        return ResponseEntity.ok(ResponseDTO.success(count));
    }
    
    /**
     * 특정 모델의 부정적 피드백 수 조회
     */
    @GetMapping("/stats/negative")
    public ResponseEntity<ResponseDTO<Long>> getNegativeFeedbackCount(
            @RequestParam String modelName
    ) {
        log.info("부정 피드백 수 조회 API 호출 - 모델: {}", modelName);
        
        Long count = modelFeedbackService.getNegativeFeedbackCount(modelName);
        
        return ResponseEntity.ok(ResponseDTO.success(count));
    }
}