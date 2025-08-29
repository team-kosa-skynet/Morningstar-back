package com.gaebang.backend.domain.aibot.entity;

/**
 * AI 제공자
 */
public enum AiProvider {
    GEMINI("Gemini"),
    OPENAI("OpenAI");

    private final String displayName;

    AiProvider(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}