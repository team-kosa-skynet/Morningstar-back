package com.gaebang.backend.domain.interview.dto;

import java.util.List;

public record IceServer(
        List<String> urls, String username, String credential
) {
}
