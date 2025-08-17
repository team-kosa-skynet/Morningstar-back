package com.gaebang.backend.domain.conversation.dto.response;

import com.gaebang.backend.domain.conversation.entity.Conversation;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 새 대화방 생성 API 응답 DTO
 * 대화방 생성 후 클라이언트에게 생성된 대화방 정보를 전달
 */
@Builder
public record CreateConversationResponseDto(
        /**
         * 새로 생성된 대화방 ID
         * 프론트엔드에서 이 ID로 리다이렉트하거나 상태 업데이트
         */
        Long conversationId,

        /**
         * 생성된 대화방 제목
         * 사용자 입력 또는 자동 생성된 제목
         */
        String title,

        /**
         * 대화방 생성 시간
         */
        LocalDateTime createdAt
) {

    /**
     * Conversation 엔티티로부터 생성 응답 DTO 생성
     *
     * @param conversation 생성된 대화방 엔티티
     * @return 변환된 생성 응답 DTO
     */
    public static CreateConversationResponseDto from(Conversation conversation) {
        return CreateConversationResponseDto.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .build();
    }
}
