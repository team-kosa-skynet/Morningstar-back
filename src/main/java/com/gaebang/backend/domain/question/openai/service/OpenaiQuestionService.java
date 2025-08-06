package com.gaebang.backend.domain.question.openai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
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
public class OpenaiQuestionService {

    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public SseEmitter createQuestionStream(
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L); // 5분

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            performApiCall(emitter, openaiQuestionRequestDto);
        });

        setupEmitterCallbacksWithCancellation(emitter, future, "OpenAI");
        return emitter;
    }

    private void performApiCall(SseEmitter emitter, OpenaiQuestionRequestDto requestDto) {
        try {
            Map<String, Object> parameters = createRequestParameters(requestDto);
            String openaiUrl = openaiQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(openaiUrl)
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        // 취소 신호 확인
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("OpenAI 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        // HTTP 상태 코드 검증
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("OpenAI API 호출 실패: %s", response.getStatusCode());
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
                                    log.info("OpenAI 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                if (line.startsWith("data: ")) {
                                    String jsonData = line.substring(6);
                                    if (jsonData.equals("[DONE]")) {
                                        break;
                                    }
                                    String content = parseOpenaiJsonResponse(jsonData);
                                    if (content != null && !content.isEmpty()) {
                                        try {
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(content));
                                        } catch (IOException e) {
                                            log.warn("OpenAI 클라이언트 연결 종료됨 - 스트리밍 중단");
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
                                log.error("OpenAI 스트리밍 중 네트워크 오류", e);
                                handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("OpenAI API 스트리밍 호출 실패: ", e);
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

    private Map<String, Object> createRequestParameters(OpenaiQuestionRequestDto requestDto) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", openaiQuestionProperties.getModel());
        parameters.put("messages", List.of(
                Map.of("role", "user", "content", requestDto.input())
        ));
        parameters.put("stream", true);
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0);
        return parameters;
    }

    private String parseOpenaiJsonResponse(String jsonData) {
        try {
            JsonNode root = objectMapper.readTree(jsonData);

            return Optional.ofNullable(root.path("choices"))
                    .filter(JsonNode::isArray)
                    .filter(choices -> choices.size() > 0)
                    .map(choices -> choices.get(0))
                    .map(choice -> choice.path("delta"))
                    .map(delta -> delta.path("content"))
                    .map(JsonNode::asText)
                    .filter(text -> !text.isEmpty())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("OpenAI JSON 파싱 실패: {}", e.getMessage());
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
