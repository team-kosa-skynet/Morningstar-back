package com.gaebang.backend.domain.interview.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FinalizeReportRequestDto(
        @NotNull UUID sessionId
) {
}
