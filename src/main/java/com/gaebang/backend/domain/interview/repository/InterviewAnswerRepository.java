package com.gaebang.backend.domain.interview.repository;

import com.gaebang.backend.domain.interview.entity.InterviewAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswer, Long> {
}
