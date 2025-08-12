package com.gaebang.backend.domain.interviewTurn.repository;

import com.gaebang.backend.domain.interviewTurn.entity.InterviewsSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewsSessionRepository extends JpaRepository<InterviewsSession, UUID> {

    List<InterviewsSession> findByMemberIdOrderByCreatedAtDesc(Long memberId);

}
