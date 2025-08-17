package com.gaebang.backend.domain.conversation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 대화방에 AI 답변을 추가하는 요청 DTO
 * 사용자가 3개 답변 중 하나를 선택했을 때 ConversationService에 저장하는 용도
 */
public record AddAnswerRequestDto(
        /**
         * AI가 생성한 답변 내용
         */
        @NotBlank(message = "답변 내용이 필요합니다")
        String content,

        /**
         * 답변을 생성한 AI 모델명
         * 예: "gpt-4", "claude-3-sonnet", "gemini-pro"
         */
        @NotBlank(message = "AI 모델명이 필요합니다")
        @Size(max = 50, message = "모델명은 50자 이하로 입력해주세요")
        String aiModel
) {
}
