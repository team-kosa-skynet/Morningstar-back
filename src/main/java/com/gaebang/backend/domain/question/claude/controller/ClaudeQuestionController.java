package com.gaebang.backend.domain.question.claude.controller;

import com.gaebang.backend.domain.question.claude.dto.request.ClaudeQuestionRequestDto;
import com.gaebang.backend.domain.question.claude.dto.response.ClaudeQuestionResponseDto;
import com.gaebang.backend.domain.question.claude.service.ClaudeQuestionService;
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
@RequestMapping("/api/claude/question")
@RequiredArgsConstructor
public class ClaudeQuestionController {

    private final ClaudeQuestionService claudeQuestionService;

    @PostMapping
    public ResponseEntity<ResponseDTO<ClaudeQuestionResponseDto>> addQeustion(
            @RequestBody @Valid ClaudeQuestionRequestDto claudeQuestionRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        ClaudeQuestionResponseDto response = claudeQuestionService.createQuestion(claudeQuestionRequestDto, principalDetails);
        ResponseDTO<ClaudeQuestionResponseDto> responseDTO = ResponseDTO.okWithData(response);
        return ResponseEntity
                .status(responseDTO.getCode())
                .body(responseDTO);
    }

}
