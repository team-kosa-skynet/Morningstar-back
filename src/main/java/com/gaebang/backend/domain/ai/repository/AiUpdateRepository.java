package com.gaebang.backend.domain.ai.repository;

import com.gaebang.backend.domain.ai.entity.AiUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiUpdateRepository extends JpaRepository<AiUpdate, Long> {
    List<AiUpdate> findTop20ByOrderByCreatedAtDesc();
}

