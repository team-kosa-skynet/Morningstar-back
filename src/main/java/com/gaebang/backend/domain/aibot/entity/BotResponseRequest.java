package com.gaebang.backend.domain.aibot.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AI 봇 응답 요청 정보
 */
@Entity
@Table(name = "bot_response_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BotResponseRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bot_response_request_id")
    private Long id;

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(name = "requested_by")
    private Long requestedBy;  // 요청한 사용자 ID (null이면 자동 생성)

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "request_type", nullable = false)
    private RequestType requestType = RequestType.AUTO;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Builder.Default
    @Column(name = "priority")
    private Integer priority = 3;  // 처리 우선순위 (1=높음, 5=낮음)

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    // 비즈니스 메서드
    public void markAsProcessing() {
        this.status = RequestStatus.PROCESSING;
    }

    public void markAsCompleted() {
        this.status = RequestStatus.COMPLETED;
        this.processedAt = LocalDateTime.now();
    }

    public void markAsCancelled(String reason) {
        this.status = RequestStatus.CANCELLED;
        this.cancellationReason = reason;
        this.processedAt = LocalDateTime.now();
    }

    public boolean isManualRequest() {
        return requestType == RequestType.MANUAL;
    }
}