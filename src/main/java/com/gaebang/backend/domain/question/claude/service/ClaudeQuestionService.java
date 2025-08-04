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
public class ClaudeQuestionService {

    private final ClaudeQuestionProperties claudeQuestionProperties;
    private final RestClient restClient;
    private final MemberRepository memberRepository;

    public ClaudeQuestionResponseDto createQuestion(
            ClaudeQuestionRequestDto claudeQuestionRequestDto,
            PrincipalDetails principalDetails
    ) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        // 사용자 메시지 생성
        ClaudeMessage userMessage = ClaudeMessage.builder()
                .role("user")
                .content(claudeQuestionRequestDto.content())
                .build();

        // Claude API 요청 데이터 준비
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", claudeQuestionProperties.getModel());
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0);
        parameters.put("system", "너는 AI에 최적화된 전문가야");
        parameters.put("messages", List.of(userMessage));

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

            // 성공 로깅
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
}
