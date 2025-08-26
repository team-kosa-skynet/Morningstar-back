package com.gaebang.backend.domain.question.feedback.dto.request;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.question.common.entity.AiModel;
import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import com.gaebang.backend.domain.question.feedback.entity.ModelFeedback;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;

@Builder
public record SubmitFeedbackRequestDto(

        // 긍정적으로 평가할 모델
        String positiveModel,

        // 부정적으로 평가할 모델
        String negativeModel,

        // 긍정적 피드백 (하나만 선택)
        String positiveFeedback,

        // 부정적 피드백 (하나만 선택)
        String negativeFeedback,

        @Size(max = 1000, message = "상세 의견은 1000자 이하로 입력해주세요.")
        String detailedComment
) {

    // 피드백 엔티티 생성
    public ModelFeedback toEntity(Member member) {
        return ModelFeedback.builder()
                .member(member)
                .positiveModel(positiveModel)
                .negativeModel(negativeModel)
                .positiveFeedback(positiveFeedback != null ? FeedbackCategory.valueOf(positiveFeedback) : null)
                .negativeFeedback(negativeFeedback != null ? FeedbackCategory.valueOf(negativeFeedback) : null)
                .detailedComment(detailedComment)
                .build();
    }
}