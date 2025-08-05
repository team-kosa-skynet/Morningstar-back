package com.gaebang.backend.domain.question.claude.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.claude.dto.request.ClaudeMessage;
import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.dto.response.ClaudeQuestionResponseDto;
import com.gaebang.backend.domain.question.claude.util.ClaudeQuestionProperties;
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
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeQuestionService {

    private final ClaudeQuestionProperties claudeQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public ClaudeQuestionResponseDto createQuestion(
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);

        ClaudeMessage userMessage = ClaudeMessage.builder()
                .role("user")
                .content(claudeQuestionRequestDto.content())
                .build();

        Map<String, Object> parameters = createRequestParameters(userMessage, false);

        try {
            String claudeUrl = claudeQuestionProperties.getResponseUrl();
            ClaudeQuestionResponseDto responseDto = restClient.post()
                    .uri(claudeUrl)
                    .header("x-api-key", claudeQuestionProperties.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .retrieve()
                    .body(ClaudeQuestionResponseDto.class);

            if (responseDto == null) {
                throw new RuntimeException("Claude API 응답이 null입니다.");
            }

            log.info("Claude API 호출 성공 - 모델: {}, 사용 토큰: input={}, output={}",
                    responseDto.model(),
                    responseDto.usage().input_tokens(),
                    responseDto.usage().output_tokens());

            return responseDto;

        } catch (Exception e) {
            log.error("Claude API 호출 실패: ", e);
            throw new RuntimeException("AI 응답 생성 중 오류가 발생했습니다.");
        }
    }

    public SseEmitter createQuestionStream(
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(30000L);

        ClaudeMessage userMessage = ClaudeMessage.builder()
                .role("user")
                .content(claudeQuestionRequestDto.content())
                .build();

        Map<String, Object> parameters = createRequestParameters(userMessage, true);

        CompletableFuture.runAsync(() -> {
            try {
                String claudeUrl = claudeQuestionProperties.getResponseUrl();

                restClient.post()
                        .uri(claudeUrl)
                        .header("x-api-key", claudeQuestionProperties.getApiKey())
                        .header("anthropic-version", "2023-06-01")
                        .header("Content-Type", "application/json")
                        .body(parameters)
                        .exchange((request, response) -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new StringReader(new String(response.getBody().readAllBytes())))) {

                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("event: content_block_delta")) {
                                        String dataLine = reader.readLine();
                                        if (dataLine != null && dataLine.startsWith("data: ")) {
                                            String jsonData = dataLine.substring(6);
                                            String content = parseClaudeStreamResponse(jsonData);
                                            if (content != null && !content.isEmpty()) {
                                                emitter.send(SseEmitter.event()
                                                        .name("message")
                                                        .data(content));
                                            }
                                        }
                                    }
                                }

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("스트리밍 완료"));
                                emitter.complete();

                            } catch (IOException e) {
                                log.error("스트리밍 중 오류 발생", e);
                                emitter.completeWithError(e);
                            }
                            return null;
                        });

            } catch (Exception e) {
                log.error("Claude API 스트리밍 호출 실패: ", e);
                handleStreamError(emitter, e);
            }
        });

        setupEmitterCallbacks(emitter, "Claude");
        return emitter;
    }

    private Member validateAndGetMember(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();
        if (memberId == null) {
            throw new UserInvalidAccessException();
        }
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());
    }

    private Map<String, Object> createRequestParameters(ClaudeMessage userMessage, boolean stream) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", claudeQuestionProperties.getModel());
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0);
        parameters.put("system", "너는 AI에 최적화된 전문가야");
        parameters.put("messages", List.of(userMessage));
        if (stream) {
            parameters.put("stream", true);
        }
        return parameters;
    }

    private String parseClaudeStreamResponse(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);
            JsonNode delta = jsonNode.get("delta");
            if (delta != null && delta.has("text")) {
                return delta.get("text").asText();
            }
            return null;
        } catch (Exception e) {
            log.warn("Claude 스트리밍 응답 파싱 실패", e);
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
}
