package com.gaebang.backend.domain.conversation.repository;

import com.gaebang.backend.domain.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 특정 사용자의 활성화된 모든 대화방을 최근 메시지 시간순으로 조회
     */
    @Query("SELECT c FROM Conversation c " +
           "LEFT JOIN ConversationMessage m ON c.conversationId = m.conversation.conversationId " +
           "WHERE c.member.id = :memberId AND c.isActive = true " +
           "GROUP BY c.conversationId " +
           "ORDER BY MAX(m.createdAt) DESC NULLS LAST")
    List<Conversation> findActiveConversationsByMemberIdOrderByModifiedDateDesc(@Param("memberId") Long memberId);

    /**
     * 특정 사용자의 특정 대화방을 조회 (활성화된 것만)
     */
    @Query("SELECT c FROM Conversation c WHERE c.conversationId = :conversationId AND c.member.id = :memberId AND c.isActive = true")
    Optional<Conversation> findActiveConversationByIdAndMemberId(@Param("conversationId") Long conversationId,
                                                                 @Param("memberId") Long memberId);

    /**
     * 특정 사용자의 활성화된 대화방 개수 조회
     */
    @Query("SELECT COUNT(c) FROM Conversation c WHERE c.member.id = :memberId AND c.isActive = true")
    Long countActiveConversationsByMemberId(@Param("memberId") Long memberId);

    /**
     * 특정 사용자의 대화방 중 제목으로 검색
     */
    @Query("SELECT c FROM Conversation c WHERE c.member.id = :memberId AND c.isActive = true AND c.title LIKE %:keyword% ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveConversationsByMemberIdAndTitleContaining(@Param("memberId") Long memberId,
                                                                           @Param("keyword") String keyword);

    /**
     * 특정 사용자의 활성화된 대화방들과 각 대화방의 메시지 개수, 마지막 메시지를 한 번에 조회
     * N+1 문제 해결을 위한 최적화된 쿼리
     */
    @Query("SELECT c, COUNT(m), " +
           "(SELECT m2.content FROM ConversationMessage m2 " +
           "WHERE m2.conversation.conversationId = c.conversationId " +
           "ORDER BY m2.messageOrder DESC, m2.createdAt DESC LIMIT 1) as lastMessageContent " +
           "FROM Conversation c " +
           "LEFT JOIN ConversationMessage m ON c.conversationId = m.conversation.conversationId " +
           "WHERE c.member.id = :memberId AND c.isActive = true " +
           "GROUP BY c.conversationId " +
           "ORDER BY MAX(m.createdAt) DESC NULLS LAST")
    List<Object[]> findConversationSummariesByMemberId(@Param("memberId") Long memberId);

    Optional<Conversation> findByConversationIdAndMemberIdAndIsActiveTrue(Long conversationId, Long memberId);
}
