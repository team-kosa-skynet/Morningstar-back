package com.gaebang.backend.domain.conversation.repository;

import com.gaebang.backend.domain.conversation.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 대화 메시지 엔티티에 대한 데이터베이스 접근 레포지토리
 */
@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /**
     * 특정 대화방의 모든 메시지를 시간순으로 조회
     * LLM API 호출 시 이전 대화 맥락을 전달하는 용도
     *
     * @param conversationId 대화방 ID
     * @return 해당 대화방의 모든 메시지 (시간순 정렬)
     */
    @Query("SELECT cm FROM ConversationMessage cm WHERE cm.conversation.conversationId = :conversationId ORDER BY cm.messageOrder ASC, cm.createdAt ASC")
    List<ConversationMessage> findMessagesByConversationIdOrderByOrder(@Param("conversationId") Long conversationId);

    /**
     * 특정 대화방의 최근 N개 메시지만 조회
     * 토큰 제한이 있을 때 최근 대화만 포함하는 용도
     *
     * @param conversationId 대화방 ID
     * @param limit 가져올 메시지 개수
     * @return 최근 N개 메시지 (시간순 정렬)
     */
    @Query(value = "SELECT cm FROM ConversationMessage cm WHERE cm.conversation.conversationId = :conversationId ORDER BY cm.messageOrder DESC, cm.createdAt DESC")
    List<ConversationMessage> findRecentMessagesByConversationId(@Param("conversationId") Long conversationId,
                                                                 @Param("limit") int limit);

    /**
     * 특정 대화방의 다음 메시지 순서 번호 조회
     * 새 메시지 추가 시 순서 번호를 결정하는 용도
     *
     * @param conversationId 대화방 ID
     * @return 다음 메시지 순서 번호 (기존 최대값 + 1)
     */
    @Query("SELECT COALESCE(MAX(cm.messageOrder), 0) + 1 FROM ConversationMessage cm WHERE cm.conversation.conversationId = :conversationId")
    Integer findNextMessageOrder(@Param("conversationId") Long conversationId);

    /**
     * 특정 대화방의 메시지 개수 조회
     * 대화 길이 확인이나 페이징 처리용
     *
     * @param conversationId 대화방 ID
     * @return 해당 대화방의 총 메시지 개수
     */
    @Query("SELECT COUNT(cm) FROM ConversationMessage cm WHERE cm.conversation.conversationId = :conversationId")
    Long countMessagesByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 특정 대화방에서 사용자 질문만 조회 (제목 생성용)
     * 첫 번째 질문으로 대화방 제목을 자동 생성할 때 사용
     *
     * @param conversationId 대화방 ID
     * @return 사용자 질문 메시지들만 (시간순 정렬)
     */
    @Query("SELECT cm FROM ConversationMessage cm WHERE cm.conversation.conversationId = :conversationId AND cm.role = 'USER' ORDER BY cm.messageOrder ASC")
    List<ConversationMessage> findUserQuestionsByConversationId(@Param("conversationId") Long conversationId);
}
