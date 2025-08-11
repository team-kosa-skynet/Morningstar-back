package com.gaebang.backend.domain.conversation.entity;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 대화방(채팅방) 엔티티
 * 사용자별로 여러개의 독립적인 대화방을 가질 수 있음 (ChatGPT의 사이드바와 같은 개념)
 */
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id")
    private Long conversationId;

    /**
     * 대화방 제목
     * 보통 첫 번째 질문을 기반으로 자동 생성되거나 사용자가 직접 설정
     */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 대화방 소유자 (사용자)
     * 각 사용자는 자신만의 독립적인 대화방들을 가짐
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /**
     * 해당 대화방의 모든 메시지들
     * 질문과 답변이 시간순으로 저장됨
     */
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConversationMessage> messages = new ArrayList<>();

    /**
     * 대화방이 활성 상태인지 여부
     * false면 삭제된 대화방으로 간주 (soft delete)
     */
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * 대화방 생성자
     * @param title 대화방 제목
     * @param member 대화방 소유자
     */
    @Builder
    public Conversation(String title, Member member) {
        this.title = title;
        this.member = member;
        this.isActive = true;
    }

    /**
     * 대화방 제목 변경
     * @param title 새로운 제목
     */
    public void updateTitle(String title) {
        this.title = title;
    }

    /**
     * 대화방 비활성화 (소프트 삭제)
     * 실제로 DB에서 삭제하지 않고 isActive를 false로 변경
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 대화방에 새 메시지 추가
     * @param message 추가할 메시지
     */
    public void addMessage(ConversationMessage message) {
        this.messages.add(message);
        message.setConversation(this);
    }
}
