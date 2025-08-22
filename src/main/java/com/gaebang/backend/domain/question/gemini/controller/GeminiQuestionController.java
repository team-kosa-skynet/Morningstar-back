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
     * 텍스트만 (JSON 요청)
     */
    @PostMapping(value = "/{conversationId}/gemini/stream",
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionText(
            @PathVariable Long conversationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestBody @Valid GeminiQuestionRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return geminiQuestionService.createQuestionStream(
                conversationId,
                model,
                requestDto,
                principalDetails
        );
    }

    /**
     * 파일 포함 (Multipart 요청)
     */
    @PostMapping(value = "/{conversationId}/gemini/stream",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionWithFiles(
            @PathVariable Long conversationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam("content") String content,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        GeminiQuestionRequestDto requestDto = new GeminiQuestionRequestDto(content, files);

        return geminiQuestionService.createQuestionStream(
                conversationId,
                model,
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
                principalDetails
        );
    }

}
