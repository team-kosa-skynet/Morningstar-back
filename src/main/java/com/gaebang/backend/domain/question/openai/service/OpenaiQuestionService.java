package com.gaebang.backend.domain.question.openai.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.entity.QuestionSession;
import com.gaebang.backend.domain.question.openai.repository.QuestionRepository;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenaiQuestionService {

    private final MemberRepository memberRepository;
    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final RestClient restClient;
    private final QuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void startNewConversation(PrincipalDetails principalDetails) {
        Member member = validateAndGetMember(principalDetails);
        questionRepository.deactivateAllByMember(member);
    }

    public SseEmitter createQuestionStream(
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Member member = validateAndGetMember(principalDetails);
        SseEmitter emitter = new SseEmitter(30000L);

        // 1. 활성 세션 확인 (24시간 이내)
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);
        Optional<QuestionSession> activeSession = questionRepository
                .findByMemberAndIsActiveTrueAndLastUsedAtAfter(member, cutoffTime);

        // OpenAI Responses API 스트리밍 요청 데이터 준비
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", openaiQuestionProperties.getModel());
        parameters.put("input", openaiQuestionRequestDto.input());
        parameters.put("instructions", "너는 AI에 최적화된 전문가야");
        parameters.put("stream", true);

        // 2. 이전 세션이 있으면 previous_response_id 추가
        if (activeSession.isPresent()) {
            parameters.put("previous_response_id", activeSession.get().getOpenaiSessionId());
        }

        CompletableFuture.runAsync(() -> {
            try {
                String openaiUrl = openaiQuestionProperties.getResponseUrl();

                restClient.post()
                        .uri(openaiUrl)
                        .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                        .header("Content-Type", "application/json")
                        .body(parameters)
                        .exchange((request, response) -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new StringReader(new String(response.getBody().readAllBytes())))) {

                                String line;
                                String responseId = null;

                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("event: ")) {
                                        // 이벤트 타입 라인 건너뛰기
                                        continue;
                                    }
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6);
                                        if (!data.trim().isEmpty()) {
                                            String content = parseResponsesStreamResponse(data);
                                            if (content != null && !content.isEmpty()) {
                                                emitter.send(SseEmitter.event()
                                                        .name("message")
                                                        .data(content));
                                            }
                                            if (responseId == null) {
                                                responseId = extractResponseId(data);
                                            }
                                        }
                                    }
                                }

                                // 세션 관리
                                if (responseId != null) {
                                    manageSessionForStream(member, activeSession, responseId);
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
                log.error("OpenAI API 스트리밍 호출 실패: ", e);
                handleStreamError(emitter, e);
            }
        });

        setupEmitterCallbacks(emitter, "OpenAI");
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

    private String parseResponsesStreamResponse(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);
            String eventType = jsonNode.get("type").asText();

            // 실시간 텍스트 델타만 처리
            if ("response.output_text.delta".equals(eventType)) {
                JsonNode delta = jsonNode.get("delta");
                if (delta != null) {
                    return delta.asText();
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("OpenAI 스트리밍 응답 파싱 실패", e);
            return null;
        }
    }

    private String extractResponseId(String data) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);
            String eventType = jsonNode.get("type").asText();

            // response.created 이벤트에서 ID 추출
            if ("response.created".equals(eventType)) {
                JsonNode response = jsonNode.get("response");
                if (response != null && response.has("id")) {
                    return response.get("id").asText();
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("Response ID 추출 실패", e);
            return null;
        }
    }

    @Transactional
    public void manageSession(Member member, Optional<QuestionSession> activeSession, String responseId) {
        if (activeSession.isPresent()) {
            // 기존 세션 업데이트
            QuestionSession session = activeSession.get();
            session.updateLastUsed();
            questionRepository.save(session);
        } else {
            // 새 세션 생성 (기존 세션들 비활성화)
            questionRepository.deactivateAllByMember(member);

            QuestionSession newSession = QuestionSession.builder()
                    .member(member)
                    .openaiSessionId(responseId)
                    .build();
            questionRepository.save(newSession);
        }
    }

    @Transactional
    public void manageSessionForStream(Member member, Optional<QuestionSession> activeSession, String responseId) {
        // 스트리밍용 세션 관리 (비동기 컨텍스트에서 호출)
        manageSession(member, activeSession, responseId);
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