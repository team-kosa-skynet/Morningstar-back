package com.gaebang.backend.domain.interview.client;

import com.gaebang.backend.domain.interview.client.response.OpenAiRealtimeSessionResponseDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class OpenAiRealtimeClient {

    private final RestClient http;
    private final String model;
    private final String defaultVoice;

    public OpenAiRealtimeClient(
            // 권장: spring.ai.openai.api-key 로 받되, yml에서 ${OPENAI_API_KEY}로 연결
            @Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}") String apiKeyRaw,
            @Value("${app.realtime.model}") String model,
            @Value("${app.realtime.voice:verse}") String defaultVoice
    ) {
        String apiKey = apiKeyRaw == null ? "" : apiKeyRaw.trim();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key is empty");
        }
        this.model = model;
        this.defaultVoice = defaultVoice;

        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))   // 연결 타임아웃
                .version(HttpClient.Version.HTTP_2)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(30));   // 읽기 타임아웃

        this.http = RestClient.builder()
                .requestFactory(factory)
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("OpenAI-Beta", "realtime=v1")
                .build();
    }

    /**
     * OpenAI Realtime 세션(ephemeral) 생성
     */
    public OpenAiRealtimeSessionResponseDto createSession(String instructions) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("voice", defaultVoice);
        body.put("instructions", instructions);

        return http.post()
                .uri("/v1/realtime/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OpenAiRealtimeSessionResponseDto.class);
    }
}
