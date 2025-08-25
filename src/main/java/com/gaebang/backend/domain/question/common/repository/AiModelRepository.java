package com.gaebang.backend.domain.question.common.repository;

import com.gaebang.backend.domain.question.common.entity.AiModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * AI 모델 정보 리포지토리
 */
@Repository
public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    /**
     * 활성화된 모든 모델 조회 (제공업체별 정렬)
     */
    List<AiModel> findByIsActiveTrueOrderByProviderAscModelNameAsc();

    /**
     * 특정 제공업체의 활성화된 모델들 조회
     */
    List<AiModel> findByProviderAndIsActiveTrueOrderByModelNameAsc(String provider);

    /**
     * 특정 제공업체의 기본 모델 조회
     */
    Optional<AiModel> findByProviderAndIsDefaultTrueAndIsActiveTrue(String provider);

    /**
     * 특정 모델명으로 활성화된 모델 조회
     */
    Optional<AiModel> findByModelNameAndIsActiveTrue(String modelName);

    /**
     * 특정 제공업체와 모델명으로 모델 조회
     */
    Optional<AiModel> findByProviderAndModelName(String provider, String modelName);

    /**
     * 제공업체별로 그룹화된 활성화된 모델 조회 (최적화된 쿼리)
     */
    @Query("SELECT m FROM AiModel m WHERE m.isActive = true ORDER BY m.provider, m.isDefault DESC, m.modelName")
    List<AiModel> findAllActiveModelsOrderedByProviderAndDefault();

    /**
     * 파일 업로드를 지원하는 활성화된 모델들 조회
     */
    List<AiModel> findBySupportsFilesTrueAndIsActiveTrueOrderByProviderAscModelNameAsc();
}