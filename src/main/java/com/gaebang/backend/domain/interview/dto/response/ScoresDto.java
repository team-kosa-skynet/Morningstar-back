package com.gaebang.backend.domain.interview.dto.response;

import java.util.Map;

public record ScoresDto(
        double overallScore,
        Map<String, Integer> subscores
) {
}
