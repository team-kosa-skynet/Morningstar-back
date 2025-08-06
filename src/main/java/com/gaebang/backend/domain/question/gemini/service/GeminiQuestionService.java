package com.gaebang.backend.domain.question.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiMessage;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        SseEmitter emitter = new SseEmitter(300000L); // 5분

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            performApiCall(emitter, geminiQuestionRequestDto);
        });

        setupEmitterCallbacksWithCancellation(emitter, future, "Gemini");
        return emitter;
    }

    private void performApiCall(SseEmitter emitter, GeminiQuestionRequestDto requestDto) {
        try {
            GeminiMessage userMessage = GeminiMessage.builder()
                    .parts(List.of(Map.of("text", requestDto.content())))
                    .build();

            Map<String, Object> parameters = createRequestParameters(userMessage);

            // URL 중복 수정 제거
            String geminiStreamUrl = geminiQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(geminiStreamUrl)
                    .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        // 취소 신호 확인
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Gemini 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        // HTTP 상태 코드 검증
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("Gemini API 호출 실패: %s", response.getStatusCode());
                            log.error(errorMessage);
                            handleStreamError(emitter, new RuntimeException(errorMessage));
                            return null;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {

                            String line;
                            while ((line = reader.readLine()) != null) {
                                // 주기적으로 취소 신호 확인
                                if (Thread.currentThread().isInterrupted()) {
                                    log.info("Gemini 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                // SSE 형태로 오는 데이터 파싱: "data: {json}"
                                if (line.startsWith("data: ")) {
                                    String jsonData = line.substring(6); // "data: " 제거
                                    if (!jsonData.equals("[DONE]")) {
                                        String content = parseGeminiJsonResponse(jsonData);
                                        if (content != null && !content.isEmpty()) {
                                            try {
                                                emitter.send(SseEmitter.event()
                                                        .name("message")
                                                        .data(content));
                                            } catch (IOException e) {
                                                log.warn("Gemini 클라이언트 연결 종료됨 - 스트리밍 중단");
                                                return null;
                                            }
                                        }
                                    }
                                }
                            }

                            if (!Thread.currentThread().isInterrupted()) {
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("스트리밍 완료"));
                                emitter.complete();
                            }

                        } catch (IOException e) {
                            if (!Thread.currentThread().isInterrupted()) {
                                log.error("Gemini 스트리밍 중 네트워크 오류", e);
                                handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Gemini API 스트리밍 호출 실패: ", e);
                handleStreamError(emitter, e);
            }
        }
    }

    private void setupEmitterCallbacksWithCancellation(SseEmitter emitter,
                                                       CompletableFuture<Void> future,
                                                       String serviceName) {
        emitter.onTimeout(() -> {
            log.warn("{} 스트리밍 타임아웃 - CompletableFuture 취소", serviceName);
            future.cancel(true);
            emitter.complete();
        });

        emitter.onCompletion(() -> {
            log.info("{} 스트리밍 완료", serviceName);
            if (!future.isDone()) {
                future.cancel(true);
            }
        });

        emitter.onError((throwable) -> {
            log.error("{} 스트리밍 에러 - CompletableFuture 취소", serviceName, throwable);
            future.cancel(true);
        });
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

    private String parseGeminiJsonResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            return Optional.ofNullable(root.path("candidates"))
                    .filter(JsonNode::isArray)
                    .filter(candidates -> candidates.size() > 0)
                    .map(candidates -> candidates.get(0))
                    .map(candidate -> candidate.path("content"))
                    .map(content -> content.path("parts"))
                    .filter(JsonNode::isArray)
                    .filter(parts -> parts.size() > 0)
                    .map(parts -> parts.get(0))
                    .map(part -> part.path("text"))
                    .map(JsonNode::asText)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Gemini JSON 파싱 실패: {}", e.getMessage());
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
}
