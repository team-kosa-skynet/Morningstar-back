package com.gaebang.backend.domain.question.claude.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeQuestionService {

    private final ClaudeQuestionProperties claudeQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService; // ConversationService 주입 추가

    /**
     * 특정 대화방에서 Claude 질문 스트리밍을 생성합니다
     * 이전 대화 히스토리를 포함하여 연속적인 대화가 가능합니다
     */
    public SseEmitter createQuestionStream(
            Long conversationId,
            String model, // 쿼리 파라미터로 받은 모델
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L); // 5분

        // 사용자 질문을 대화방에 먼저 저장
        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(claudeQuestionRequestDto.content());
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            performApiCall(emitter, conversationId, model, claudeQuestionRequestDto, member); // 모델 파라미터 추가
        });

        setupEmitterCallbacksWithCancellation(emitter, future, "Claude");
        return emitter;
    }


    /**
     * Claude API를 호출하고 스트리밍 응답을 처리합니다
     * 대화 히스토리를 포함하여 이전 맥락을 유지합니다
     */
    private void performApiCall(SseEmitter emitter, Long conversationId, String requestModel,
                                ClaudeQuestionRequestDto requestDto, Member member) {
        StringBuilder fullResponse = new StringBuilder(); // 전체 응답 저장용

        try {
            // 사용할 모델 결정 (쿼리 파라미터로 받은 모델 또는 기본값)
            String modelToUse = claudeQuestionProperties.getModelToUse(requestModel);
            log.info("Claude API 호출 - 사용 모델: {} (요청 모델: {})", modelToUse, requestModel);

            // 대화 히스토리 조회 (이전 대화 맥락 포함)
            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    null // 전체 히스토리 사용 (토큰 제한 고려시 숫자 설정)
            );

            // Claude API용 메시지 배열 생성
            List<ClaudeMessage> messages = new ArrayList<>();

            // 히스토리에서 메시지들을 Claude 형식으로 변환
            for (Map<String, Object> historyMessage : historyDto.messages()) {
                String role = (String) historyMessage.get("role");
                String content = (String) historyMessage.get("content");

                ClaudeMessage claudeMessage = ClaudeMessage.builder()
                        .role(role)
                        .content(content)
                        .build();
                messages.add(claudeMessage);
            }

            // 현재 질문이 히스토리에 없으면 추가 (안전장치)
            if (messages.isEmpty() ||
                    !requestDto.content().equals(messages.get(messages.size() - 1).getContent())) {
                ClaudeMessage currentMessage = ClaudeMessage.builder()
                        .role("user")
                        .content(requestDto.content())
                        .build();
                messages.add(currentMessage);
            }

            Map<String, Object> parameters = createRequestParameters(messages, modelToUse, true);
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
                                        fullResponse.append(content); // 전체 응답에 추가

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
                                // 스트리밍 완료 후 전체 응답을 대화방에 저장 (실제 사용된 모델명으로)
                                if (fullResponse.length() > 0) {
                                    AddAnswerRequestDto answerRequest = new AddAnswerRequestDto(
                                            fullResponse.toString(),
                                            modelToUse // 실제 사용된 모델명 저장
                                    );
                                    conversationService.addAnswer(conversationId, member.getId(), answerRequest);
                                    log.info("Claude 답변 저장 완료 - 모델: {}", modelToUse);
                                }

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

    /**
     * Claude API 요청 파라미터 생성 (히스토리 포함)
     */
    private Map<String, Object> createRequestParameters(List<ClaudeMessage> messages, boolean stream) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", claudeQuestionProperties.getModel());
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0);
        parameters.put("system", "너는 AI에 최적화된 전문가야");
        parameters.put("messages", messages); // 전체 히스토리 포함
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
