package com.gaebang.backend.domain.interviewTurn.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TtsRequestDto(
        @NotBlank @Size(max = 600) String text,
        String voice,   // 예: "alloy"
        String format   // 예: "mp3"
) {
}
