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
    
    List<ModelFeedback> findByMemberIdOrderByCreatedAtDesc(Long memberId);
    
    List<ModelFeedback> findByModelNameOrderByCreatedAtDesc(String modelName);
    
    List<ModelFeedback> findByConversationIdOrderByCreatedAtDesc(Long conversationId);
    
    @Query("SELECT mf FROM ModelFeedback mf WHERE mf.feedbackCategory = :category ORDER BY mf.createdAt DESC")
    List<ModelFeedback> findByFeedbackCategoryOrderByCreatedAtDesc(@Param("category") FeedbackCategory category);
    
    @Query("SELECT COUNT(mf) FROM ModelFeedback mf WHERE mf.modelName = :modelName AND mf.feedbackCategory IN :positiveCategories")
    Long countPositiveFeedbackByModelName(@Param("modelName") String modelName, @Param("positiveCategories") List<FeedbackCategory> positiveCategories);
    
    @Query("SELECT COUNT(mf) FROM ModelFeedback mf WHERE mf.modelName = :modelName AND mf.feedbackCategory IN :negativeCategories")
    Long countNegativeFeedbackByModelName(@Param("modelName") String modelName, @Param("negativeCategories") List<FeedbackCategory> negativeCategories);
}