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
     * 특정 사용자의 활성화된 모든 대화방을 최근 수정일 순으로 조회
     */
    @Query("SELECT c FROM Conversation c WHERE c.member.id = :memberId AND c.isActive = true ORDER BY c.updatedAt DESC")
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

    Optional<Conversation> findByConversationIdAndMemberIdAndIsActiveTrue(Long conversationId, Long memberId);
}
