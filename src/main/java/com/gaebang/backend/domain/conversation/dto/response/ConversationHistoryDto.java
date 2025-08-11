package com.gaebang.backend.domain.conversation.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * LLM API 호출을 위한 대화 히스토리 DTO
 * Question 서비스에서 ConversationService로부터 받아서 LLM API에 전달
 * 외부 API로 노출되지 않는 내부용 DTO
 */
@Builder
public record ConversationHistoryDto(
        /**
         * 대화방 ID
         */
        Long conversationId,

        /**
         * LLM API 호출용 메시지 배열
         * [{"role": "user", "content": "질문"}, {"role": "assistant", "content": "답변"}] 형태
         */
        List<Map<String, Object>> messages,

        /**
         * 총 메시지 개수
         * 토큰 제한 관리용
         */
        Integer totalMessageCount
) {

    /**
     * 메시지 응답 DTO 목록으로부터 히스토리 DTO 생성
     *
     * @param conversationId 대화방 ID
     * @param messageResponseDtos 메시지 응답 DTO 목록
     * @return 변환된 히스토리 DTO
     */
    public static ConversationHistoryDto from(Long conversationId, List<MessageResponseDto> messageResponseDtos) {
        List<Map<String, Object>> llmMessages = messageResponseDtos.stream()
                .map(MessageResponseDto::toLlmApiFormat)
                .toList();

        return ConversationHistoryDto.builder()
                .conversationId(conversationId)
                .messages(llmMessages)
                .totalMessageCount(messageResponseDtos.size())
                .build();
    }
}
