package com.gaebang.backend.domain.conversation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 대화방 제목 수정 요청 DTO
 * 사용자가 대화방 제목을 편집할 때 사용 (연필 아이콘 클릭 등)
 */
public record UpdateConversationTitleRequestDto(
        /**
         * 새로운 대화방 제목
         * 반드시 입력되어야 하며 빈 문자열 불가
         */
        @NotBlank(message = "제목을 입력해주세요")
        @Size(max = 200, message = "제목은 200자 이하로 입력해주세요")
        String title
) {
}
