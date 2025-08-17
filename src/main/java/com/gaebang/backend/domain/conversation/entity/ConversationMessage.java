package com.gaebang.backend.domain.conversation.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대화 메시지 엔티티
 * 사용자의 질문과 AI의 답변을 모두 저장
 */
@Entity
@Table(name = "conversation_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    /**
     * 메시지가 속한 대화방
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /**
     * 메시지 역할 (user: 사용자 질문, assistant: AI 답변)
     * LLM API의 messages 형식과 동일하게 맞춤
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    /**
     * 메시지 내용 (질문 또는 답변)
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 답변을 생성한 AI 모델 (질문의 경우 null)
     * 예: "gpt-4", "claude-3", "gemini-pro"
     */
    @Column(length = 50)
    private String aiModel;

    /**
     * 메시지 순서 (같은 대화방 내에서의 순서)
     * 질문-답변 쌍이 시간순으로 정렬되도록 관리
     */
    @Column(nullable = false)
    private Integer messageOrder;

    /**
     * 메시지 생성자
     * @param conversation 속한 대화방
     * @param role 메시지 역할 (user/assistant)
     * @param content 메시지 내용
     * @param aiModel AI 모델명 (답변의 경우)
     * @param messageOrder 메시지 순서
     */
    @Builder
    public ConversationMessage(Conversation conversation, MessageRole role,
                               String content, String aiModel, Integer messageOrder) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.aiModel = aiModel;
        this.messageOrder = messageOrder;
    }

    /**
     * 대화방 설정 (연관관계 편의 메서드)
     * @param conversation 설정할 대화방
     */
    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }
}
