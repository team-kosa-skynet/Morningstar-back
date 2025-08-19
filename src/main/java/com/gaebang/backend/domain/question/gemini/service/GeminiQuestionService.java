package com.gaebang.backend.domain.question.gemini.service;

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
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
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
public class GeminiQuestionService {

    private final GeminiQuestionProperties geminiQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;

    /**
     * 파일과 함께 Gemini 질문 스트리밍을 생성합니다
     */
    public SseEmitter createQuestionStreamWithFiles(
            Long conversationId,
            String model,
            GeminiQuestionRequestDto geminiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L); // 5분

        // 파일 정보를 포함한 질문 내용 생성
        String questionContent = buildQuestionContentWithFiles(geminiQuestionRequestDto);

        // 사용자 질문을 대화방에 먼저 저장
        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(questionContent);
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performApiCallWithFiles(emitter, conversationId, model, geminiQuestionRequestDto, member);

        setupEmitterCallbacksWithCancellation(emitter, "Gemini");
        return emitter;
    }

    /**
     * 파일 정보를 포함한 질문 내용 생성
     */
    private String buildQuestionContentWithFiles(GeminiQuestionRequestDto requestDto) {
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
     * 파일을 포함한 Gemini API 호출
     */
    private void performApiCallWithFiles(SseEmitter emitter, Long conversationId, String requestModel,
                                         GeminiQuestionRequestDto requestDto, Member member) {
        StringBuilder fullResponse = new StringBuilder();

        try {
            String modelToUse = geminiQuestionProperties.getModelToUse(requestModel);
            log.info("Gemini API 호출 - 사용 모델: {} (요청 모델: {})", modelToUse, requestModel);

            // 대화 히스토리 조회
            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    null
            );

            // Gemini API용 contents 배열 생성
            List<Map<String, Object>> contents = new ArrayList<>();

            // 히스토리에서 메시지들을 Gemini 형식으로 변환
            for (Map<String, Object> historyMessage : historyDto.messages()) {
                String role = (String) historyMessage.get("role");
                String content = (String) historyMessage.get("content");

                String geminiRole = "user".equals(role) ? "user" : "model";

                Map<String, Object> geminiContent = new HashMap<>();
                geminiContent.put("role", geminiRole);
                geminiContent.put("parts", List.of(Map.of("text", content)));
                contents.add(geminiContent);
            }

            // 현재 질문을 파일과 함께 구성
            Map<String, Object> currentContent = buildContentWithFiles(requestDto);
            contents.add(currentContent);

            Map<String, Object> parameters = createRequestParameters(contents);

            // 선택된 모델에 대한 URL 생성
            String geminiStreamUrl = geminiQuestionProperties.getResponseUrl(modelToUse);

            restClient.post()
                    .uri(geminiStreamUrl)
                    .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Gemini 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

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
                                if (Thread.currentThread().isInterrupted()) {
                                    log.info("Gemini 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                if (line.startsWith("data: ")) {
                                    String jsonData = line.substring(6);
                                    if (!jsonData.equals("[DONE]")) {
                                        String content = parseGeminiJsonResponse(jsonData);
                                        if (content != null && !content.isEmpty()) {
                                            fullResponse.append(content);

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
                                if (fullResponse.length() > 0) {
                                    AddAnswerRequestDto answerRequest = new AddAnswerRequestDto(
                                            fullResponse.toString(),
                                            modelToUse
                                    );
                                    conversationService.addAnswer(conversationId, member.getId(), answerRequest);
                                    log.info("Gemini 답변 저장 완료 - 모델: {}", modelToUse);
                                }

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

    /**
     * 파일을 포함한 Content 구성 (Gemini 형식)
     */
    private Map<String, Object> buildContentWithFiles(GeminiQuestionRequestDto requestDto) {
        Map<String, Object> content = new HashMap<>();
        content.put("role", "user");

        List<Map<String, Object>> parts = new ArrayList<>();

        // 텍스트 부분
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", requestDto.getContent());
        parts.add(textPart);

        // 파일 부분들 (이미지인 경우만 처리)
        if (requestDto.getFiles() != null && !requestDto.getFiles().isEmpty()) {
            for (MultipartFile file : requestDto.getFiles()) {
                if (isImageFile(file)) {
                    try {
                        Map<String, Object> imagePart = new HashMap<>();

                        // Gemini 이미지 형식
                        Map<String, Object> inlineData = new HashMap<>();
                        inlineData.put("mime_type", file.getContentType());
                        inlineData.put("data", Base64.getEncoder().encodeToString(file.getBytes()));

                        imagePart.put("inline_data", inlineData);
                        parts.add(imagePart);

                        log.info("이미지 파일 추가됨: {}", file.getOriginalFilename());
                    } catch (IOException e) {
                        log.error("이미지 파일 처리 실패: {}", file.getOriginalFilename(), e);
                    }
                }
            }
        }

        content.put("parts", parts);
        return content;
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

    private Map<String, Object> createRequestParameters(List<Map<String, Object>> contents) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("contents", contents);
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
