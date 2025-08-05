package com.gaebang.backend.domain.question.gemini.controller;

import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.service.GeminiQuestionService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/gemini/question")
@RequiredArgsConstructor
public class GeminiQuestionController {

    private final GeminiQuestionService geminiQuestionService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestion(
            @RequestBody @Valid GeminiQuestionRequestDto geminiQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return geminiQuestionService.createQuestionStream(geminiQuestionRequestDto, principalDetails);
    }
}