package com.gaebang.backend.domain.question.openai.repository;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.question.openai.entity.QuestionSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface QuestionRepository extends JpaRepository<QuestionSession, Long> {

    Optional<QuestionSession> findByMemberAndIsActiveTrueAndLastUsedAtAfter(
            Member member,
            LocalDateTime cutoffTime
    );

    @Modifying
    @Query("UPDATE QuestionSession cs SET cs.isActive = false WHERE cs.member = :member")
    void deactivateAllByMember(@Param("member") Member member);

    @Modifying
    @Query("UPDATE QuestionSession cs SET cs.lastUsedAt = :now WHERE cs.member = :member AND cs.isActive = true")
    void updateLastUsedByMember(@Param("member") Member member, @Param("now") LocalDateTime now);
}