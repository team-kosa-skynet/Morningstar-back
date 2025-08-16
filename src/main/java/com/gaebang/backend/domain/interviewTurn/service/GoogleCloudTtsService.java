package com.gaebang.backend.domain.interviewTurn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.dto.response.TtsPayloadDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;

@Slf4j
@Service
@Primary
public class GoogleCloudTtsService implements TtsService {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    private final String apiKey;
    private final String voiceName;
    private final String languageCode;
    private final double speakingRate;
    private final double pitch;
    
    public GoogleCloudTtsService(
            @Value("${tts.google.api.key}") String apiKey,
            @Value("${tts.google.voice-name:ko-KR-Neural2-A}") String voiceName,
            @Value("${tts.google.language-code:ko-KR}") String languageCode,
            @Value("${tts.google.speaking-rate:1.0}") double speakingRate,
            @Value("${tts.google.pitch:0.0}") double pitch,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.voiceName = voiceName;
        this.languageCode = languageCode;
        this.speakingRate = speakingRate;
        this.pitch = pitch;
        this.objectMapper = objectMapper;
        
        this.webClient = WebClient.builder()
                .baseUrl("https://texttospeech.googleapis.com")
                .exchangeStrategies(
                        ExchangeStrategies.builder()
                                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                                .build()
                )
                .build();
    }
    
    @Override
    public TtsPayloadDto synthesize(String text, String preferFormat) throws Exception {
        log.info("[GoogleCloudTTS] 음성 합성 시작: text length={}, format={}", text.length(), preferFormat);
        
        // preferFormat에 따라 실제 요청 포맷 결정
        String requestAudioEncoding = determineRequestEncoding(preferFormat);
        String requestBody = buildRequestBody(text, requestAudioEncoding);
        
        long startTime = System.nanoTime();
        
        String response = webClient.post()
                .uri("/v1/text:synthesize")
                .header("X-Goog-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("[GoogleCloudTTS] API 응답 완료: {} ms", elapsedMs);
        
        String audioContent = extractAudioContent(response);
        if (audioContent == null || audioContent.isEmpty()) {
            throw new IllegalStateException("Google Cloud TTS 응답에서 오디오 데이터가 비어있습니다.");
        }
        
        // 클라이언트에게 응답할 실제 포맷 결정
        String actualFormat = "LINEAR16".equals(requestAudioEncoding) ? "wav" : requestAudioEncoding.toLowerCase();
        
        log.info("[GoogleCloudTTS] 음성 합성 완료: format={}, size={} bytes", 
                actualFormat, Base64.getDecoder().decode(audioContent).length);
        
        return new TtsPayloadDto(actualFormat, audioContent);
    }
    
    private String buildRequestBody(String text, String encoding) {
        try {
            return objectMapper.writeValueAsString(new TtsRequest(
                    new TextInput(text),
                    new VoiceSelectionParams(languageCode, voiceName),
                    new AudioConfig(encoding, speakingRate, pitch)
            ));
        } catch (Exception e) {
            throw new RuntimeException("TTS 요청 본문 생성 실패", e);
        }
    }
    
    private String determineRequestEncoding(String preferFormat) {
        if ("wav".equalsIgnoreCase(preferFormat)) {
            return "LINEAR16"; // Google Cloud TTS에서 WAV는 LINEAR16
        }
        return "MP3"; // 기본값은 MP3
    }
    
    private String extractAudioContent(String jsonResponse) throws Exception {
        JsonNode root = objectMapper.readTree(jsonResponse);
        return root.path("audioContent").asText(null);
    }
    
    // DTO 클래스들
    private record TtsRequest(
            TextInput input,
            VoiceSelectionParams voice,
            AudioConfig audioConfig
    ) {}
    
    private record TextInput(String text) {}
    
    private record VoiceSelectionParams(
            String languageCode,
            String name
    ) {}
    
    private record AudioConfig(
            String audioEncoding,
            double speakingRate,
            double pitch
    ) {}
}