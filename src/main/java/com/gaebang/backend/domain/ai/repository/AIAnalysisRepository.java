package com.gaebang.backend.domain.ai.repository;

import com.gaebang.backend.domain.ai.entity.AIModelIntegrated;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AIAnalysisRepository
        extends JpaRepository<AIModelIntegrated, Long> {

    /**
     * 지능 지수(artificialAnalysisIntelligenceIndex)가 높은 순으로
     * 상위 100개의 AI 모델을 조회
     * @return 지능 지수 순으로 정렬된 AI 모델 리스트 (최대 100개)
     */
    List<AIModelIntegrated> findTop100ByOrderByArtificialAnalysisIntelligenceIndexDesc();
}
