package com.gaebang.backend.domain.interview.repository;

import com.gaebang.backend.domain.interview.entity.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
}
