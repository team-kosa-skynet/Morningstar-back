package com.gaebang.backend.domain.interview.client.response;

import java.util.List;

public record OpenAiIceServerDto(
        List<String> urls,
        String username,
        String credential
) {
}
