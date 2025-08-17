package com.gaebang.backend.domain.interview.client.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OpenAiRealtimeSessionResponseDto(
        String id,
        @JsonProperty("client_secret") OpenAiClientSecretDto clientSecret,
        @JsonProperty("ice_servers") List<OpenAiIceServerDto> iceServers
) {
}
