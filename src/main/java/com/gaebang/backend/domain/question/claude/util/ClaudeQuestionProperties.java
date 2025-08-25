package com.gaebang.backend.domain.question.claude.util;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Getter
public class ClaudeQuestionProperties {

    /**
     * Claude API 키 (.env 파일에서 가져옴)
     */
    private static Dotenv dotenv = Dotenv.load();
    private final String apiKey = dotenv.get("CLAUDE_API_KEY");

    /**
     * Claude API 응답 URL (하드코딩)
     */
    private final String responseUrl = "https://api.anthropic.com/v1/messages";

    /**
     * 기본 모델 (하드코딩)
     */
    private final String defaultModel = "claude-3-haiku-20240307";

    /**
     * 사용할 모델 결정 (요청 모델 또는 기본 모델)
     */
    public String getModelToUse(String requestModel) {
        return (requestModel != null && !requestModel.trim().isEmpty()) ? requestModel : defaultModel;
    }

}
