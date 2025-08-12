package com.gaebang.backend.domain.interviewTurn.dto.response;

import java.util.List;
import java.util.Map;

public record FinalizeReportResponseDto(
        double overallScore,                     // 0.0 ~ 5.0
        Map<String, Double> subscores,           // clarity, tech_depth, ...
        List<String> strengths,                  // 상위 2개
        List<String> areasToImprove,             // 하위 2개
        List<String> recommendedNextSteps        // 연습 제안
) {
}
