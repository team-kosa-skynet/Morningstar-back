package com.gaebang.backend.domain.question.openai.util;


import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
@Getter
public class OpenaiQuestionProperties {

        private static Dotenv dotenv = Dotenv.load();
        private final String apiKey = dotenv.get("OPENAI-API-KEY");
        private final String model = "gpt-4.1-nano";
        private final String responseUrl = "https://api.openai.com/v1/responses";

}
