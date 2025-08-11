package com.gaebang.backend.domain.question.gemini.dto.request;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Builder
@Getter
public class GeminiMessage {
    private List<Map<String, String>> parts;
}