package com.gaebang.backend.domain.question.gemini.controller;

import com.gaebang.backend.domain.question.gemini.dto.request.GeminiQuestionRequestDto;
import com.gaebang.backend.domain.question.gemini.dto.request.ImageGenerateRequestDto;
import com.gaebang.backend.domain.question.gemini.service.GeminiQuestionService;
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
public class GeminiQuestionController {

    private final GeminiQuestionService geminiQuestionService;

    /**
     * 파일 포함 (Multipart 요청)
     */
    @PostMapping(value = "/{conversationId}/gemini/stream",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionWithFiles(
            @PathVariable Long conversationId,
            @ModelAttribute @Valid GeminiQuestionRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return geminiQuestionService.createQuestionStream(
                conversationId,
                requestDto,
                principalDetails
        );
    }

    /**
     * Gemini 이미지 생성 전용 엔드포인트
     */
    @PostMapping(value = "/{conversationId}/gemini/generate-image",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateImage(
            @PathVariable Long conversationId,
            @RequestBody @Valid ImageGenerateRequestDto request,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return geminiQuestionService.generateImageInConversation(
                conversationId,
                request.prompt(),
                request.model(),
                principalDetails
        );
    }

}
