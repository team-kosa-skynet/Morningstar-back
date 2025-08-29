package com.gaebang.backend.domain.aibot.entity;

/**
 * AI 답변 요청 상태
 */
public enum RequestStatus {
    PENDING("처리 대기"),
    PROCESSING("처리 중"),
    COMPLETED("처리 완료"),
    CANCELLED("취소됨");

    private final String description;

    RequestStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}