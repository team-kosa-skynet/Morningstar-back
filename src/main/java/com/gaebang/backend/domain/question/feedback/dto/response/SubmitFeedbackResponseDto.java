package com.gaebang.backend.domain.question.feedback.dto.response;

import lombok.Builder;

@Builder
public record SubmitFeedbackResponseDto(
        Long feedbackId,
        String message
) {
    
    public static SubmitFeedbackResponseDto success(Long feedbackId) {
        return SubmitFeedbackResponseDto.builder()
                .feedbackId(feedbackId)
                .message("피드백이 성공적으로 저장되었습니다.")
                .build();
    }
}