package com.gaebang.backend.domain.question.openai.controller;

import com.gaebang.backend.domain.question.openai.dto.request.OpenaiQuestionRequestDto;
import com.gaebang.backend.domain.question.openai.dto.response.OpenaiQuestionResponseDto;
import com.gaebang.backend.domain.question.openai.service.OpenaiQuestionService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/openai/question")
@RequiredArgsConstructor
public class OpenaiQuestionController {

    private final OpenaiQuestionService openaiQuestionService;

    // 질문 생성
    @PostMapping
    public ResponseEntity<ResponseDTO<OpenaiQuestionResponseDto>> addQuestion(
            @RequestBody @Valid OpenaiQuestionRequestDto openaiQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        OpenaiQuestionResponseDto response = openaiQuestionService.createQuestion(openaiQuestionRequestDto, principalDetails);
        ResponseDTO<OpenaiQuestionResponseDto> responseDTO = ResponseDTO.okWithData(response);
        return ResponseEntity
                .status(responseDTO.getCode())
                .body(responseDTO);
    }

    @PostMapping("/new")
    public ResponseEntity<ResponseDTO<Void>> startNewConversation(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        openaiQuestionService.startNewConversation(principalDetails);
        ResponseDTO<Void> responseDTO = ResponseDTO.ok();
        return ResponseEntity
                .status(responseDTO.getCode())
                .body(responseDTO);
    }
}
