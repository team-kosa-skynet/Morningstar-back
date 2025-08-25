package com.gaebang.backend.domain.question.gemini.util;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Getter
public class GeminiQuestionProperties {

    /**
     * Gemini API 키 (.env 파일에서 가져옴)
     */
    private static Dotenv dotenv = Dotenv.load();
    private final String apiKey = dotenv.get("GEMINI_API_KEY");

    /**
     * Gemini API 기본 URL (하드코딩)
     */
    private final String baseUrl = "https://generativelanguage.googleapis.com/v1/models";

    /**
     * 이미지 생성 url
     */
//    private final String createImageUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-preview-image-generation:generateContent";
//    private final String createImageUrl = "https://generativelanguage.googleapis.com/v1beta/models/imagen-4.0-generate-preview-06-06:predict";
    // Imagen-3로 변경 (다른 모델명 시도)
    private final String createImageUrl = "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict";
    /**
     * 기본 모델 (하드코딩)
     */
    private final String defaultModel = "gemini-2.5-flash";

    /**
     * 특정 모델에 대한 스트리밍 URL 생성
     */
    public String getResponseUrl(String model) {
        return baseUrl + "/" + model + ":streamGenerateContent?alt=sse";
    }

    /**
     * 기본 모델에 대한 스트리밍 URL
     */
    public String getResponseUrl() {
        return getResponseUrl(defaultModel);
    }

    /**
     * 사용할 모델 결정 (요청 모델 또는 기본 모델)
     */
    public String getModelToUse(String requestModel) {
        return (requestModel != null && !requestModel.trim().isEmpty()) ? requestModel : defaultModel;
    }

}
