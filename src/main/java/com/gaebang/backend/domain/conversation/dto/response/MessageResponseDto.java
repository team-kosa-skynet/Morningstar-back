package com.gaebang.backend.domain.conversation.dto.response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.conversation.entity.ConversationMessage;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 개별 메시지 정보 응답 DTO
 * 질문과 답변 모두에 사용되며, 프론트엔드에서 채팅 UI 렌더링에 사용
 */
@Builder
@Slf4j
public record MessageResponseDto(
        /**
         * 메시지 고유 ID
         */
        Long messageId,

        /**
         * 메시지 역할 ("user" 또는 "assistant")
         * 프론트엔드에서 말풍선 스타일 결정에 사용
         */
        String role,

        /**
         * 메시지 내용
         * 마크다운 형태일 수 있으므로 프론트엔드에서 파싱 필요
         */
        String content,

        /**
         * AI 모델명 (답변의 경우만)
         * "GPT-4로 생성됨" 같은 표시를 위해 사용
         */
        String aiModel,

        /**
         * 메시지 순서
         * 동일한 시간에 생성된 메시지들의 순서 보장
         */
        Integer messageOrder,

        /**
         * 메시지 생성 시간
         * "방금 전", "2분 전" 같은 상대 시간 표시용
         */
        LocalDateTime createdAt,

        /**
         * 첨부 파일 목록
         * 대화 맥락 유지를 위해 추가
         */
        List<FileAttachmentDto> attachments
) {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * ConversationMessage 엔티티로부터 응답 DTO 생성
     *
     * @param message 메시지 엔티티
     * @return 변환된 응답 DTO
     */
    public static MessageResponseDto from(ConversationMessage message) {
        List<FileAttachmentDto> attachmentList = parseAttachments(message.getAttachments());

        return MessageResponseDto.builder()
                .messageId(message.getMessageId())
                .role(message.getRole().getValue())
                .content(message.getContent())
                .aiModel(message.getAiModel())
                .messageOrder(message.getMessageOrder())
                .createdAt(message.getCreatedAt())
                .attachments(attachmentList)
                .build();
    }

    /**
     * JSON 형태의 attachments 문자열을 FileAttachmentDto 리스트로 변환
     */
    private static List<FileAttachmentDto> parseAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(attachmentsJson, new TypeReference<List<FileAttachmentDto>>() {});
        } catch (Exception e) {
            log.warn("첨부파일 JSON 파싱 실패: {}", attachmentsJson, e);
            return Collections.emptyList();
        }
    }

    /**
     * LLM API 호출용 메시지 형태로 변환
     * ConversationService에서 히스토리 생성 시 사용
     *
     * @return LLM API 표준 형식의 메시지 맵
     */
    public java.util.Map<String, Object> toLlmApiFormat() {
        java.util.Map<String, Object> apiMessage = new java.util.HashMap<>();
        apiMessage.put("role", this.role);
        
        // content에 메시지 순서 정보 포함하여 LLM이 대화 흐름을 더 잘 이해할 수 있도록 함
        String contentWithOrder = String.format("[메시지 %d] %s", this.messageOrder, this.content);
        apiMessage.put("content", contentWithOrder);
        apiMessage.put("attachments", this.attachments);
        return apiMessage;
    }
}
