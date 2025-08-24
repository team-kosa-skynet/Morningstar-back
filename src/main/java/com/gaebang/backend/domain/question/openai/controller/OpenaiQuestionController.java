package com.gaebang.backend.domain.question.openai.controller;

import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.dto.request.OpenaiImageGenerateRequestDto;
import com.gaebang.backend.domain.question.openai.service.OpenaiQuestionService;
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
public class OpenaiQuestionController {

    private final OpenaiQuestionService openaiQuestionService;

    /**
     * 텍스트만 (JSON 요청)
     */
    @PostMapping(value = "/{conversationId}/openai/stream",
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionText(
            @PathVariable Long conversationId,
            @RequestBody @Valid OpenaiQuestionRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return openaiQuestionService.createQuestionStream(
                conversationId,
                requestDto,
                principalDetails
        );
    }

    /**
     * 파일 포함 (Multipart 요청)
     */
    @PostMapping(value = "/{conversationId}/openai/stream",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestionWithFiles(
            @PathVariable Long conversationId,
            @ModelAttribute @Valid OpenaiQuestionRequestDto requestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return openaiQuestionService.createQuestionStream(
                conversationId,
                requestDto,
                principalDetails
        );
    }

    /**
     * OpenAI DALL-E 3 이미지 생성 전용 엔드포인트
     */
    @PostMapping(value = "/{conversationId}/openai/generate-image",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateImage(
            @PathVariable Long conversationId,
            @RequestBody @Valid OpenaiImageGenerateRequestDto request,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        return openaiQuestionService.generateImageInConversation(
                conversationId,
                request.prompt(),
                principalDetails
        );
    }
}