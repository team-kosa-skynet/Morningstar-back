package com.gaebang.backend.domain.conversation.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gaebang.backend.domain.conversation.entity.Conversation;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 특정 대화방의 상세 정보 조회 API 응답 DTO
 * 사용자가 특정 대화방을 선택했을 때 전체 대화 내역과 메타데이터를 제공
 */
@Builder
public record ConversationDetailResponseDto(
        /**
         * 대화방 고유 ID
         */
        Long conversationId,

        /**
         * 대화방 제목
         */
        String title,

        /**
         * 대화방 생성 시간
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        /**
         * 대화방 마지막 수정 시간
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastModifiedAt,

        /**
         * 해당 대화방의 모든 메시지들
         * 질문-답변이 시간순으로 정렬되어 제공
         */
        List<MessageResponseDto> messages,

        /**
         * 총 메시지 개수
         */
        Long totalMessageCount
) {

    /**
     * Conversation 엔티티와 메시지들로부터 상세 응답 DTO 생성
     *
     * @param conversation 대화방 엔티티
     * @param messages 메시지 응답 DTO 목록
     * @return 변환된 상세 응답 DTO
     */
    public static ConversationDetailResponseDto from(Conversation conversation, List<MessageResponseDto> messages) {
        return ConversationDetailResponseDto.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .lastModifiedAt(conversation.getUpdatedAt())
                .messages(messages)
                .totalMessageCount((long) messages.size())
                .build();
    }
}
