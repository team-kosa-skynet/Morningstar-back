package com.gaebang.backend.domain.question.openai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.common.service.FileProcessingService;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenaiQuestionService {

    private final MemberRepository memberRepository;
    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final FileProcessingService fileProcessingService;

    public SseEmitter createQuestionStream(
            Long conversationId,
            String model,
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L);

        List<FileAttachmentDto> attachments = processFiles(openaiQuestionRequestDto.files());

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                openaiQuestionRequestDto.content(),
                attachments
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performApiCallWithFiles(emitter, conversationId, model, openaiQuestionRequestDto, member, attachments);

        setupEmitterCallbacks(emitter, "OpenAI");
        return emitter;
    }

    private List<FileAttachmentDto> processFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        return files.stream()
                .map(file -> {
                    Map<String, Object> processedFile = fileProcessingService.processFile(file);

                    return new FileAttachmentDto(
                            (String) processedFile.get("fileName"),
                            (String) processedFile.get("type"),
                            (Long) processedFile.get("fileSize"),
                            (String) processedFile.get("mimeType")
                    );
                })
                .toList();
    }

    private void performApiCallWithFiles(SseEmitter emitter, Long conversationId, String requestModel,
                                         OpenaiQuestionRequestDto requestDto, Member member,
                                         List<FileAttachmentDto> attachments) {
        StringBuilder fullResponse = new StringBuilder();

        try {
            String modelToUse = openaiQuestionProperties.getModelToUse(requestModel);
            log.info("OpenAI API 호출 - 사용 모델: {} (요청 모델: {})", modelToUse, requestModel);

            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    null
            );

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("model", modelToUse);
            parameters.put("stream", true);

            List<Map<String, Object>> messages = new ArrayList<>(historyDto.messages());

            // 파일이 있거나 새로운 텍스트일 때 createContentWithFiles 호출
            if (messages.isEmpty() ||
                    !requestDto.content().equals(messages.get(messages.size() - 1).get("content")) ||
                    (requestDto.files() != null && !requestDto.files().isEmpty())) {

                List<Map<String, Object>> content = createContentWithFiles(
                        requestDto.content(),
                        requestDto.files()
                );

                Map<String, Object> currentMessage = new HashMap<>();
                currentMessage.put("role", "user");
                currentMessage.put("content", content);
                messages.add(currentMessage);
            }

            parameters.put("messages", messages);

            // === OpenAI API 요청 데이터 로깅 ===
            log.info("=== OpenAI API 요청 데이터 ===");
            log.info("모델: {}", modelToUse);
            log.info("메시지 개수: {}", messages.size());

            if (!messages.isEmpty()) {
                Map<String, Object> lastMessage = messages.get(messages.size() - 1);
                log.info("마지막 메시지 role: {}", lastMessage.get("role"));

                Object contentObj = lastMessage.get("content");
                if (contentObj instanceof List) {
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) contentObj;
                    log.info("content 파트 개수: {}", contentList.size());

                    for (int i = 0; i < contentList.size(); i++) {
                        Map<String, Object> part = contentList.get(i);
                        String type = (String) part.get("type");
                        log.info("content[{}] 타입: {}", i, type);

                        if ("text".equals(type)) {
                            String text = (String) part.get("text");
                            log.info("content[{}] 텍스트 길이: {} 문자", i, text != null ? text.length() : 0);
                            log.info("content[{}] 텍스트 내용: {}", i, text != null && text.length() > 100 ? text.substring(0, 100) + "..." : text);
                        } else if ("image_url".equals(type)) {
                            Map<String, String> imageUrl = (Map<String, String>) part.get("image_url");
                            if (imageUrl != null) {
                                String url = imageUrl.get("url");
                                if (url != null && url.startsWith("data:")) {
                                    String[] parts = url.split(",");
                                    log.info("content[{}] 이미지 URL 헤더: {}", i, parts.length > 0 ? parts[0] : "없음");
                                    log.info("content[{}] Base64 데이터 길이: {} 문자", i, parts.length > 1 ? parts[1].length() : 0);

                                    // Base64 데이터 유효성 검사
                                    if (parts.length > 1) {
                                        String base64Data = parts[1];
                                        boolean isValidBase64 = base64Data.matches("^[A-Za-z0-9+/]*={0,2}$");
                                        log.info("content[{}] Base64 유효성: {}", i, isValidBase64 ? "유효" : "무효");

                                        // Base64 디코딩 테스트
                                        try {
                                            byte[] decoded = java.util.Base64.getDecoder().decode(base64Data);
                                            log.info("content[{}] 디코딩된 바이트 크기: {} bytes", i, decoded.length);
                                        } catch (Exception e) {
                                            log.error("content[{}] Base64 디코딩 실패: {}", i, e.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    log.info("content가 List가 아님: {}", contentObj != null ? contentObj.getClass().getSimpleName() : "null");
                    log.info("content 내용: {}", contentObj);
                }
            }
            log.info("=== OpenAI API 요청 데이터 끝 ===");

            String openaiUrl = openaiQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(openaiUrl)
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("OpenAI Chat Completions API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

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
                                if (Thread.currentThread().isInterrupted()) {
                                    log.info("OpenAI Chat Completions API 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    String content = parseChatCompletionsStreamResponse(data);
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);

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
                                if (fullResponse.length() > 0) {
                                    AddAnswerRequestDto answerRequest = new AddAnswerRequestDto(
                                            fullResponse.toString(),
                                            modelToUse
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

    private List<Map<String, Object>> createContentWithFiles(String textContent, List<MultipartFile> files) {
        List<Map<String, Object>> content = new ArrayList<>();

        log.info("=== createContentWithFiles 시작 ===");
        log.info("텍스트 내용: {}", textContent);
        log.info("파일 개수: {}", files != null ? files.size() : 0);

        StringBuilder combinedText = new StringBuilder(textContent);

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                try {
                    log.info("처리 중인 파일: {}", file.getOriginalFilename());
                    Map<String, Object> processedFile = fileProcessingService.processFile(file);
                    log.info("파일 처리 결과: {}", processedFile);

                    String fileType = (String) processedFile.get("type");

                    if ("image".equals(fileType)) {
                        String base64 = (String) processedFile.get("base64");
                        String mimeType = (String) processedFile.get("mimeType");

                        Map<String, Object> imagePart = new HashMap<>();
                        imagePart.put("type", "image_url");

                        Map<String, String> imageUrl = new HashMap<>();
                        imageUrl.put("url", "data:" + mimeType + ";base64," + base64);
                        imagePart.put("image_url", imageUrl);

                        content.add(imagePart);
                        log.info("이미지 파트 추가됨 - MIME: {}, Base64 길이: {}", mimeType, base64.length());
                    } else if ("text".equals(fileType)) {
                        String extractedText = (String) processedFile.get("extractedText");
                        String fileName = (String) processedFile.get("fileName");
                        combinedText.append("\n\n[파일: ").append(fileName).append("]\n").append(extractedText);
                        log.info("텍스트 파트 추가됨 - 파일: {}, 길이: {}", fileName, extractedText.length());
                    }
                } catch (Exception e) {
                    log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
                }
            }
        }

        // 텍스트 파트 추가 (맨 앞에)
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", combinedText.toString());
        content.add(0, textPart);

        log.info("최종 content 파트 개수: {}", content.size());
        log.info("최종 텍스트 내용: {}", combinedText.toString());
        log.info("=== createContentWithFiles 끝 ===");

        return content;
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