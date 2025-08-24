package com.gaebang.backend.domain.question.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.domain.question.common.service.FileProcessingService;
import com.gaebang.backend.domain.question.common.util.QuestionServiceUtils;
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
            GeminiQuestionRequestDto geminiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);
        SseEmitter emitter = new SseEmitter(300000L);

        List<FileAttachmentDto> attachments = QuestionServiceUtils.processFiles(geminiQuestionRequestDto.files(), fileProcessingService);
        
        // 파일 내용을 미리 결합하여 content 생성
        String contentWithFiles = QuestionServiceUtils.buildContentWithExtractedFiles(
                geminiQuestionRequestDto.content(),
                geminiQuestionRequestDto.files(),
                fileProcessingService
        );

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                contentWithFiles,
                attachments
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performApiCallWithFiles(emitter, conversationId, geminiQuestionRequestDto.model(), geminiQuestionRequestDto, member, attachments);

        QuestionServiceUtils.setupEmitterCallbacks(emitter, "Gemini");
        return emitter;
    }

    public SseEmitter generateImageInConversation(
            Long conversationId,
            String prompt,
            PrincipalDetails principalDetails
    ) {
        Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);
        SseEmitter emitter = new SseEmitter(300000L);

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                prompt,
                Collections.emptyList()
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performImageGeneration(emitter, conversationId, prompt, member);

        QuestionServiceUtils.setupEmitterCallbacks(emitter, "Gemini Image");
        return emitter;
    }

    private void performImageGeneration(SseEmitter emitter, Long conversationId,
                                        String prompt, Member member) {
        try {
            log.info("Gemini 이미지 생성 요청: {}", prompt);

            String imageDataUrl = generateImageWithGemini(prompt);

            if (imageDataUrl != null) {
                Map<String, Object> imageResponse = new HashMap<>();
                imageResponse.put("imageUrl", imageDataUrl);
                imageResponse.put("prompt", prompt);
                imageResponse.put("type", "base64");

                try {
                    emitter.send(SseEmitter.event()
                            .name("image")
                            .data(imageResponse));

                    log.info("Gemini 이미지 생성 완료 및 전송");
                } catch (IOException e) {
                    log.warn("Gemini 이미지 전송 실패 - 클라이언트 연결 종료됨");
                    return;
                }

                FileAttachmentDto imageAttachment = new FileAttachmentDto(
                        "generated_image.png",
                        "generated_image",
                        0L,
                        "image/png"
                );

                String responseText = String.format("요청하신 '%s' 이미지를 생성했습니다.", prompt);
                AddAnswerRequestDto answerRequest = new AddAnswerRequestDto(
                        responseText,
                        "imagen-4.0",
                        List.of(imageAttachment)
                );
                conversationService.addAnswer(conversationId, member.getId(), answerRequest);

                emitter.send(SseEmitter.event()
                        .name("done")
                        .data("이미지 생성 완료"));
                emitter.complete();

            } else {
                QuestionServiceUtils.handleStreamError(emitter, new RuntimeException("이미지 생성에 실패했습니다."));
            }

        } catch (Exception e) {
            log.error("Gemini 이미지 생성 실패: ", e);
            QuestionServiceUtils.handleStreamError(emitter, e);
        }
    }

    private String generateImageWithGemini(String prompt) {
        try {
            Map<String, Object> instance = new HashMap<>();
            instance.put("prompt", prompt);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("sampleCount", 1);
            parameters.put("aspectRatio", "1:1");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("instances", Arrays.asList(instance));
            requestBody.put("parameters", parameters);

            log.info("Gemini Imagen-4.0 이미지 생성 API 호출");
            log.info("요청 프롬프트: {}", prompt);

            String response = restClient.post()
                    .uri(geminiQuestionProperties.getCreateImageUrl())
                    .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .exchange((request, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            log.error("Imagen 4.0 API 호출 실패: {}", httpResponse.getStatusCode());
                            try {
                                String errorBody = new String(httpResponse.getBody().readAllBytes());
                                log.error("오류 응답 본문: {}", errorBody);
                            } catch (Exception e) {
                                log.error("오류 응답 읽기 실패", e);
                            }
                            return null;
                        }
                        try {
                            return new String(httpResponse.getBody().readAllBytes());
                        } catch (Exception e) {
                            log.error("응답 파싱 오류", e);
                            return null;
                        }
                    });

            if (response != null) {
                log.info("Imagen-4.0 실제 API 응답 전체: {}", response);
                return parseImagenUrlResponse(response);
            }

            return null;

        } catch (Exception e) {
            log.error("Gemini 이미지 생성 API 호출 실패: ", e);
            return null;
        }
    }

    private String parseImagenUrlResponse(String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            List<String> fieldNames = new ArrayList<>();
            rootNode.fieldNames().forEachRemaining(fieldNames::add);
            log.info("Imagen-4.0 JSON 파싱 결과 - 최상위 필드들: {}", String.join(", ", fieldNames));

            JsonNode predictions = rootNode.path("predictions");

            if (predictions.isEmpty()) {
                log.warn("Imagen 4.0 응답에 predictions가 없습니다.");
                log.info("사용 가능한 필드들: {}", rootNode.fieldNames());
                return null;
            }

            JsonNode firstPrediction = predictions.get(0);
            List<String> predictionFields = new ArrayList<>();
            firstPrediction.fieldNames().forEachRemaining(predictionFields::add);
            log.info("첫 번째 prediction 필드들: {}", String.join(", ", predictionFields));

            String base64Data = null;
            String mimeType = "image/png";

            if (firstPrediction.has("bytesBase64Encoded")) {
                base64Data = firstPrediction.path("bytesBase64Encoded").asText();
            } else if (firstPrediction.has("image")) {
                base64Data = firstPrediction.path("image").asText();
            } else if (firstPrediction.has("data")) {
                base64Data = firstPrediction.path("data").asText();
            }

            if (firstPrediction.has("mimeType")) {
                mimeType = firstPrediction.path("mimeType").asText();
            }

            if (base64Data == null || base64Data.isEmpty()) {
                log.warn("Imagen 4.0 이미지 데이터가 비어있습니다.");
                return null;
            }

            log.info("Imagen 4.0 Base64 이미지 데이터 발견: mimeType={}, 데이터 크기={} bytes",
                    mimeType, base64Data.length());

            String dataUrl = String.format("data:%s;base64,%s", mimeType, base64Data);
            log.info("Imagen 4.0 이미지 data URL 생성 완료");

            return dataUrl;

        } catch (Exception e) {
            log.error("Imagen 4.0 응답 파싱 실패", e);
            return null;
        }
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

            // Gemini용 대화 히스토리 처리 - 파일 정보 포함
            List<Map<String, Object>> messages = historyDto.messages();
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> message = messages.get(i);
                String role = (String) message.get("role");
                String content = (String) message.get("content");
                List<FileAttachmentDto> messageAttachments = (List<FileAttachmentDto>) message.get("attachments");

                Map<String, Object> geminiContent = new HashMap<>();
                geminiContent.put("role", "user".equals(role) ? "user" : "model");

                // 파일이 있는 경우 parts를 파트 형태로 구성
                if (messageAttachments != null && !messageAttachments.isEmpty() && "user".equals(role)) {
                    List<Map<String, Object>> parts = createPartsFromHistory(content, messageAttachments);
                    geminiContent.put("parts", parts);
                } else {
                    geminiContent.put("parts", List.of(Map.of("text", content)));
                }

                contents.add(geminiContent);
            }

            // 파일이 있거나 새로운 텍스트일 때 createContentWithFiles 호출
            if (messages.isEmpty() ||
                    !requestDto.content().equals(QuestionServiceUtils.getLastUserMessage(messages)) ||
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

                            QuestionServiceUtils.handleStreamError(emitter, new RuntimeException(errorMessage));
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
                                QuestionServiceUtils.handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Gemini API 스트리밍 호출 실패: ", e);
                QuestionServiceUtils.handleStreamError(emitter, e);
            }
        }
    }

    private List<Map<String, Object>> fixGeminiContentStructure(List<Map<String, Object>> contents) {
        List<Map<String, Object>> fixedContents = new ArrayList<>();

        for (int i = 0; i < contents.size(); i++) {
            Map<String, Object> content = contents.get(i);
            String role = (String) content.get("role");

            if (!fixedContents.isEmpty()) {
                Map<String, Object> lastContent = fixedContents.get(fixedContents.size() - 1);
                String lastRole = (String) lastContent.get("role");

                if (role.equals(lastRole)) {
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

    /**
     * 대화 히스토리에서 파일 정보를 포함한 parts 생성
     */
    private List<Map<String, Object>> createPartsFromHistory(String content, List<FileAttachmentDto> attachments) {
        // 이미지가 포함된 경우 추가 안내 메시지 생성
        StringBuilder enhancedContent = new StringBuilder(content);
        
        if (attachments != null && !attachments.isEmpty()) {
            boolean hasImages = attachments.stream()
                    .anyMatch(attachment -> "image".equals(attachment.fileType()));
            
            if (hasImages) {
                enhancedContent.append("\n\n[참고: 이 메시지에는 이미지가 포함되어 있었습니다. ")
                        .append("현재 질문이 이전 이미지와 관련된 경우, 이전 대화에서 제공된 이미지 분석 결과를 참고하여 답변해주세요.]");
            }
        }
        
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", enhancedContent.toString());
        
        return List.of(textPart);
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
//                    log.info("파일 처리 결과: {}", processedFile);

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

                        combinedText.append("\n\n너는 파일을 해석하는 전문가야. 다음 파일의 내용을 분석하고 사용자의 질문에 답변해줘.\n\n");
                        combinedText.append("=== 파일 전체 내용 시작 ===\n\n");
                        combinedText.append("파일명: ").append(fileName).append("\n\n");
                        combinedText.append(extractedText);
                        combinedText.append("\n\n=== 파일 전체 내용 끝 ===\n");

                        log.info("Gemini 텍스트 파일 내용 텍스트에 추가됨 - 파일: {}, 길이: {}", fileName, extractedText.length());
                    }
                } catch (Exception e) {
                    log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
                }
            }
        }

        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", combinedText.toString());
        parts.add(0, textPart);

        content.put("parts", parts);

        log.info("Gemini 최종 parts 개수: {}", parts.size());
        log.info("Gemini 최종 텍스트 내용 길이: {} 문자", combinedText.length());
        log.info("=== Gemini createContentWithFiles 끝 ===");

        return content;
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


}
