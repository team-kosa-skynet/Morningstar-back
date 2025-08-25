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

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_category", nullable = false)
    private FeedbackCategory feedbackCategory;

    @Column(name = "detailed_comment", length = 1000)
    private String detailedComment;

    public FeedbackCategory.FeedbackType getFeedbackType() {
        return feedbackCategory.getFeedbackType();
    }
}