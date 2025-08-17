package com.gaebang.backend.domain.interview.client.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record OpenAiClientSecretDto(
        String value,
        @JsonProperty("expires_at") OffsetDateTime expiresAt
) {
}
