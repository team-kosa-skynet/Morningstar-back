package com.gaebang.backend.domain.interviewTurn.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TurnRequestDto(
        @NotNull UUID sessionId,
        @Min(0) int questionIndex,
        @NotBlank String transcript // 클라 STT 결과
) {
}
