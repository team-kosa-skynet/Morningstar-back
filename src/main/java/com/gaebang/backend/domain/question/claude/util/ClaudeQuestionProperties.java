package com.gaebang.backend.domain.question.claude.util;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
@Getter
public class ClaudeQuestionProperties {
    private static Dotenv dotenv = Dotenv.load();
    private final String apiKey = dotenv.get("CLAUDE-API-KEY");
    private final String model = "claude-3-5-haiku-20241022";
    private final String responseUrl = "https://api.anthropic.com/v1/messages";
}
