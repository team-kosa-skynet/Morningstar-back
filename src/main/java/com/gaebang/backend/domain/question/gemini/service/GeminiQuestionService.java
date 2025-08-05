package com.gaebang.backend.domain.question.gemini.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiMessage;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiQuestionService {

    private final GeminiQuestionProperties geminiQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public SseEmitter createQuestionStream(
            GeminiQuestionRequestDto geminiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(30000L);

        GeminiMessage userMessage = GeminiMessage.builder()
                .parts(List.of(Map.of("text", geminiQuestionRequestDto.content())))
                .build();

        Map<String, Object> parameters = createRequestParameters(userMessage);

        CompletableFuture.runAsync(() -> {
            try {
                String geminiStreamUrl = geminiQuestionProperties.getResponseUrl()
                        .replace("generateContent", "streamGenerateContent");

                restClient.post()
                        .uri(geminiStreamUrl)
                        .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                        .header("Content-Type", "application/json")
                        .body(parameters)
                        .exchange((request, response) -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new StringReader(new String(response.getBody().readAllBytes())))) {

                                String line;
                                StringBuilder jsonBuilder = new StringBuilder();
                                boolean inJsonObject = false;
                                int braceCount = 0;

                                while ((line = reader.readLine()) != null) {
                                    line = line.trim();
                                    if (line.isEmpty()) continue;

                                    for (char c : line.toCharArray()) {
                                        if (c == '{') {
                                            if (!inJsonObject) {
                                                inJsonObject = true;
                                                jsonBuilder = new StringBuilder();
                                            }
                                            braceCount++;
                                        }

                                        if (inJsonObject) {
                                            jsonBuilder.append(c);
                                        }

                                        if (c == '}') {
                                            braceCount--;
                                            if (braceCount == 0 && inJsonObject) {
                                                String content = parseGeminiStreamResponse(jsonBuilder.toString());
                                                if (content != null && !content.isEmpty()) {
                                                    emitter.send(SseEmitter.event()
                                                            .name("message")
                                                            .data(content));
                                                }
                                                inJsonObject = false;
                                            }
                                        }
                                    }
                                }

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("스트리밍 완료"));
                                emitter.complete();

                            } catch (IOException e) {
                                log.error("스트리밍 중 오류 발생", e);
                                emitter.completeWithError(e);
                            }
                            return null;
                        });

            } catch (Exception e) {
                log.error("Gemini API 스트리밍 호출 실패: ", e);
                handleStreamError(emitter, e);
            }
        });

        setupEmitterCallbacks(emitter, "Gemini");
        return emitter;
    }

    private Member validateAndGetMember(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();
        if (memberId == null) {
            throw new UserInvalidAccessException();
        }
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());
    }

    private Map<String, Object> createRequestParameters(GeminiMessage userMessage) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("contents", List.of(userMessage));
        return parameters;
    }

    private String parseGeminiStreamResponse(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);
            JsonNode candidates = jsonNode.get("candidates");
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode content = candidates.get(0).get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray() && parts.size() > 0) {
                        JsonNode text = parts.get(0).get("text");
                        if (text != null) {
                            return text.asText();
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Gemini 스트리밍 응답 파싱 실패", e);
            return null;
        }
    }

    private void handleStreamError(SseEmitter emitter, Exception e) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("AI 응답 생성 중 오류가 발생했습니다."));
            emitter.completeWithError(e);
        } catch (IOException ioException) {
            log.error("에러 전송 실패", ioException);
        }
    }

    private void setupEmitterCallbacks(SseEmitter emitter, String serviceName) {
        emitter.onTimeout(() -> {
            log.warn("{} 스트리밍 타임아웃", serviceName);
            emitter.complete();
        });

        emitter.onCompletion(() -> {
            log.info("{} 스트리밍 완료", serviceName);
        });

        emitter.onError((throwable) -> {
            log.error("{} 스트리밍 에러", serviceName, throwable);
        });
    }
}