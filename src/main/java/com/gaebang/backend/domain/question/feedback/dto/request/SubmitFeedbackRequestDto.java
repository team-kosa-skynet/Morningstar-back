package com.gaebang.backend.domain.question.feedback.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record SubmitFeedbackRequestDto(
        
        @NotBlank(message = "모델명은 필수입니다.")
        String modelName,
        
        @NotNull(message = "대화 ID는 필수입니다.")
        Long conversationId,
        
        @NotBlank(message = "피드백 카테고리는 필수입니다.")
        String feedbackCategory,
        
        @Size(max = 1000, message = "상세 의견은 1000자 이하로 입력해주세요.")
        String detailedComment
) {
}