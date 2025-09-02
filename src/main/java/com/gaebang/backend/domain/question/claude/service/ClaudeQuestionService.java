package com.gaebang.backend.domain.question.claude.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.util.ClaudeQuestionProperties;
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
public class ClaudeQuestionService {

    private final MemberRepository memberRepository;
    private final ClaudeQuestionProperties claudeQuestionProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final FileProcessingService fileProcessingService;

    public SseEmitter createQuestionStream(
            Long conversationId,
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);
        SseEmitter emitter = new SseEmitter(300000L);

        List<FileAttachmentDto> attachments = QuestionServiceUtils.processFiles(claudeQuestionRequestDto.files(), fileProcessingService);

        // 파일 내용을 미리 결합하여 content 생성
        String contentWithFiles = QuestionServiceUtils.buildContentWithExtractedFiles(
                claudeQuestionRequestDto.content(),
                claudeQuestionRequestDto.files(),
                fileProcessingService
        );

        AddQuestionRequestDto questionRequest = new AddQuestionRequestDto(
                contentWithFiles,
                attachments
        );
        conversationService.addQuestion(conversationId, member.getId(), questionRequest);

        performApiCallWithFiles(emitter, conversationId, claudeQuestionRequestDto.model(), claudeQuestionRequestDto, member, attachments);

        QuestionServiceUtils.setupEmitterCallbacks(emitter, "Claude");
        return emitter;
    }


    private void performApiCallWithFiles(SseEmitter emitter, Long conversationId, String requestModel,
                                         ClaudeQuestionRequestDto requestDto, Member member,
                                         List<FileAttachmentDto> attachments) {
        StringBuilder fullResponse = new StringBuilder();

        try {
            String modelToUse = claudeQuestionProperties.getModelToUse(requestModel);

            ConversationHistoryDto historyDto = conversationService.getConversationHistory(
                    conversationId,
                    member.getId(),
                    20  // 최근 20개 메시지만 가져오기 (질문-답변 쌍 10개)
            );

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("model", modelToUse);
            parameters.put("max_tokens", 4096);
            parameters.put("stream", true);

            List<Map<String, Object>> messages = new ArrayList<>();

            // 시스템 메시지 추가 - 메시지 번호 참조 금지
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "user");
            systemMessage.put("content", List.of(Map.of("type", "text", "text", 
                "**CRITICAL INSTRUCTION**: NEVER use [메시지 X] or [Message X] format in your responses. " +
                "This is STRICTLY PROHIBITED. Instead of saying '[메시지 16]에서 설명한...', " +
                "use natural expressions like '이전에 말씀드린 대로', '앞서 설명한 내용처럼', '방금 전 언급한', etc. " +
                "This rule applies to ALL your responses without exception.")));
            messages.add(systemMessage);

            // Claude용 대화 히스토리 처리 - 파일 정보 포함
            List<Map<String, Object>> historyMessages = historyDto.messages();
            for (Map<String, Object> message : historyMessages) {
                String role = (String) message.get("role");
                String content = (String) message.get("content");
                List<FileAttachmentDto> messageAttachments = (List<FileAttachmentDto>) message.get("attachments");

                Map<String, Object> claudeMessage = new HashMap<>();
                claudeMessage.put("role", "user".equals(role) ? "user" : "assistant");

                // 파일이 있는 경우 content를 파트 형태로 구성
                if (messageAttachments != null && !messageAttachments.isEmpty() && "user".equals(role)) {
                    List<Map<String, Object>> contentParts = createContentPartsFromHistory(content, messageAttachments);
                    claudeMessage.put("content", contentParts);
                } else {
                    claudeMessage.put("content", List.of(Map.of("type", "text", "text", content)));
                }

                messages.add(claudeMessage);
            }

            // 파일이 있거나 새로운 텍스트일 때 createContentWithFiles 호출
            if (messages.isEmpty() ||
                    !requestDto.content().equals(QuestionServiceUtils.getLastUserMessage(historyMessages)) ||
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

            if (!messages.isEmpty()) {
                Map<String, Object> lastMessage = messages.get(messages.size() - 1);

                Object contentObj = lastMessage.get("content");
                if (contentObj instanceof List) {
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) contentObj;

                    for (int i = 0; i < contentList.size(); i++) {
                        Map<String, Object> part = contentList.get(i);
                        String type = (String) part.get("type");

                        if ("text".equals(type)) {
                            String text = (String) part.get("text");
                        } else if ("image".equals(type)) {
                            Map<String, Object> source = (Map<String, Object>) part.get("source");
                            if (source != null) {
                                String mediaType = (String) source.get("media_type");
                                String data = (String) source.get("data");
                            }
                        }
                    }
                } else {
                    log.info("content가 List가 아님: {}", contentObj != null ? contentObj.getClass().getSimpleName() : "null");
                }
            }

            String claudeUrl = claudeQuestionProperties.getResponseUrl();

            restClient.post()
                    .uri(claudeUrl)
                    .header("x-api-key", claudeQuestionProperties.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, response) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Claude API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorMessage = String.format("Claude API 호출 실패: %s", response.getStatusCode());
                            log.error(errorMessage);
                            QuestionServiceUtils.handleStreamError(emitter, new RuntimeException(errorMessage));
                            return null;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {

                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (Thread.currentThread().isInterrupted()) {
                                    log.info("Claude API 스레드 인터럽트 감지 - 스트리밍 중단");
                                    break;
                                }

                                line = line.trim();
                                if (line.isEmpty()) continue;

                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    String content = parseClaudeStreamResponse(data);
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
                                            log.warn("Claude API 클라이언트 연결 종료됨 - 스트리밍 중단");
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
                                }

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("스트리밍 완료"));
                                emitter.complete();
                            }

                        } catch (IOException e) {
                            if (!Thread.currentThread().isInterrupted()) {
                                log.error("Claude API 스트리밍 중 네트워크 오류", e);
                                QuestionServiceUtils.handleStreamError(emitter, e);
                            }
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Claude API 스트리밍 호출 실패: ", e);
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

    private List<Map<String, Object>> createContentWithFiles(String textContent, List<MultipartFile> files) {
        List<Map<String, Object>> content = new ArrayList<>();

        StringBuilder combinedText = new StringBuilder(textContent);

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                try {
                    Map<String, Object> processedFile = fileProcessingService.processFile(file);

                    String fileType = (String) processedFile.get("type");

                    if ("image".equals(fileType)) {
                        String base64 = (String) processedFile.get("base64");
                        String mimeType = (String) processedFile.get("mimeType");

                        Map<String, Object> imagePart = new HashMap<>();
                        imagePart.put("type", "image");

                        Map<String, Object> source = new HashMap<>();
                        source.put("type", "base64");
                        source.put("media_type", mimeType);
                        source.put("data", base64);

                        imagePart.put("source", source);
                        content.add(imagePart);

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
        }

        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", combinedText.toString());
        content.add(0, textPart);

        return content;
    }



    private String parseClaudeStreamResponse(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);

            if (jsonNode.has("type") && "content_block_delta".equals(jsonNode.get("type").asText())) {
                JsonNode delta = jsonNode.get("delta");
                if (delta != null && delta.has("text")) {
                    return delta.get("text").asText();
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("Claude API 스트리밍 응답 파싱 실패: {}", e.getMessage());
            return null;
        }
    }



}
