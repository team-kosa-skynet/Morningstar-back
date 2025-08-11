package com.gaebang.backend.domain.conversation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 대화방에 사용자 질문을 추가하는 요청 DTO
 * Question 서비스에서 ConversationService를 호출할 때 내부적으로 사용
 */
public record AddQuestionRequestDto(
        /**
         * 사용자가 입력한 질문 내용
         */
        @NotBlank(message = "질문 내용을 입력해주세요")
        @Size(max = 10000, message = "질문은 10000자 이하로 입력해주세요")
        String content
) {
}
