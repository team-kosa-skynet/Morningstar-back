package com.gaebang.backend.domain.conversation.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversation_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String aiModel;

    @Column(nullable = false)
    private Integer messageOrder;

    @Column(columnDefinition = "JSON")
    private String attachments;

    @Builder
    public ConversationMessage(Conversation conversation, MessageRole role,
                               String content, String aiModel, Integer messageOrder,
                               String attachments) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.aiModel = aiModel;
        this.messageOrder = messageOrder;
        this.attachments = attachments;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }
}
