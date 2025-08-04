package com.gaebang.backend.domain.question.openai.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.dto.response.OpenaiQuestionResponseDto;
import com.gaebang.backend.domain.question.openai.entity.QuestionSession;
import com.gaebang.backend.domain.question.openai.repository.QuestionRepository;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenaiQuestionService {

    private final MemberRepository memberRepository;
    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final RestClient restClient;
    private final QuestionRepository questionRepository;

    @Transactional
    public OpenaiQuestionResponseDto createQuestion(
            OpenaiQuestionRequestDto openaiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {
        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        // 1. 활성 세션 확인 (24시간 이내)
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);
        Optional<QuestionSession> activeSession = questionRepository
                .findByMemberAndIsActiveTrueAndLastUsedAtAfter(member, cutoffTime);

        // OpenAI API 요청 데이터 준비
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", openaiQuestionProperties.getModel());
        parameters.put("instructions", "너는 AI에 최적화된 전문가야");
        parameters.put("input", openaiQuestionRequestDto.input());
        parameters.put("stream", false);

        // 2. 이전 세션이 있으면 previous_response_id 추가
        if (activeSession.isPresent()) {
            parameters.put("previous_response_id", activeSession.get().getOpenaiSessionId());
        }

        try {
            String openaiUrl = openaiQuestionProperties.getResponseUrl();
            Map<String, Object> responseBody = restClient.post()
                    .uri(openaiUrl)
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .retrieve()
                    .body(Map.class);

            String responseId = (String) responseBody.get("id");

            // 응답 내용 추출
            String content = extractContent(responseBody);

            // 3. 세션 관리
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

            return OpenaiQuestionResponseDto.fromEntity(responseId, content);

        } catch (Exception e) {
            log.error("OpenAI API 호출 실패: ", e);
            throw new RuntimeException("AI 응답 생성 중 오류가 발생했습니다.");
        }
    }

    @Transactional
    public void startNewConversation(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        questionRepository.deactivateAllByMember(member);
    }

    private String extractContent(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> outputs = (List<Map<String, Object>>) responseBody.get("output");
            if (!outputs.isEmpty()) {
                Map<String, Object> firstOutput = outputs.get(0);
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) firstOutput.get("content");
                if (!contentList.isEmpty()) {
                    return (String) contentList.get(0).get("text");
                }
            }
        } catch (Exception e) {
            log.warn("응답 내용 추출 실패", e);
        }
        return "응답을 처리할 수 없습니다.";
    }
}
