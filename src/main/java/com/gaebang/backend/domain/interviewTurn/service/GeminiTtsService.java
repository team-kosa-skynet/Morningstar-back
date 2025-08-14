package com.gaebang.backend.domain.interviewTurn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.dto.response.TtsPayloadDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;

@Service
public class GeminiTtsService implements TtsService {
    private final WebClient webClient;
    private final ObjectMapper om;

    private final String apiKey;
    private final String model;
    private final String voiceName;
    private final int sampleRateHz;
    private final String styleInstruction;

    public GeminiTtsService(
            @Value("${tts.gemini.api.key}") String apiKey,
            @Value("${tts.gemini.model:gemini-2.5-flash-preview-tts}") String model,
            @Value("${tts.gemini.voice-name:Leda}") String voiceName,
            @Value("${tts.gemini.sample-rate-hz:16000}") int sampleRateHz,
            @Value("${tts.gemini.style-instruction:}") String styleInstruction,
            ObjectMapper om
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.voiceName = voiceName;
        this.sampleRateHz = sampleRateHz;
        this.styleInstruction = styleInstruction;
        this.webClient = WebClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .exchangeStrategies(
                        ExchangeStrategies.builder()
                                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                                .build()
                )
                .build();
        this.om = om;
    }

    @Override
    public TtsPayloadDto synthesize(String text, String preferFormat) throws Exception {
        String body = buildRequestBody(text);

        String resp = webClient.post()
                .uri("/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        String b64Pcm = extractBase64Pcm(resp);
        if (b64Pcm == null || b64Pcm.isEmpty()) {
            throw new IllegalStateException("Gemini TTS 응답에서 오디오 데이터가 비어있습니다.");
        }

        byte[] pcm = Base64.getDecoder().decode(b64Pcm);
        
        // 설정에 따라 포맷 결정 (기본: wav)
        if ("mp3".equalsIgnoreCase(preferFormat)) {
            // MP3는 복잡한 인코딩이 필요하므로 일단 WAV로 처리
            byte[] wav = wrapPcmToWav(pcm, sampleRateHz, (short) 1, (short) 16);
            String b64Wav = Base64.getEncoder().encodeToString(wav);
            return new TtsPayloadDto("wav", b64Wav);
        } else {
            // WAV 포맷
            byte[] wav = wrapPcmToWav(pcm, sampleRateHz, (short) 1, (short) 16);
            String b64Wav = Base64.getEncoder().encodeToString(wav);
            return new TtsPayloadDto("wav", b64Wav);
        }
    }

    private String buildRequestBody(String text) {
        // 스타일 가이드를 텍스트 앞에 자연스럽게 추가
        String finalText = text;
        if (styleInstruction != null && !styleInstruction.isBlank()) {
            finalText = styleInstruction + " " + text;
        }
        
        String safe = finalText.replace("\"", "\\\"");
        return """
                {
                  "contents":[{"parts":[{"text":"%s"}]}],
                  "generationConfig":{
                    "responseModalities":["AUDIO"],
                    "speechConfig":{
                      "voiceConfig":{
                        "prebuiltVoiceConfig":{
                          "voiceName":"%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(safe, voiceName);
    }

    private String extractBase64Pcm(String json) throws IOException {
        JsonNode root = om.readTree(json);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.size() == 0) return null;
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.size() == 0) return null;
        JsonNode inline = parts.get(0).path("inlineData");
        return inline.path("data").asText(null);
    }

    private static byte[] wrapPcmToWav(byte[] pcm, int sampleRate, short channels, short bitsPerSample) {
        int dataSize = pcm.length;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int chunkSize = 36 + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            out.write(intLE(chunkSize));
            out.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            out.write(intLE(16));                // Subchunk1Size(PCM=16)
            out.write(shortLE((short) 1));        // AudioFormat=1(PCM)
            out.write(shortLE(channels));
            out.write(intLE(sampleRate));
            out.write(intLE(byteRate));
            out.write(shortLE((short) blockAlign));
            out.write(shortLE(bitsPerSample));
            out.write(new byte[]{'d', 'a', 't', 'a'});
            out.write(intLE(dataSize));
            out.write(pcm);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    private static byte[] intLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] shortLE(short v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }
}
