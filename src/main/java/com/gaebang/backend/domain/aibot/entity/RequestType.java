package com.gaebang.backend.domain.aibot.entity;

/**
 * AI 답변 요청 타입
 */
public enum RequestType {
    AUTO("자동 생성"),
    MANUAL("수동 요청");

    private final String description;

    RequestType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}