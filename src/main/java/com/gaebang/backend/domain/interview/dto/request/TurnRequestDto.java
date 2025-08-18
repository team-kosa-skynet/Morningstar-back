package com.gaebang.backend.domain.interview.dto.request;

import java.util.UUID;

public record TurnRequestDto(
        UUID sessionId,
        int questionIndex,
        String transcript
) {
}
