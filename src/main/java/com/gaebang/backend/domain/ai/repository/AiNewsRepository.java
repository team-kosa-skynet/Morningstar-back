package com.gaebang.backend.domain.ai.repository;

import com.gaebang.backend.domain.ai.entity.AiNews;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiNewsRepository extends JpaRepository<AiNews, Long> {
    List<AiNews> findTop10ByOrderByCreatedAtDesc();
}
