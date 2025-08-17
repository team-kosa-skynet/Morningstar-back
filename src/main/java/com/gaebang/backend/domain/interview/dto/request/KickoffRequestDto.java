package com.gaebang.backend.domain.interview.dto.request;

import java.util.UUID;

public record KickoffRequestDto(
        UUID sessionId,
        String jobPosition,     // 옵션
        String oaiSessionId     // 옵션(지금은 저장 안 해도 OK)
) {
}
