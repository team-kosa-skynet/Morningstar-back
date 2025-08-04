package com.gaebang.backend.domain.question.claude.dto.request;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ClaudeMessage {
    private String role;
    private String content;
}
