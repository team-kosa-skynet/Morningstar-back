package com.gaebang.backend.domain.conversation.dto.response;

import lombok.Builder;

import java.util.List;

/**
 * 대화방 목록 조회 API의 전체 응답 DTO
 * 사이드바에 표시할 모든 채팅방 정보를 담음
 */
@Builder
public record ConversationListResponseDto(
        /**
         * 사용자의 모든 활성 대화방 목록
         * 최신순으로 정렬되어 있음
         */
        List<ConversationSummaryDto> conversations,

        /**
         * 총 대화방 개수
         * 페이징이나 통계 표시용
         */
        Long totalCount
) {

    /**
     * 대화방 목록과 개수로부터 응답 DTO 생성
     *
     * @param conversations 대화방 요약 목록
     * @param totalCount 총 개수
     * @return 응답 DTO
     */
    public static ConversationListResponseDto of(List<ConversationSummaryDto> conversations, Long totalCount) {
        return ConversationListResponseDto.builder()
                .conversations(conversations)
                .totalCount(totalCount)
                .build();
    }
}
