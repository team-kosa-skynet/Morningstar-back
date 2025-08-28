package com.gaebang.backend.domain.aibot.entity;

/**
 * AI 봇 응답 상태
 */
public enum ResponseStatus {
    PENDING("답변 생성 대기"),
    GENERATED("답변 생성 완료"),
    MODERATED("검열 완료"),
    POSTED("게시 완료"),
    FAILED("생성 실패");

    private final String description;

    ResponseStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}