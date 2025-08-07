package com.gaebang.backend.domain.question.openai.service;

import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenaiQuestionService {

    private final MemberRepository memberRepository;
    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService; // ConversationService 주입 추가

    /**
     * 특정 대화방에서 OpenAI 질문 스트리밍을 생성합니다
     * 이전 대화 히스토리를 포함하여 연속적인 대화가 가능합니다
     */
    public SseEmitter createQuestionStream(
            Long conversationId,
            String model, // 쿼리 파라미터로 받은 모델
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L); // 5분 타임아웃

        // 사용자 질문을 대화방에 먼저 저장
        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(openaiQuestionRequestDto.content());
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            performApiCall(emitter, conversationId, model, openaiQuestionRequestDto, member); // 모델 파라미터 추가
        });

        setupEmitterCallbacks(emitter, future, "OpenAI");
        return emitter;
    }


    /**
     * OpenAI API를 호출하고 스트리밍 응답을 처리합니다
     * 대화 히스토리를 포함하여 이전 맥락을 유지합니다
     */
    private void performApiCall(SseEmitter emitter, Long conversationId, String requestModel,
                                OpenaiQuestionRequestDto requestDto, Member member) {
        StringBuilder fullResponse = new StringBuilder(); // 전체 응답 저장용

        try {
            // 사용할 모델 결정 (쿼리 파라미터로 받은 모델 또는 기본값)
            String modelToUse = openaiQuestionProperties.getModelToUse(requestModel);
            log.info("OpenAI API 호출 - 사용 모델: {} (요청 모델: {})", modelToUse, requestModel);

            // 대화 히스토리 조회 (이전 대화 맥락 포함)
            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    null // 전체 히스토리 사용 (토큰 제한 고려시 숫자 설정)
            );

            // Chat Completions API 스트리밍 요청 데이터 준비
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("model", modelToUse); // 동적으로 결정된 모델 사용
            parameters.put("stream", true);

            // 히스토리에서 messages 가져오기 (이미 LLM API 형태로 변환됨)
            List<Map<String, Object>> messages = new ArrayList<>(historyDto.messages());

            // 현재 질문 추가 (이미 ConversationService에 저장했으므로 히스토리에 포함되어 있음)
            // 하지만 혹시 누락될 경우를 대비해 마지막 메시지가 현재 질문인지 확인
            if (messages.isEmpty() ||
                    !requestDto.content().equals(messages.get(messages.size() - 1).get("content"))) {
                Map<String, Object> currentMessage = new HashMap<>();
                currentMessage.put("role", "user");
                currentMessage.put("content", requestDto.content());
                messages.add(currentMessage);
            }

            parameters.put("messages", messages);

            String openaiUrl = openaiQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(openaiUrl)
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        // 취소 신호 확인
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("OpenAI Chat Completions API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        // HTTP 상태 코드 검증
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("OpenAI Chat Completions API 호출 실패: %s", response.getStatusCode());
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
                                    log.info("OpenAI Chat Completions API 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                // SSE 스트리밍 데이터 파싱: "data: {json}"
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    // [DONE] 신호 확인
                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    String content = parseChatCompletionsStreamResponse(data);
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content); // 전체 응답에 추가

                                        try {
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(content));
                                        } catch (IOException e) {
                                            log.warn("OpenAI Chat Completions API 클라이언트 연결 종료됨 - 스트리밍 중단");
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
                                    log.info("OpenAI 답변 저장 완료 - 모델: {}", modelToUse);
                                }

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("스트리밍 완료"));
                                emitter.complete();
                            }

                        } catch (IOException e) {
                            if (!Thread.currentThread().isInterrupted()) {
                                log.error("OpenAI Chat Completions API 스트리밍 중 네트워크 오류", e);
                                handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("OpenAI Chat Completions API 스트리밍 호출 실패: ", e);
                handleStreamError(emitter, e);
            }
        }
    }


    // 기존 메서드들은 그대로 유지
    private void setupEmitterCallbacks(SseEmitter emitter, CompletableFuture<Void> future, String serviceName) {
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

    private String parseChatCompletionsStreamResponse(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);

            // Chat Completions API 스트리밍 응답 구조
            JsonNode choices = jsonNode.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode delta = firstChoice.get("delta");
                if (delta != null && delta.has("content")) {
                    return delta.get("content").asText();
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("OpenAI Chat Completions API 스트리밍 응답 파싱 실패: {}", e.getMessage());
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
