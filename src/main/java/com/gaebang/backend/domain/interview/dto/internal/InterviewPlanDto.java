package com.gaebang.backend.domain.interview.dto.internal;

import java.util.List;

public record InterviewPlanDto(
        List<PlanQuestionDto> questions
) {
}
