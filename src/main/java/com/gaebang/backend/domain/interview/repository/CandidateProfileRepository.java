package com.gaebang.backend.domain.interview.repository;

import com.gaebang.backend.domain.interview.entity.CandidateProfile;
import com.gaebang.backend.domain.interview.entity.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findBySession(InterviewSession session);
}
