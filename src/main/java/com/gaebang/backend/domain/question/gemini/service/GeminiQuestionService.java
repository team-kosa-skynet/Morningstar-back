package com.gaebang.backend.domain.question.gemini.service;

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
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.domain.question.common.service.FileProcessingService;
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
public class GeminiQuestionService {

    private final MemberRepository memberRepository;
    private final GeminiQuestionProperties geminiQuestionProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final FileProcessingService fileProcessingService;

    public SseEmitter createQuestionStream(
            Long conversationId,
            String model,
            GeminiQuestionRequestDto geminiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L);

        List<FileAttachmentDto> attachments = processFiles(geminiQuestionRequestDto.files());

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                geminiQuestionRequestDto.content(),
                attachments
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performApiCallWithFiles(emitter, conversationId, model, geminiQuestionRequestDto, member, attachments);

        setupEmitterCallbacks(emitter, "Gemini");
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
                                         GeminiQuestionRequestDto requestDto, Member member,
                                         List<FileAttachmentDto> attachments) {
        StringBuilder fullResponse = new StringBuilder();

        try {
            String modelToUse = geminiQuestionProperties.getModelToUse(requestModel);
            log.info("Gemini API 호출 - 사용 모델: {} (요청 모델: {})", modelToUse, requestModel);

            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    null
            );

            Map<String, Object> parameters = new HashMap<>();

            List<Map<String, Object>> contents = new ArrayList<>();

            // Gemini용 대화 히스토리 처리 (교대로 user-model 순서)
            List<Map<String, Object>> messages = historyDto.messages();
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> message = messages.get(i);
                String role = (String) message.get("role");
                String content = (String) message.get("content");

                Map<String, Object> geminiContent = new HashMap<>();
                geminiContent.put("role", "user".equals(role) ? "user" : "model");
                geminiContent.put("parts", List.of(Map.of("text", content)));
                contents.add(geminiContent);
            }

            // 파일이 있거나 새로운 텍스트일 때 createContentWithFiles 호출
            if (messages.isEmpty() ||
                    !requestDto.content().equals(getLastUserMessage(messages)) ||
                    (requestDto.files() != null && !requestDto.files().isEmpty())) {

                Map<String, Object> content = createContentWithFiles(
                        requestDto.content(),
                        requestDto.files()
                );
                content.put("role", "user");
                contents.add(content);
            }

            // Gemini는 마지막이 user여야 하고, user-model이 교대로 나와야 함
            contents = fixGeminiContentStructure(contents);

            parameters.put("contents", contents);
            parameters.put("generationConfig", Map.of(
                    "temperature", 0.7,
                    "maxOutputTokens", 4096
            ));

            // === Gemini API 요청 데이터 로깅 ===
            log.info("=== Gemini API 요청 데이터 ===");
            log.info("모델: {}", modelToUse);
            log.info("contents 개수: {}", contents.size());

            if (!contents.isEmpty()) {
                for (int i = 0; i < contents.size(); i++) {
                    Map<String, Object> content = contents.get(i);
                    log.info("content[{}] role: {}", i, content.get("role"));
                }

                Map<String, Object> lastContent = contents.get(contents.size() - 1);
                log.info("마지막 content role: {}", lastContent.get("role"));

                Object partsObj = lastContent.get("parts");
                if (partsObj instanceof List) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) partsObj;
                    log.info("parts 개수: {}", parts.size());

                    for (int i = 0; i < parts.size(); i++) {
                        Map<String, Object> part = parts.get(i);

                        if (part.containsKey("text")) {
                            String text = (String) part.get("text");
                            log.info("parts[{}] 텍스트 길이: {} 문자", i, text != null ? text.length() : 0);
                            log.info("parts[{}] 텍스트 내용: {}", i, text != null && text.length() > 100 ? text.substring(0, 100) + "..." : text);
                        } else if (part.containsKey("inline_data")) {
                            Map<String, Object> inlineData = (Map<String, Object>) part.get("inline_data");
                            if (inlineData != null) {
                                String mimeType = (String) inlineData.get("mime_type");
                                String data = (String) inlineData.get("data");
                                log.info("parts[{}] 이미지 MIME 타입: {}", i, mimeType);
                                log.info("parts[{}] Base64 데이터 길이: {} 문자", i, data != null ? data.length() : 0);
                            }
                        }
                    }
                }
            }
            log.info("=== Gemini API 요청 데이터 끝 ===");

            String geminiUrl = geminiQuestionProperties.getResponseUrl(modelToUse) + "&key=" + geminiQuestionProperties.getApiKey();

            restClient.post()
                    .uri(geminiUrl)
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Gemini API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("Gemini API 호출 실패: %s", response.getStatusCode());
                            log.error(errorMessage);

                            // 에러 응답 본문 로깅
                            try (BufferedReader errorReader = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String errorLine;
                                StringBuilder errorBody = new StringBuilder();
                                while ((errorLine = errorReader.readLine()) != null) {
                                    errorBody.append(errorLine);
                                }
                                log.error("Gemini API 에러 응답: {}", errorBody.toString());
                            } catch (IOException e) {
                                log.error("에러 응답 읽기 실패", e);
                            }

                            handleStreamError(emitter, new RuntimeException(errorMessage));
                            return null;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {

                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (Thread.currentThread().isInterrupted()) {
                                    log.info("Gemini API 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                // SSE 형식 처리 추가
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    String content = parseGeminiStreamResponse(data);
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);

                                        try {
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(content));
                                        } catch (IOException e) {
                                            log.warn("Gemini API 클라이언트 연결 종료됨 - 스트리밍 중단");
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
                                    log.info("Gemini 답변 저장 완료 - 모델: {}", modelToUse);
                                }

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("스트리밍 완료"));
                                emitter.complete();
                            }

                        } catch (IOException e) {
                            if (!Thread.currentThread().isInterrupted()) {
                                log.error("Gemini API 스트리밍 중 네트워크 오류", e);
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

    private List<Map<String, Object>> fixGeminiContentStructure(List<Map<String, Object>> contents) {
        List<Map<String, Object>> fixedContents = new ArrayList<>();

        for (int i = 0; i < contents.size(); i++) {
            Map<String, Object> content = contents.get(i);
            String role = (String) content.get("role");

            // 연속된 같은 role 방지
            if (!fixedContents.isEmpty()) {
                Map<String, Object> lastContent = fixedContents.get(fixedContents.size() - 1);
                String lastRole = (String) lastContent.get("role");

                if (role.equals(lastRole)) {
                    // 같은 role이 연속으로 나오면 마지막 content의 parts에 합치기
                    List<Map<String, Object>> lastParts = (List<Map<String, Object>>) lastContent.get("parts");
                    List<Map<String, Object>> currentParts = (List<Map<String, Object>>) content.get("parts");

                    List<Map<String, Object>> combinedParts = new ArrayList<>(lastParts);
                    combinedParts.addAll(currentParts);
                    lastContent.put("parts", combinedParts);
                    continue;
                }
            }

            fixedContents.add(content);
        }

        // 마지막이 user가 아니면 빈 user 메시지 추가
        if (!fixedContents.isEmpty()) {
            Map<String, Object> lastContent = fixedContents.get(fixedContents.size() - 1);
            String lastRole = (String) lastContent.get("role");

            if (!"user".equals(lastRole)) {
                Map<String, Object> userContent = new HashMap<>();
                userContent.put("role", "user");
                userContent.put("parts", List.of(Map.of("text", "계속해주세요.")));
                fixedContents.add(userContent);
            }
        }

        return fixedContents;
    }

    private Map<String, Object> createContentWithFiles(String textContent, List<MultipartFile> files) {
        Map<String, Object> content = new HashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();

        log.info("=== Gemini createContentWithFiles 시작 ===");
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
                        imagePart.put("inline_data", Map.of(
                                "mime_type", mimeType,
                                "data", base64
                        ));
                        parts.add(imagePart);

                        log.info("Gemini 이미지 파트 추가됨 - MIME: {}, Base64 길이: {}", mimeType, base64.length());
                    } else if ("text".equals(fileType)) {
                        String extractedText = (String) processedFile.get("extractedText");
                        String fileName = (String) processedFile.get("fileName");

                        combinedText.append("\n\n--- 파일: ").append(fileName).append(" ---\n");
                        combinedText.append(extractedText);
                        combinedText.append("\n--- 파일 끝 ---\n");

                        log.info("Gemini 텍스트 파일 내용 텍스트에 추가됨 - 파일: {}, 길이: {}", fileName, extractedText.length());
                    }
                } catch (Exception e) {
                    log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
                }
            }
        }

        // 텍스트 파트 추가 (맨 앞에)
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", combinedText.toString());
        parts.add(0, textPart);

        content.put("parts", parts);

        log.info("Gemini 최종 parts 개수: {}", parts.size());
        log.info("Gemini 최종 텍스트 내용 길이: {} 문자", combinedText.length());
        log.info("=== Gemini createContentWithFiles 끝 ===");

        return content;
    }

    private String getLastUserMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if ("user".equals(message.get("role"))) {
                return (String) message.get("content");
            }
        }
        return "";
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

    private String parseGeminiStreamResponse(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);

            JsonNode candidates = jsonNode.get("candidates");
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray() && parts.size() > 0) {
                        JsonNode firstPart = parts.get(0);
                        if (firstPart.has("text")) {
                            return firstPart.get("text").asText();
                        }
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("Gemini API 스트리밍 응답 파싱 실패: {}", e.getMessage());
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
