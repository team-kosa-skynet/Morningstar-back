package com.gaebang.backend.domain.question.feedback.entity;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "model_feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ModelFeedback extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_id")
    private Long feedbackId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // 긍정적으로 평가한 모델명
    @Column(name = "positive_model")
    private String positiveModel;

    // 부정적으로 평가한 모델명
    @Column(name = "negative_model")
    private String negativeModel;

    // 긍정적 피드백 카테고리
    @Enumerated(EnumType.STRING)
    @Column(name = "positive_feedback")
    private FeedbackCategory positiveFeedback;

    // 부정적 피드백 카테고리
    @Enumerated(EnumType.STRING)
    @Column(name = "negative_feedback")
    private FeedbackCategory negativeFeedback;

    @Column(name = "detailed_comment", length = 1000)
    private String detailedComment;
}