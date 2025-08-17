package com.gaebang.backend.domain.interview.dto.response;

import com.gaebang.backend.domain.interview.enums.InterviewStage;

public record TurnResponseDto(
        int nextQuestionNo,
        InterviewStage nextStage,
        boolean done,            // true면 세션 종료 지시
        String instructions      // 모델에 바로 넣을 지시문(= session.update → response.create 에 사용)
) {
}
