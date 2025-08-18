package com.gaebang.backend.domain.interview.dto.internal;

import java.util.List;

public record PlanQuestionDto(
        int idx,          // 0-based 권장
        String type,      // BEHAVIORAL / SYSTEM_DESIGN / ...
        String text,      // 질문 문장
        String intent,    // 🆕 질문 의도
        List<String> guides  // 🆕 답변 가이드 (3개)
) {
}
