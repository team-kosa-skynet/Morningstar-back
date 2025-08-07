package com.gaebang.backend.domain.conversation.entity;

/**
 * 메시지의 역할을 나타내는 열거형
 * LLM API의 표준 역할 구분과 동일
 */
public enum MessageRole {
    /**
     * 사용자가 보낸 질문
     */
    USER("user"),

    /**
     * AI가 생성한 답변
     */
    ASSISTANT("assistant");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    /**
     * LLM API 호출 시 사용할 문자열 값 반환
     * @return role 문자열 ("user" 또는 "assistant")
     */
    public String getValue() {
        return value;
    }
}
