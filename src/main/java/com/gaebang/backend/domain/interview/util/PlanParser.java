package com.gaebang.backend.domain.interview.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interview.dto.internal.InterviewPlanDto;
import com.gaebang.backend.domain.interview.dto.internal.PlanQuestionDto;
import org.springframework.stereotype.Component;

@Component
public class PlanParser {

    private final ObjectMapper om;
    public PlanParser(ObjectMapper om) { this.om = om; }

    public InterviewPlanDto parse(String planJson) throws Exception {
        return om.readValue(planJson, InterviewPlanDto.class);
    }

    public PlanQuestionDto getQuestionByIndex(String planJson, int questionIndex) throws Exception {
        InterviewPlanDto plan = parse(planJson);
        if (questionIndex < 0 || questionIndex >= plan.questions().size()) {
            throw new IllegalArgumentException("questionIndex out of range: " + questionIndex);
        }
        return plan.questions().get(questionIndex); // 0-based
    }



}
