package com.gaebang.backend.domain.interview.client;

import com.gaebang.backend.domain.interview.client.response.OpenAiRealtimeSessionResponseDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class OpenAiRealtimeClient {

    private final RestClient http;
    private final String model;
    private final String defaultVoice;

    public OpenAiRealtimeClient(
            @Value("${OPENAI_API_KEY}") String apiKey,
            @Value("${app.realtime.model}") String model,
            @Value("${app.realtime.voice:verse}") String defaultVoice
    ) {
        this.model = model;
        this.defaultVoice = defaultVoice;
        this.http = RestClient.builder()
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
