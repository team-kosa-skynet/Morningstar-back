package com.gaebang.backend.domain.question.openai.controller;

import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.service.OpenaiQuestionService;
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
@RequestMapping("/api/openai/question")
@RequiredArgsConstructor
public class OpenaiQuestionController {

    private final OpenaiQuestionService openaiQuestionService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestion(
            @RequestBody @Valid OpenaiQuestionRequestDto openaiQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return openaiQuestionService.createQuestionStream(openaiQuestionRequestDto, principalDetails);
    }
}