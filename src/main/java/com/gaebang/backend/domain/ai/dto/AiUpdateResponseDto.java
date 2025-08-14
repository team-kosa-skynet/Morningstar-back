package com.gaebang.backend.domain.ai.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AiUpdateResponseDto {
    private Long id;
    private String source;       // OpenAI / GoogleAI / Claude
    private String title;        // 원문 제목
    private String url;          // 원문 URL
    private String rawContent;   // 수집한 원문(요약/본문 일부)
    private String article;      // Gemini 생성 기사
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}

