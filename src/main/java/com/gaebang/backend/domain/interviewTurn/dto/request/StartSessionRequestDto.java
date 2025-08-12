package com.gaebang.backend.domain.interviewTurn.dto.request;

import jakarta.validation.constraints.NotBlank;

public record StartSessionRequestDto(
        @NotBlank String displayName,
        @NotBlank String role,                  // BACKEND / FRONTEND / UNKNOWN
        String profileSnapshotJson             // 선택값
) {

}
