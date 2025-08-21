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
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
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

    public SseEmitter generateImageInConversation(
            Long conversationId,
            String prompt,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(300000L);

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                prompt,
                Collections.emptyList()
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performImageGeneration(emitter, conversationId, prompt, member);

        setupEmitterCallbacks(emitter, "OpenAI DALL-E 3");
        return emitter;
    }

    private void performImageGeneration(SseEmitter emitter, Long conversationId,
                                        String prompt, Member member) {
        try {
            log.info("OpenAI DALL-E 3 이미지 생성 요청: {}", prompt);

            String imageDataUrl = generateImageWithOpenAI(prompt);

            if (imageDataUrl != null) {
                Map<String, Object> imageResponse = new HashMap<>();
                imageResponse.put("imageUrl", imageDataUrl);
                imageResponse.put("prompt", prompt);
                imageResponse.put("type", "base64");

                try {
                    emitter.send(SseEmitter.event()
                            .name("image")
                            .data(imageResponse));

                    log.info("OpenAI DALL-E 3 이미지 생성 완료 및 전송");
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
                        "dall-e-3",
                        List.of(imageAttachment)
                );
                conversationService.addAnswer(conversationId, member.getId(), answerRequest);

                emitter.send(SseEmitter.event()
                        .name("done")
                        .data("이미지 생성 완료"));
                emitter.complete();

            } else {
                handleStreamError(emitter, new RuntimeException("이미지 생성에 실패했습니다."));
            }

        } catch (Exception e) {
            log.error("OpenAI DALL-E 3 이미지 생성 실패: ", e);
            handleStreamError(emitter, e);
        }
    }

    private String generateImageWithOpenAI(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "dall-e-3");
            requestBody.put("prompt", prompt);
            requestBody.put("n", 1);
            requestBody.put("size", "1024x1024");
            requestBody.put("quality", "standard");
            requestBody.put("response_format", "url");

            log.info("OpenAI DALL-E 3 이미지 생성 API 호출");
            log.info("요청 프롬프트: {}", prompt);

            String response = restClient.post()
                    .uri("https://api.openai.com/v1/images/generations")
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .exchange((request, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            log.error("OpenAI DALL-E 3 API 호출 실패: {}", httpResponse.getStatusCode());
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
                log.info("OpenAI DALL-E 3 실제 API 응답 전체: {}", response);
                return parseOpenAIImageResponseAndConvertToBase64(response);
            }

            return null;

        } catch (Exception e) {
            log.error("OpenAI DALL-E 3 이미지 생성 API 호출 실패: ", e);
            return null;
        }
    }

    private String parseOpenAIImageResponseAndConvertToBase64(String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            List<String> fieldNames = new ArrayList<>();
            rootNode.fieldNames().forEachRemaining(fieldNames::add);
            log.info("OpenAI DALL-E 3 JSON 파싱 결과 - 최상위 필드들: {}", String.join(", ", fieldNames));

            JsonNode dataArray = rootNode.path("data");

            if (dataArray.isEmpty()) {
                log.warn("OpenAI DALL-E 3 응답에 data가 없습니다.");
                return null;
            }

            JsonNode firstData = dataArray.get(0);
            List<String> dataFields = new ArrayList<>();
            firstData.fieldNames().forEachRemaining(dataFields::add);
            log.info("첫 번째 data 필드들: {}", String.join(", ", dataFields));

            if (firstData.has("url")) {
                String imageUrl = firstData.path("url").asText();
                String revisedPrompt = firstData.path("revised_prompt").asText("");

                log.info("OpenAI DALL-E 3 이미지 URL 수신: URL={}, Revised Prompt={}", imageUrl, revisedPrompt);

                String base64DataUrl = downloadImageAndConvertToBase64(imageUrl);
                if (base64DataUrl != null) {
                    log.info("OpenAI DALL-E 3 이미지 base64 변환 완료");
                    return base64DataUrl;
                }

                log.warn("OpenAI DALL-E 3 이미지 base64 변환 실패");
                return null;
            }

            log.warn("OpenAI DALL-E 3 응답에 url 필드가 없습니다.");
            return null;

        } catch (Exception e) {
            log.error("OpenAI DALL-E 3 응답 파싱 실패", e);
            return null;
        }
    }

    private String downloadImageAndConvertToBase64(String imageUrl) {
        try {
            log.info("이미지 다운로드 시작: {}", imageUrl);

            URL url = new URL(imageUrl);
            try (InputStream inputStream = url.openStream()) {
                byte[] imageBytes = inputStream.readAllBytes();
                String base64Data = Base64.getEncoder().encodeToString(imageBytes);

                String mimeType = "image/png";

                String dataUrl = String.format("data:%s;base64,%s", mimeType, base64Data);

                log.info("이미지 다운로드 및 base64 변환 완료: 크기={} bytes", imageBytes.length);
                return dataUrl;
            }

        } catch (Exception e) {
            log.error("이미지 다운로드 및 base64 변환 실패: {}", imageUrl, e);
            return null;
        }
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

            List<Map<String, Object>> messages = new ArrayList<>();

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
                    !requestDto.content().equals(getLastUserMessage(historyMessages)) ||
                    (requestDto.files() != null && !requestDto.files().isEmpty())) {

                Map<String, Object> content = createContentWithFiles(
                        requestDto.content(),
                        requestDto.files()
                );
                messages.add(content);
            }

            parameters.put("model", modelToUse);
            parameters.put("messages", messages);
            parameters.put("temperature", 0.7);
            parameters.put("max_tokens", 4096);
            parameters.put("stream", true);

            log.info("=== OpenAI API 요청 데이터 ===");
            log.info("모델: {}", modelToUse);
            log.info("messages 개수: {}", messages.size());

            if (!messages.isEmpty()) {
                Map<String, Object> lastMessage = messages.get(messages.size() - 1);
                log.info("마지막 message role: {}", lastMessage.get("role"));

                Object contentObj = lastMessage.get("content");
                if (contentObj instanceof String) {
                    String textContent = (String) contentObj;
                    log.info("content 텍스트 길이: {} 문자", textContent.length());
                    log.info("content 텍스트 내용: {}", textContent.length() > 100 ? textContent.substring(0, 100) + "..." : textContent);
                } else if (contentObj instanceof List) {
                    List<Map<String, Object>> contentParts = (List<Map<String, Object>>) contentObj;
                    log.info("content parts 개수: {}", contentParts.size());

                    for (int i = 0; i < contentParts.size(); i++) {
                        Map<String, Object> part = contentParts.get(i);
                        String type = (String) part.get("type");
                        log.info("part[{}] type: {}", i, type);

                        if ("text".equals(type)) {
                            String text = (String) part.get("text");
                            log.info("part[{}] 텍스트 길이: {} 문자", i, text != null ? text.length() : 0);
                        } else if ("image_url".equals(type)) {
                            Map<String, Object> imageUrl = (Map<String, Object>) part.get("image_url");
                            if (imageUrl != null) {
                                String url = (String) imageUrl.get("url");
                                log.info("part[{}] 이미지 URL 길이: {} 문자", i, url != null ? url.length() : 0);
                            }
                        }
                    }
                }
            }
            log.info("=== OpenAI API 요청 데이터 끝 ===");

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

                            handleStreamError(emitter, new RuntimeException(errorMessage));
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
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(content));
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

    /**
     * 대화 히스토리에서 파일 정보를 포함한 content 파트 생성
     */
    private List<Map<String, Object>> createContentPartsFromHistory(String content, List<FileAttachmentDto> attachments) {
        // 히스토리에서는 이미지 데이터를 다시 전송하지 않음
        // 이미지 정보는 이미 content에 텍스트로 포함되어 있음 ("이전에 업로드한 이미지: filename.png")
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", content);
        
        return List.of(textPart);
    }

    private Map<String, Object> createContentWithFiles(String textContent, List<MultipartFile> files) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");

        log.info("=== OpenAI createContentWithFiles 시작 ===");
        log.info("텍스트 내용: {}", textContent);
        log.info("파일 개수: {}", files != null ? files.size() : 0);

        if (files != null && !files.isEmpty()) {
            List<Map<String, Object>> contentParts = new ArrayList<>();

            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");

            StringBuilder combinedText = new StringBuilder(textContent);

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

                        Map<String, Object> imageUrl = new HashMap<>();
                        imageUrl.put("url", String.format("data:%s;base64,%s", mimeType, base64));
                        imagePart.put("image_url", imageUrl);

                        contentParts.add(imagePart);

                        log.info("OpenAI 이미지 파트 추가됨 - MIME: {}, Base64 길이: {}", mimeType, base64.length());
                    } else if ("text".equals(fileType)) {
                        String extractedText = (String) processedFile.get("extractedText");
                        String fileName = (String) processedFile.get("fileName");

                        combinedText.append("\n\n--- 파일: ").append(fileName).append(" ---\n");
                        combinedText.append(extractedText);
                        combinedText.append("\n--- 파일 끝 ---\n");

                        log.info("OpenAI 텍스트 파일 내용 텍스트에 추가됨 - 파일: {}, 길이: {}", fileName, extractedText.length());
                    }
                } catch (Exception e) {
                    log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
                }
            }

            textPart.put("text", combinedText.toString());
            contentParts.add(0, textPart);

            message.put("content", contentParts);
            log.info("OpenAI 최종 content parts 개수: {}", contentParts.size());
        } else {
            message.put("content", textContent);
            log.info("OpenAI 파일 없음 - 텍스트만 사용");
        }

        log.info("OpenAI 최종 텍스트 내용 길이: {} 문자", textContent.length());
        log.info("=== OpenAI createContentWithFiles 끝 ===");

        return message;
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
