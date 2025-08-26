package com.gaebang.backend.domain.question.feedback.dto.request;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.question.common.entity.AiModel;
import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import com.gaebang.backend.domain.question.feedback.entity.ModelFeedback;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;

@Builder
public record SubmitFeedbackRequestDto(

        // 긍정적으로 평가할 모델 (필수)
        @NotBlank(message = "긍정적으로 평가할 모델은 필수입니다.")
        String positiveModel,

        // 부정적으로 평가할 모델 (필수)
        @NotBlank(message = "부정적으로 평가할 모델은 필수입니다.")
        String negativeModel,

        // 긍정적 피드백 (필수, 하나만 선택)
        @NotBlank(message = "긍정적 피드백은 필수입니다.")
        String positiveFeedback,

        // 부정적 피드백 (필수, 하나만 선택)
        @NotBlank(message = "부정적 피드백은 필수입니다.")
        String negativeFeedback,

        // 상세 의견 (선택사항)
        @Size(max = 1000, message = "상세 의견은 1000자 이하로 입력해주세요.")
        String detailedComment
) {

    // 피드백 엔티티 생성
    public ModelFeedback toEntity(Member member) {
        return ModelFeedback.builder()
                .member(member)
                .positiveModel(positiveModel)
                .negativeModel(negativeModel)
                .positiveFeedback(FeedbackCategory.valueOf(positiveFeedback))
                .negativeFeedback(FeedbackCategory.valueOf(negativeFeedback))
                .detailedComment(detailedComment)
                .build();
    }
}