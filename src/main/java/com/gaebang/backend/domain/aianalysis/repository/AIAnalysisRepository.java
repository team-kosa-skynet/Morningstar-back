package com.gaebang.backend.domain.aianalysis.repository;

import com.gaebang.backend.domain.aianalysis.entity.AIModelIntegrated;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AIAnalysisRepository
        extends JpaRepository<AIModelIntegrated, Long> {

}
