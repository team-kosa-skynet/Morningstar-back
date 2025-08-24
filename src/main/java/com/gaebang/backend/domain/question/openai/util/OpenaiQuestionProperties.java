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

        // 이미지 생성 url
        private final String createImageUrl = "https://api.openai.com/v1/images/generations";

        /**
         * 기본 모델 (하드코딩)
         */
        private final String defaultModel = "gpt-4o";

        /**
         * 사용할 모델 결정 (요청 모델 또는 기본 모델)
         */
        public String getModelToUse(String requestModel) {
                return (requestModel != null && !requestModel.trim().isEmpty()) ? requestModel : defaultModel;
        }

}
