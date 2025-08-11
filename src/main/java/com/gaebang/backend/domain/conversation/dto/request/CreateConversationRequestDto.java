package com.gaebang.backend.domain.conversation.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 새로운 대화방 생성 요청 DTO
 * 사용자가 "새 채팅" 버튼을 눌렀을 때 사용
 */
public record CreateConversationRequestDto(
        /**
         * 대화방 제목
         * 사용자가 직접 입력하거나, 비어있으면 첫 질문으로 자동 생성
         * 선택사항이므로 @NotBlank 없음
         */
        @Size(max = 200, message = "제목은 200자 이하로 입력해주세요")
        String title
) {
}
