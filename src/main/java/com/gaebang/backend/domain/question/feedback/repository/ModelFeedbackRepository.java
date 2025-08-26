package com.gaebang.backend.domain.question.feedback.repository;

import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import com.gaebang.backend.domain.question.feedback.entity.ModelFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelFeedbackRepository extends JpaRepository<ModelFeedback, Long> {

    // 회원별 피드백 조회
    List<ModelFeedback> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    // 특정 모델의 긍정적 피드백 수 조회 (해당 모델이 positiveModel인 경우)
    @Query("SELECT COUNT(mf) FROM ModelFeedback mf WHERE mf.positiveModel = :modelName AND mf.positiveFeedback IN :positiveCategories")
    Long countPositiveFeedbackByModelName(@Param("modelName") String modelName, @Param("positiveCategories") List<FeedbackCategory> positiveCategories);

    // 특정 모델의 부정적 피드백 수 조회 (해당 모델이 negativeModel인 경우)
    @Query("SELECT COUNT(mf) FROM ModelFeedback mf WHERE mf.negativeModel = :modelName AND mf.negativeFeedback IN :negativeCategories")
    Long countNegativeFeedbackByModelName(@Param("modelName") String modelName, @Param("negativeCategories") List<FeedbackCategory> negativeCategories);

    // 특정 모델에 대한 전체 피드백 조회 (긍정적이든 부정적이든)
    @Query("SELECT mf FROM ModelFeedback mf WHERE mf.positiveModel = :modelName OR mf.negativeModel = :modelName ORDER BY mf.createdAt DESC")
    List<ModelFeedback> findByModelNameOrderByCreatedAtDesc(@Param("modelName") String modelName);
}