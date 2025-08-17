package com.gaebang.backend.domain.interviewTurn.dto.internal;

import java.util.List;

public record InterviewPlanDto(
        List<PlanQuestionDto> questions
) {
}
