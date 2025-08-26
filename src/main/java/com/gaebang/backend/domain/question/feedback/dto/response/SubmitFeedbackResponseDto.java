package com.gaebang.backend.domain.question.feedback.dto.response;

import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import com.gaebang.backend.domain.question.feedback.entity.ModelFeedback;

public record SubmitFeedbackResponseDto(
        Long feedbackId,
        Long memberId,
        String positiveModel,
        String negativeModel,
        FeedbackCategory positiveFeedback,
        FeedbackCategory negativeFeedback,
        String detailedComment
) {

    public static SubmitFeedbackResponseDto fromEntity(ModelFeedback feedback) {
        return new SubmitFeedbackResponseDto(
                feedback.getFeedbackId(),
                feedback.getMember().getId(),
                feedback.getPositiveModel(),
                feedback.getNegativeModel(),
                feedback.getPositiveFeedback(),
                feedback.getNegativeFeedback(),
                feedback.getDetailedComment()
        );
    }
}