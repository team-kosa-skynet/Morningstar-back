package com.gaebang.backend.domain.conversation.dto.response;

import com.gaebang.backend.domain.conversation.entity.Conversation;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 사이드바 목록에 표시될 개별 대화방의 요약 정보 DTO
 * 각 채팅방의 기본 정보만 포함 (상세 내용 제외)
 */
@Builder
public record ConversationSummaryDto(
        /**
         * 대화방 고유 ID
         * 클릭 시 해당 대화방으로 이동하는 용도
         */
        Long conversationId,

        /**
         * 대화방 제목
         * 사이드바에 표시될 제목
         */
        String title,

        /**
         * 마지막 수정 시간
         * "방금 전", "2시간 전", "어제" 같은 형태로 변환되어 표시
         */
        LocalDateTime lastModifiedAt,

        /**
         * 해당 대화방의 총 메시지 개수
         * 대화 길이를 나타내는 지표 (UI에 작은 숫자로 표시 가능)
         */
        Long messageCount,

        /**
         * 마지막 메시지의 일부 미리보기
         * 사이드바에서 대화 내용을 간략히 보여주는 용도 (선택사항)
         */
        String lastMessagePreview
) {

    /**
     * Conversation 엔티티로부터 요약 DTO를 생성
     *
     * @param conversation 대화방 엔티티
     * @param messageCount 메시지 개수
     * @param lastMessagePreview 마지막 메시지 미리보기
     * @return 변환된 요약 DTO
     */
    public static ConversationSummaryDto from(Conversation conversation, Long messageCount, String lastMessagePreview) {
        return ConversationSummaryDto.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .lastModifiedAt(conversation.getUpdatedAt())
                .messageCount(messageCount)
                .lastMessagePreview(lastMessagePreview)
                .build();
    }
}
