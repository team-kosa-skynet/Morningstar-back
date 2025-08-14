package com.gaebang.backend.domain.interviewTurn.dto.request;

import com.gaebang.backend.domain.interviewTurn.enums.JobRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StartSessionRequestDto(
        @NotBlank String displayName,
        @NotNull JobRole jobRole,               // FULLSTACK / FRONTEND / BACKEND
        UUID documentId                         // 업로드된 문서 ID (선택값)
) {

}
