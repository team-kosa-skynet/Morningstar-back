package com.gaebang.backend.domain.question.openai.dto.request;

public record OpenaiQuestionRequestDto(
        String input,
        String previousResponseId
) {

}
