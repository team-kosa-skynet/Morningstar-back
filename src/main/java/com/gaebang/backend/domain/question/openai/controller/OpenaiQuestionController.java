package com.gaebang.backend.domain.question.openai.controller;

import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
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

    @PostMapping(value = "/{conversationId}/openai/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestion(
            @PathVariable Long conversationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam("content") String content,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        OpenaiQuestionRequestDto requestDto = new OpenaiQuestionRequestDto(content, files);

        return openaiQuestionService.createQuestionStream(
                conversationId,
                model,
                requestDto,
                principalDetails
        );
    }
}
