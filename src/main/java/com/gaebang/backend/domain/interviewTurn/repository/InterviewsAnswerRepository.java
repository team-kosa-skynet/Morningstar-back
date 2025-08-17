package com.gaebang.backend.domain.interviewTurn.repository;

import com.gaebang.backend.domain.interviewTurn.entity.InterviewsAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewsAnswerRepository extends JpaRepository<InterviewsAnswer, Long> {
    boolean existsBySession_IdAndQuestionIndex(UUID sessionId, int questionIndex);
    List<InterviewsAnswer> findBySession_IdOrderByQuestionIndexAsc(UUID sessionId);
}
