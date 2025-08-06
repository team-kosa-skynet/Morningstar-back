package com.gaebang.backend.domain.question.gemini.util;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
@Getter
public class GeminiQuestionProperties {
    private static Dotenv dotenv = Dotenv.load();
    private final String apiKey = dotenv.get("GEMINI-API-KEY");
    // gemini-2.0-flash-lite gemini-2.5-flash  gemini-2.5-flash-lite gemini-2.0-flash gemini-2.5-pro
    private final String model = "gemini-2.0-flash-lite";
    private final String responseUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse";
}