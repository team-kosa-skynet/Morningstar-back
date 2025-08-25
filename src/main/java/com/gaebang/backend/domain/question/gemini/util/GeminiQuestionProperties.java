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
     * 이미지 생성 기본 모델
     */
    private final String defaultImageModel = "imagen-3.0";

    /**
     * 이미지 생성 URL 생성 (모델에 따라)
     */
    public String getCreateImageUrl(String model) {
        String modelToUse = (model != null && !model.trim().isEmpty()) ? model : defaultImageModel;
        return "https://generativelanguage.googleapis.com/v1beta/models/" + modelToUse + "-generate-002:predict";
    }

    /**
     * 기본 이미지 생성 URL
     */
    public String getCreateImageUrl() {
        return getCreateImageUrl(defaultImageModel);
    }
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
