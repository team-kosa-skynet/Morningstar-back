package com.gaebang.backend.domain.question.claude.controller;

import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.service.ClaudeQuestionService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ClaudeQuestionController {

    private final ClaudeQuestionService claudeQuestionService;

    /**
     * 특정 대화방에서 Claude에게 질문하고 스트리밍 답변을 받습니다
     * 이전 대화 맥락이 포함되어 연속적인 대화가 가능합니다
     *
     * @param conversationId 대화방 ID
     * @param model 사용할 Claude 모델 (선택사항, 없으면 기본값 사용)
     * @param claudeQuestionRequestDto 질문 내용
     * @param principalDetails 인증된 사용자 정보
     * @return SSE 스트리밍 응답
     */
    @PostMapping(value = "/{conversationId}/claude/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestion(
            @PathVariable Long conversationId,
            @RequestParam(value = "model", required = false) String model, // 쿼리 파라미터로 모델 받기
            @RequestBody @Valid ClaudeQuestionRequestDto claudeQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return claudeQuestionService.createQuestionStream(
                conversationId,
                model, // 쿼리 파라미터로 받은 모델 전달
                claudeQuestionRequestDto,
                principalDetails
        );
    }
}
