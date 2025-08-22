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
    private final String createImageUrl = "https://generativelanguage.googleapis.com/v1beta/models/imagen-4.0-generate-preview-06-06:predict";

    /**
     * 기본 모델 (하드코딩)
     */
    private final String defaultModel = "gemini-2.5-flash";

    /**
     * 지원하는 Gemini 모델 목록 (하드코딩)
     */
    private final List<String> supportedModels = List.of(
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-1.0-pro",
            "gemini-2.5-flash"
    );

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
     * 요청된 모델이 지원되는지 확인
     */
    public boolean isModelSupported(String model) {
        return model != null && supportedModels.contains(model);
    }

    /**
     * 사용할 모델 결정 (요청 모델 또는 기본 모델)
     */
    public String getModelToUse(String requestModel) {
        return isModelSupported(requestModel) ? requestModel : defaultModel;
    }

    /**
     * 기존 getModel() 메서드 호환성 유지
     */
    public String getModel() {
        return defaultModel;
    }

    /**
     * 지원 모델 목록 조회
     */
    public List<String> getSupportedModels() {
        return supportedModels;
    }
}
