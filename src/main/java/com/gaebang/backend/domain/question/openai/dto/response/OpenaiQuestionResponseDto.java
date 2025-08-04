package com.gaebang.backend.domain.question.openai.dto.response;

import com.gaebang.backend.domain.question.openai.entity.QuestionSession;

public record OpenaiQuestionResponseDto(
        String responseId,
        String content
) {

    public static OpenaiQuestionResponseDto fromEntity(String responseId, String content) {
        return new OpenaiQuestionResponseDto(
                responseId,
                content

        );
    }

}
