package com.gaebang.backend.domain.question.openai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
import com.gaebang.backend.domain.question.common.service.FileProcessingService;
import com.gaebang.backend.domain.question.common.util.QuestionServiceUtils;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

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
    private final PointService pointService;

    public SseEmitter createQuestionStream(
            Long conversationId,
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);
        SseEmitter emitter = new SseEmitter(300000L);

        // 포인트 차감과 질문 저장을 트랜잭션으로 묶어서 처리
        List<FileAttachmentDto> attachments = processQuestionWithTransaction(
                conversationId, openaiQuestionRequestDto, principalDetails, member
        );

        // AI API 호출은 트랜잭션 외부에서 처리
        performApiCallWithFiles(emitter, conversationId, openaiQuestionRequestDto.model(), openaiQuestionRequestDto, member, attachments);

        QuestionServiceUtils.setupEmitterCallbacks(emitter, "OpenAI");
        return emitter;
    }

    @Transactional
    private List<FileAttachmentDto> processQuestionWithTransaction(
            Long conversationId,
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails,
            Member member
    ) {
        // 질문 포인트 차감 (10포인트)
        pointService.deductQuestionPoints(principalDetails);

        List<FileAttachmentDto> attachments = QuestionServiceUtils.processFiles(openaiQuestionRequestDto.files(), fileProcessingService);

        // 파일 내용을 미리 결합하여 content 생성
        String contentWithFiles = QuestionServiceUtils.buildContentWithExtractedFiles(
                openaiQuestionRequestDto.content(),
                openaiQuestionRequestDto.files(),
                fileProcessingService
        );

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                contentWithFiles,
                attachments
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        return attachments;
    }

    public SseEmitter generateImageInConversation(
            Long conversationId,
            String prompt,
            String model,
            PrincipalDetails principalDetails
    ) {
        Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);
        SseEmitter emitter = new SseEmitter(300000L);

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                prompt,
                Collections.emptyList()
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performImageGeneration(emitter, conversationId, prompt, model, member);

        QuestionServiceUtils.setupEmitterCallbacks(emitter, "OpenAI DALL-E 3");
        return emitter;
    }

    private void performImageGeneration(SseEmitter emitter, Long conversationId,
                                        String prompt, String model, Member member) {
        try {
            String modelToUse = model != null && !model.trim().isEmpty() ? model : "dall-e-3";

            String imageDataUrl = generateImageWithOpenAI(prompt, model);

            if (imageDataUrl != null) {
                Map<String, Object> imageResponse = new HashMap<>();
                imageResponse.put("imageUrl", imageDataUrl);
                imageResponse.put("prompt", prompt);
                imageResponse.put("type", "base64");

                try {
                    emitter.send(SseEmitter.event()
                            .name("image")
                            .data(imageResponse));

                } catch (IOException e) {
                    log.warn("OpenAI 이미지 전송 실패 - 클라이언트 연결 종료됨");
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
                        modelToUse,
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
            QuestionServiceUtils.handleStreamError(emitter, e);
        }
    }

    private String generateImageWithOpenAI(String prompt, String model) {
        try {
            String modelToUse = model != null && !model.trim().isEmpty() ? model : "dall-e-3";
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelToUse);
            requestBody.put("prompt", prompt);
            requestBody.put("n", 1);
            requestBody.put("size", "1024x1024");
            requestBody.put("quality", "standard");
            requestBody.put("response_format", "url");

            String response = restClient.post()
                    .uri("https://api.openai.com/v1/images/generations")
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .exchange((request, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            log.error("OpenAI {} API 호출 실패: {}", modelToUse, httpResponse.getStatusCode());
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
                return parseOpenAIImageResponseAndConvertToBase64(response);
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private String parseOpenAIImageResponseAndConvertToBase64(String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            List<String> fieldNames = new ArrayList<>();
            rootNode.fieldNames().forEachRemaining(fieldNames::add);

            JsonNode dataArray = rootNode.path("data");

            if (dataArray.isEmpty()) {
                log.warn("OpenAI 이미지 응답에 data가 없습니다.");
                return null;
            }

            JsonNode firstData = dataArray.get(0);
            List<String> dataFields = new ArrayList<>();
            firstData.fieldNames().forEachRemaining(dataFields::add);

            if (firstData.has("url")) {
                String imageUrl = firstData.path("url").asText();
                String revisedPrompt = firstData.path("revised_prompt").asText("");

                String base64DataUrl = downloadImageAndConvertToBase64(imageUrl);
                if (base64DataUrl != null) {
                    return base64DataUrl;
                }

                log.warn("OpenAI 이미지 base64 변환 실패");
                return null;
            }

            return null;

        } catch (Exception e) {
            log.error("OpenAI 이미지 응답 파싱 실패", e);
            return null;
        }
    }

    private String downloadImageAndConvertToBase64(String imageUrl) {
        try {

            URL url = new URL(imageUrl);
            try (InputStream inputStream = url.openStream()) {
                byte[] imageBytes = inputStream.readAllBytes();
                String base64Data = Base64.getEncoder().encodeToString(imageBytes);

                String mimeType = "image/png";

                String dataUrl = String.format("data:%s;base64,%s", mimeType, base64Data);

                return dataUrl;
            }

        } catch (Exception e) {
            log.error("이미지 다운로드 및 base64 변환 실패: {}", imageUrl, e);
            return null;
        }
    }


    private void performApiCallWithFiles(SseEmitter emitter, Long conversationId, String requestModel,
                                         OpenaiQuestionRequestDto requestDto, Member member,
                                         List<FileAttachmentDto> attachments) {
        StringBuilder fullResponse = new StringBuilder();

        try {
            String modelToUse = openaiQuestionProperties.getModelToUse(requestModel);

            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    20 // 최근 20개 메시지만 가져오기 (질문-답변 쌍 10개)
            );

            Map<String, Object> parameters = new HashMap<>();

            List<Map<String, Object>> messages = new ArrayList<>();

            // 시스템 메시지 추가 - 메시지 번호 참조 금지
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", "**CRITICAL INSTRUCTION**: NEVER use [메시지 X] or [Message X] format in your responses. " +
                "This is STRICTLY PROHIBITED. Instead of saying '[메시지 16]에서 설명한...', " +
                "use natural expressions like '이전에 말씀드린 대로', '앞서 설명한 내용처럼', '방금 전 언급한', etc. " +
                "This rule applies to ALL your responses without exception.");
            messages.add(systemMessage);

            // OpenAI용 대화 히스토리 처리 - 파일 정보 포함
            List<Map<String, Object>> historyMessages = historyDto.messages();
            for (Map<String, Object> message : historyMessages) {
                String role = (String) message.get("role");
                String content = (String) message.get("content");
                List<FileAttachmentDto> messageAttachments = (List<FileAttachmentDto>) message.get("attachments");

                Map<String, Object> openaiMessage = new HashMap<>();
                openaiMessage.put("role", "user".equals(role) ? "user" : "assistant");

                // 파일이 있는 경우 content를 파트 형태로 구성
                if (messageAttachments != null && !messageAttachments.isEmpty() && "user".equals(role)) {
                    List<Map<String, Object>> contentParts = createContentPartsFromHistory(content, messageAttachments);
                    openaiMessage.put("content", contentParts);
                } else {
                    openaiMessage.put("content", content);
                }

                messages.add(openaiMessage);
            }

            // 파일이 있거나 새로운 텍스트일 때 새 메시지 추가
            if (historyMessages.isEmpty() ||
                    !requestDto.content().equals(QuestionServiceUtils.getLastUserMessage(historyMessages)) ||
                    (requestDto.files() != null && !requestDto.files().isEmpty())) {

                Map<String, Object> content = createContentWithFiles(
                        requestDto.content(),
                        requestDto.files()
                );
                messages.add(content);
            }

            parameters.put("model", modelToUse);
            parameters.put("messages", messages);
            parameters.put("stream", true);

            // gpt-5 라인업은 파라미터 일부 다르게 설정
            if (modelToUse.startsWith("gpt-5")) {
                parameters.put("temperature", 1);
                parameters.put("max_completion_tokens", 4096);
            } else {
                parameters.put("temperature", 0.7);
                parameters.put("max_tokens", 4096);
            }

            if (!messages.isEmpty()) {
                Map<String, Object> lastMessage = messages.get(messages.size() - 1);

                Object contentObj = lastMessage.get("content");
                if (contentObj instanceof String) {
                    String textContent = (String) contentObj;
                } else if (contentObj instanceof List) {
                    List<Map<String, Object>> contentParts = (List<Map<String, Object>>) contentObj;

                    for (int i = 0; i < contentParts.size(); i++) {
                        Map<String, Object> part = contentParts.get(i);
                        String type = (String) part.get("type");

                        if ("text".equals(type)) {
                            String text = (String) part.get("text");
                        } else if ("image_url".equals(type)) {
                            Map<String, Object> imageUrl = (Map<String, Object>) part.get("image_url");
                            if (imageUrl != null) {
                                String url = (String) imageUrl.get("url");
                            }
                        }
                    }
                }
            }

            restClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("OpenAI API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("OpenAI API 호출 실패: %s", response.getStatusCode());
                            log.error(errorMessage);

                            try (BufferedReader errorReader = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String errorLine;
                                StringBuilder errorBody = new StringBuilder();
                                while ((errorLine = errorReader.readLine()) != null) {
                                    errorBody.append(errorLine);
                                }
                                log.error("OpenAI API 에러 응답: {}", errorBody.toString());
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
                                    log.info("OpenAI API 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    String content = parseOpenAIStreamResponse(data);
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);

                                        try {
                                            Map<String, Object> messageData = new HashMap<>();
                                            messageData.put("content", content);
                                            messageData.put("type", "text");

                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(messageData));
                                        } catch (IOException e) {
                                            log.warn("OpenAI API 클라이언트 연결 종료됨 - 스트리밍 중단");
                                            return null;
                                        }
                                    }
                                }
                            }

                            if (!Thread.currentThread().isInterrupted()) {
                                if (fullResponse.length() > 0) {
                                    // 이미지 파일이 있는 경우 답변 앞에 분석 결과 표시 추가
                                    String finalAnswer = formatAnswerWithImageContext(fullResponse.toString(), attachments);

                                    AddAnswerRequestDto answerRequest = new AddAnswerRequestDto(
                                            finalAnswer,
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
                                log.error("OpenAI API 스트리밍 중 네트워크 오류", e);
                                QuestionServiceUtils.handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("OpenAI API 스트리밍 호출 실패: ", e);
                QuestionServiceUtils.handleStreamError(emitter, e);
            }
        }
    }

    /**
     * 대화 히스토리에서 파일 정보를 포함한 content 파트 생성
     */
    private List<Map<String, Object>> createContentPartsFromHistory(String content, List<FileAttachmentDto> attachments) {
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
        textPart.put("type", "text");
        textPart.put("text", enhancedContent.toString());

        return List.of(textPart);
    }

    private Map<String, Object> createContentWithFiles(String textContent, List<MultipartFile> files) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");

        if (files != null && !files.isEmpty()) {
            List<Map<String, Object>> contentParts = new ArrayList<>();

            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");

            StringBuilder combinedText = new StringBuilder(textContent);

            for (MultipartFile file : files) {
                try {
                    Map<String, Object> processedFile = fileProcessingService.processFile(file);

                    String fileType = (String) processedFile.get("type");

                    if ("image".equals(fileType)) {
                        String fileName = (String) processedFile.get("fileName");
                        
                        // 이미지를 텍스트 설명으로 처리 (image_url 대신)
                        combinedText.append("\n\n--- 파일: ").append(fileName).append(" ---\n");
                        combinedText.append("업로드된 이미지: ").append(fileName);
                        combinedText.append("\n\n[이미지 분석 안내: 이 이미지는 현재 질문에서만 직접 분석됩니다. ");
                        combinedText.append("향후 이 이미지에 대한 추가 질문이 있을 경우, 이번 답변에서 제공된 분석 결과를 참고해주세요.]");
                        combinedText.append("\n--- 파일 끝 ---");

                    } else if ("text".equals(fileType)) {
                        String extractedText = (String) processedFile.get("extractedText");
                        String fileName = (String) processedFile.get("fileName");

                        combinedText.append("\n\n너는 파일을 해석하는 전문가야. 다음 파일의 내용을 분석하고 사용자의 질문에 답변해줘.\n\n");
                        combinedText.append("=== 파일 전체 내용 시작 ===\n\n");
                        combinedText.append("파일명: ").append(fileName).append("\n\n");
                        combinedText.append(extractedText);
                        combinedText.append("\n\n=== 파일 전체 내용 끝 ===\n");

                    }
                } catch (Exception e) {
                    log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
                }
            }

            textPart.put("text", combinedText.toString());
            contentParts.add(0, textPart);

            message.put("content", contentParts);
        } else {
            message.put("content", textContent);
        }

        return message;
    }




    private String parseOpenAIStreamResponse(String data) {
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
            log.warn("OpenAI API 스트리밍 응답 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 이미지 파일이 포함된 질문에 대한 답변 형식을 가공
     * [파일명에 대한 분석 결과]: 답변내용 형식으로 변환
     */
    private String formatAnswerWithImageContext(String originalAnswer, List<FileAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return originalAnswer;
        }

        // 이미지 파일만 필터링
        List<String> imageFileNames = attachments.stream()
                .filter(attachment -> "image".equals(attachment.fileType()))
                .map(FileAttachmentDto::fileName)
                .toList();

        if (imageFileNames.isEmpty()) {
            return originalAnswer;
        }

        // 이미지 파일이 여러 개인 경우 모든 파일명 포함
        String fileContext;
        if (imageFileNames.size() == 1) {
            fileContext = String.format("[%s에 대한 분석 결과]", imageFileNames.get(0));
        } else {
            String fileList = String.join(", ", imageFileNames);
            fileContext = String.format("[%s에 대한 분석 결과]", fileList);
        }

        return fileContext + ": " + originalAnswer;
    }


}
