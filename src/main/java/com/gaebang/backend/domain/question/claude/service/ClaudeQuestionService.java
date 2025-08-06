package com.gaebang.backend.domain.question.claude.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.claude.dto.request.ClaudeMessage;
import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.util.ClaudeQuestionProperties;
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
public class ClaudeQuestionService {

    private final ClaudeQuestionProperties claudeQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public SseEmitter createQuestionStream(
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L); // 5분

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            performApiCall(emitter, claudeQuestionRequestDto);
        });

        setupEmitterCallbacksWithCancellation(emitter, future, "Claude");
        return emitter;
    }

    private void performApiCall(SseEmitter emitter, ClaudeQuestionRequestDto requestDto) {
        try {
            ClaudeMessage userMessage = ClaudeMessage.builder()
                    .role("user")
                    .content(requestDto.content())
                    .build();

            Map<String, Object> parameters = createRequestParameters(userMessage, true);
            String claudeUrl = claudeQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(claudeUrl)
                    .header("x-api-key", claudeQuestionProperties.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        // 취소 신호 확인
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Claude 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        // HTTP 상태 코드 검증
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("Claude API 호출 실패: %s", response.getStatusCode());
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
                                    log.info("Claude 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                if (line.startsWith("data: ")) {
                                    String jsonData = line.substring(6);
                                    String content = parseClaudeJsonResponse(jsonData);
                                    if (content != null && !content.isEmpty()) {
                                        try {
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(content));
                                        } catch (IOException e) {
                                            log.warn("Claude 클라이언트 연결 종료됨 - 스트리밍 중단");
                                            return null;
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
                                log.error("Claude 스트리밍 중 네트워크 오류", e);
                                handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Claude API 스트리밍 호출 실패: ", e);
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

    private Map<String, Object> createRequestParameters(ClaudeMessage userMessage, boolean stream) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", claudeQuestionProperties.getModel());
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0);
        parameters.put("system", "너는 AI에 최적화된 전문가야");
        parameters.put("messages", List.of(userMessage));
        if (stream) {
            parameters.put("stream", true);
        }
        return parameters;
    }

    private String parseClaudeJsonResponse(String jsonData) {
        try {
            JsonNode root = objectMapper.readTree(jsonData);

            return Optional.ofNullable(root.path("delta"))
                    .map(delta -> delta.path("text"))
                    .map(JsonNode::asText)
                    .filter(text -> !text.isEmpty())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Claude JSON 파싱 실패: {}", e.getMessage());
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
