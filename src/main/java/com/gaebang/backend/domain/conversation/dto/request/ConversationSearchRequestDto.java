package com.gaebang.backend.domain.conversation.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 대화방 검색 요청 DTO
 * 사용자가 많은 대화방 중에서 특정 대화방을 찾고 싶을 때 사용
 */
public record ConversationSearchRequestDto(
        /**
         * 검색할 키워드
         * 대화방 제목에서 검색됨
         */
        @Size(max = 100, message = "검색어는 100자 이하로 입력해주세요")
        String keyword
) {
}
