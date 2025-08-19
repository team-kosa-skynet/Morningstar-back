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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeQuestionService {

    private final ClaudeQuestionProperties claudeQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;

    /**
     * 파일과 함께 Claude 질문 스트리밍을 생성합니다
     */
    public SseEmitter createQuestionStreamWithFiles(
            Long conversationId,
            String model,
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L); // 5분

        // 파일 정보를 포함한 질문 내용 생성
        String questionContent = buildQuestionContentWithFiles(claudeQuestionRequestDto);

        // 사용자 질문을 대화방에 먼저 저장
        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(questionContent);
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performApiCallWithFiles(emitter, conversationId, model, claudeQuestionRequestDto, member);

        setupEmitterCallbacksWithCancellation(emitter, "Claude");
        return emitter;
    }

    /**
     * 파일 정보를 포함한 질문 내용 생성
     */
    private String buildQuestionContentWithFiles(ClaudeQuestionRequestDto requestDto) {
        StringBuilder content = new StringBuilder(requestDto.getContent());

        if (requestDto.getFiles() != null && !requestDto.getFiles().isEmpty()) {
            content.append("\n\n[첨부된 파일들:]");
            for (MultipartFile file : requestDto.getFiles()) {
                content.append("\n- ").append(file.getOriginalFilename())
                        .append(" (크기: ").append(file.getSize()).append(" bytes)");
            }
        }

        return content.toString();
    }

    /**
     * 파일을 포함한 Claude API 호출
     */
    private void performApiCallWithFiles(SseEmitter emitter, Long conversationId, String requestModel,
                                         ClaudeQuestionRequestDto requestDto, Member member) {
        StringBuilder fullResponse = new StringBuilder();

        try {
            String modelToUse = claudeQuestionProperties.getModelToUse(requestModel);
            log.info("Claude API 호출 - 사용 모델: {} (요청 모델: {})", modelToUse, requestModel);

            // 대화 히스토리 조회
            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    null
            );

            // Claude API용 메시지 배열 생성
            List<Map<String, Object>> messages = new ArrayList<>();

            // 히스토리에서 메시지들을 Claude 형식으로 변환
            for (Map<String, Object> historyMessage : historyDto.messages()) {
                String role = (String) historyMessage.get("role");
                String content = (String) historyMessage.get("content");

                Map<String, Object> claudeMessage = new HashMap<>();
                claudeMessage.put("role", role);
                claudeMessage.put("content", content);
                messages.add(claudeMessage);
            }

            // 현재 질문을 파일과 함께 구성
            Map<String, Object> currentMessage = buildMessageWithFiles(requestDto);
            messages.add(currentMessage);

            Map<String, Object> parameters = createRequestParametersWithFiles(messages, modelToUse, true);
            String claudeUrl = claudeQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(claudeUrl)
                    .header("x-api-key", claudeQuestionProperties.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Claude 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

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
                                if (Thread.currentThread().isInterrupted()) {
                                    log.info("Claude 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                if (line.startsWith("data: ")) {
                                    String jsonData = line.substring(6);
                                    String content = parseClaudeJsonResponse(jsonData);
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);

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
                                if (fullResponse.length() > 0) {
                                    AddAnswerRequestDto answerRequest = new AddAnswerRequestDto(
                                            fullResponse.toString(),
                                            modelToUse
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

    /**
     * 파일을 포함한 메시지 구성 (Claude 형식)
     */
    private Map<String, Object> buildMessageWithFiles(ClaudeQuestionRequestDto requestDto) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");

        if (requestDto.getFiles() == null || requestDto.getFiles().isEmpty()) {
            // 파일이 없는 경우 텍스트만
            message.put("content", requestDto.getContent());
        } else {
            // 파일이 있는 경우 content를 배열로 구성
            List<Map<String, Object>> content = new ArrayList<>();

            // 텍스트 부분
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");
            textPart.put("text", requestDto.getContent());
            content.add(textPart);

            // 파일 부분들 (이미지인 경우만 처리)
            for (MultipartFile file : requestDto.getFiles()) {
                if (isImageFile(file)) {
                    try {
                        Map<String, Object> imagePart = new HashMap<>();
                        imagePart.put("type", "image");

                        Map<String, Object> source = new HashMap<>();
                        source.put("type", "base64");
                        source.put("media_type", file.getContentType());
                        source.put("data", Base64.getEncoder().encodeToString(file.getBytes()));

                        imagePart.put("source", source);
                        content.add(imagePart);

                        log.info("이미지 파일 추가됨: {}", file.getOriginalFilename());
                    } catch (IOException e) {
                        log.error("이미지 파일 처리 실패: {}", file.getOriginalFilename(), e);
                    }
                }
            }

            message.put("content", content);
        }

        return message;
    }

    /**
     * 이미지 파일인지 확인
     */
    private boolean isImageFile(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType != null && contentType.startsWith("image/");
    }

    // 기존 메서드들 유지
    private void setupEmitterCallbacksWithCancellation(SseEmitter emitter, String serviceName) {
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

    private Member validateAndGetMember(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();
        if (memberId == null) {
            throw new UserInvalidAccessException();
        }
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());
    }

    private Map<String, Object> createRequestParametersWithFiles(List<Map<String, Object>> messages, String model, boolean stream) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", model);
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0);
        parameters.put("system", "너는 AI에 최적화된 전문가야");
        parameters.put("messages", messages);
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
