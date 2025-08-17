package com.gaebang.backend.domain.interview.repository;

import com.gaebang.backend.domain.interview.entity.InterviewAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswer, Long> {
    boolean existsBySession_IdAndQuestionIndex(UUID sessionId, int questionIndex);
    List<InterviewAnswer> findBySession_IdOrderByQuestionIndexAsc(UUID sessionId);
}
