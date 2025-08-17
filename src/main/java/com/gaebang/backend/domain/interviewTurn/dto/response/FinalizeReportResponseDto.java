package com.gaebang.backend.domain.interviewTurn.dto.response;

import java.util.List;
import java.util.Map;

public record FinalizeReportResponseDto(
        double overallScore,
        Map<String, Integer> subscores,
        String strengths,
        String areasToImprove,
        String nextSteps
) {}
