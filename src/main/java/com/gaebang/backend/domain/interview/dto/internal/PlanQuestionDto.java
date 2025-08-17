package com.gaebang.backend.domain.interview.dto.internal;

public record PlanQuestionDto(
        int idx,          // 0-based 권장
        String type,      // BEHAVIORAL / SYSTEM_DESIGN / ...
        String text       // 질문 문장
) {
}
