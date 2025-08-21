package com.gaebang.backend.domain.community.dto;

/**
 * 컨텐츠 검열 결과를 나타내는 DTO
 * OpenAI, Gemini 등 다양한 AI Gateway에서 재사용 가능
 */
public class ModerationResult {
    private final boolean inappropriate;
    private final String reason;

    public ModerationResult(boolean inappropriate, String reason) {
        this.inappropriate = inappropriate;
        this.reason = reason;
    }

    public boolean isInappropriate() {
        return inappropriate;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ModerationResult{" +
                "inappropriate=" + inappropriate +
                ", reason='" + reason + '\'' +
                '}';
    }
}