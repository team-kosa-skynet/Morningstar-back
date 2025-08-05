package com.gaebang.backend.domain.question.claude.controller;

import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.dto.response.ClaudeQuestionResponseDto;
import com.gaebang.backend.domain.question.claude.service.ClaudeQuestionService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/claude/question")
@RequiredArgsConstructor
public class ClaudeQuestionController {

    private final ClaudeQuestionService claudeQuestionService;

    @PostMapping
    public ResponseEntity<ResponseDTO<ClaudeQuestionResponseDto>> addQuestion(
            @RequestBody @Valid ClaudeQuestionRequestDto claudeQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        ClaudeQuestionResponseDto response = claudeQuestionService.createQuestion(claudeQuestionRequestDto, principalDetails);
        ResponseDTO<ClaudeQuestionResponseDto> responseDTO = ResponseDTO.okWithData(response);
        return ResponseEntity
                .status(responseDTO.getCode())
                .body(responseDTO);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestion(
            @RequestBody @Valid ClaudeQuestionRequestDto claudeQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return claudeQuestionService.createQuestionStream(claudeQuestionRequestDto, principalDetails);
    }
}
