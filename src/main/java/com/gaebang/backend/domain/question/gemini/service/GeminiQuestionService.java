package com.gaebang.backend.domain.question.gemini.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiMessage;
import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.dto.response.GeminiQuestionResponseDto;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiQuestionService {

    private final GeminiQuestionProperties geminiQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;

    public GeminiQuestionResponseDto createQuestion(
            GeminiQuestionRequestDto geminiQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        // 사용자 메시지 생성
        GeminiMessage userMessage = GeminiMessage.builder()
                .parts(List.of(Map.of("text", geminiQuestionRequestDto.content())))
                .build();

        // Gemini API 요청 데이터 준비
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("contents", List.of(userMessage));

        try {
            String geminiUrl = geminiQuestionProperties.getResponseUrl();
            GeminiQuestionResponseDto responseDto = restClient.post()
                    .uri(geminiUrl)
                    .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .retrieve()
                    .body(GeminiQuestionResponseDto.class);

            if (responseDto == null) {
                throw new RuntimeException("Gemini API 응답이 null입니다.");
            }

            // 성공 로깅
            log.info("Gemini API 호출 성공 - 모델: {}, 사용 토큰: prompt={}, candidates={}, total={}",
                    geminiQuestionProperties.getModel(),
                    responseDto.usageMetadata().promptTokenCount(),
                    responseDto.usageMetadata().candidatesTokenCount(),
                    responseDto.usageMetadata().totalTokenCount());

            return responseDto;

        } catch (Exception e) {
            log.error("Gemini API 호출 실패: ", e);
            throw new RuntimeException("AI 응답 생성 중 오류가 발생했습니다.");
        }
    }
}
