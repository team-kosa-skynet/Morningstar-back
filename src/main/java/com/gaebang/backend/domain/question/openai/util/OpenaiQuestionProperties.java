package com.gaebang.backend.domain.question.openai.util;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Getter
public class OpenaiQuestionProperties {

        /**
         * OpenAI API 키 (.env 파일에서 가져옴)
         */
        private static Dotenv dotenv = Dotenv.load();
        private final String apiKey = dotenv.get("OPENAI_API_KEY");

        /**
         * OpenAI API 응답 URL (하드코딩)
         */
        private final String responseUrl = "https://api.openai.com/v1/chat/completions";

        /**
         * 기본 모델 (하드코딩)
         */
        private final String defaultModel = "gpt-3.5-turbo";

        /**
         * 지원하는 OpenAI 모델 목록 (하드코딩)
         */
        private final List<String> supportedModels = List.of(
                "gpt-3.5-turbo",
                "gpt-4",
                "gpt-4o",
                "gpt-4-turbo",
                "gpt-4o-mini"
        );

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
