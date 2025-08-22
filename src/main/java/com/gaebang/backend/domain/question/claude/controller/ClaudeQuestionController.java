package com.gaebang.backend.domain.question.claude.controller;

import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.service.ClaudeQuestionService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ClaudeQuestionController {

    private final ClaudeQuestionService claudeQuestionService;

    /**
     * 텍스트만 (JSON 요청)
     */
    @PostMapping(value = "/{conversationId}/claude/stream",
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionText(
            @PathVariable Long conversationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestBody @Valid ClaudeQuestionRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return claudeQuestionService.createQuestionStream(
                conversationId,
                model,
                requestDto,
                principalDetails
        );
    }

    /**
     * 파일 포함 (Multipart 요청)
     */
    @PostMapping(value = "/{conversationId}/claude/stream",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionWithFiles(
            @PathVariable Long conversationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam("content") String content,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        ClaudeQuestionRequestDto requestDto = new ClaudeQuestionRequestDto(content, files);

        return claudeQuestionService.createQuestionStream(
                conversationId,
                model,
                requestDto,
                principalDetails
        );
    }
}