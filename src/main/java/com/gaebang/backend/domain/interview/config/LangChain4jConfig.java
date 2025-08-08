package com.gaebang.backend.domain.interview.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    private static Dotenv dotenv = Dotenv.load();

    private String openAiApiKey = dotenv.get("OPENAI-API-KEY");

    @Bean
    public ChatModel chatLanguageModel() {
        System.out.println("API Key 값 확인: " + (openAiApiKey != null ? "있음" : "없음"));
        System.out.println("API Key 길이: " + (openAiApiKey != null ? openAiApiKey.length() : 0));

        ChatModel model = OpenAiChatModel.builder()
                .apiKey(openAiApiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.3)
                .maxTokens(5000)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(3)
                .logRequests(true)
                .logResponses(true)
                .build();

        return model;
    }
}
