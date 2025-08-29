package com.gaebang.backend.domain.aibot.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AI 봇 응답 정보
 */
@Entity
@Table(name = "bot_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BotResponse extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bot_response_id")
    private Long id;

    @Column(name = "board_id", nullable = false)
    private Long boardId;  // Board와 느슨한 결합

    @Column(name = "question", length = 2000)
    private String question;  // 원본 질문 (분석용)

    @Column(name = "response", length = 5000)
    private String response;  // 생성된 답변

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false)
    private ResponseStatus status = ResponseStatus.PENDING;

    @Column(name = "confidence_score")
    private Double confidenceScore;  // AI 답변 신뢰도 (0.0 ~ 1.0)

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_provider")
    private AiProvider aiProvider;  // GEMINI, OPENAI

    @Column(name = "question_category")
    private String questionCategory;  // TECHNICAL, CAREER, GENERAL

    @Column(name = "comment_id")
    private Long commentId;  // 실제 게시된 댓글 ID

    @Column(name = "failure_reason")
    private String failureReason;  // 실패 시 원인

    @Column(name = "user_feedback_score")
    private Integer userFeedbackScore;  // 사용자 평가 (1-5)

    // 비즈니스 메서드
    public void markAsGenerated(String response, Double confidence) {
        this.response = response;
        this.confidenceScore = confidence;
        this.status = ResponseStatus.GENERATED;
    }

    public void markAsModerated() {
        this.status = ResponseStatus.MODERATED;
    }

    public void markAsPosted(Long commentId) {
        this.commentId = commentId;
        this.status = ResponseStatus.POSTED;
    }

    public void markAsFailed(String reason) {
        this.failureReason = reason;
        this.status = ResponseStatus.FAILED;
    }

    public boolean isHighConfidence() {
        return confidenceScore != null && confidenceScore >= 0.8;
    }

    public boolean isPosted() {
        return status == ResponseStatus.POSTED;
    }
}