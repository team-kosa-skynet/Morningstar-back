package com.gaebang.backend.domain.interviewTurn.dto.response;

import java.util.UUID;

public record StartSessionResponseDto(
        UUID sessionId,
        String greeting,
        String firstQuestion
) {
}
