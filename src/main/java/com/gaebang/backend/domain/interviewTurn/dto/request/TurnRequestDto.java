package com.gaebang.backend.domain.interviewTurn.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TurnRequestDto(
        UUID sessionId,
        int questionIndex,
        String transcript
) {
}
