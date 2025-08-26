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
    public ResponseEntity<ResponseDTO<FeedbackOptionsResponseDto>> getFeedbackOptions() {
        
        FeedbackOptionsResponseDto response = modelFeedbackService.getFeedbackOptions();
        ResponseDTO<FeedbackOptionsResponseDto> responseDto = ResponseDTO.okWithData(response);
        
        return ResponseEntity
                .status(responseDto.getCode())
                .body(responseDto);
    }
    
    /**
     * 모델 피드백 제출
     */
    @PostMapping("/submit")
    public ResponseEntity<ResponseDTO<SubmitFeedbackResponseDto>> submitFeedback(
            @RequestBody @Valid SubmitFeedbackRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        SubmitFeedbackResponseDto response = modelFeedbackService.submitFeedback(requestDto, principalDetails);
        ResponseDTO<SubmitFeedbackResponseDto> responseDto = ResponseDTO.okWithData(response);
        
        return ResponseEntity
                .status(responseDto.getCode())
                .body(responseDto);
    }
    
    /**
     * 특정 모델의 긍정적 피드백 수 조회 (아직 안쓰는 기능)
     */
    @GetMapping("/stats/positive")
    public ResponseEntity<ResponseDTO<Long>> getPositiveFeedbackCount(
            @RequestParam String modelName
    ) {
        
        Long count = modelFeedbackService.getPositiveFeedbackCount(modelName);
        ResponseDTO<Long> responseDto = ResponseDTO.okWithData(count);
        
        return ResponseEntity
                .status(responseDto.getCode())
                .body(responseDto);
    }
    
    /**
     * 특정 모델의 부정적 피드백 수 조회 (아직 안쓰는 기능)
     */
    @GetMapping("/stats/negative")
    public ResponseEntity<ResponseDTO<Long>> getNegativeFeedbackCount(
            @RequestParam String modelName
    ) {
        
        Long count = modelFeedbackService.getNegativeFeedbackCount(modelName);
        ResponseDTO<Long> responseDto = ResponseDTO.okWithData(count);
        
        return ResponseEntity
                .status(responseDto.getCode())
                .body(responseDto);
    }
}