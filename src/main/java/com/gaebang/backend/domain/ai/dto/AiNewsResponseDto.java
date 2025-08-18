package com.gaebang.backend.domain.ai.dto;

import com.gaebang.backend.domain.ai.entity.AiNews;
import com.gaebang.backend.global.util.DataFormatter;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AiNewsResponseDto {
    private Long id;
    private String title;
    private String content;
    private String summary;
    private String createdAt;

    public static AiNewsResponseDto fromEntity(AiNews entity) {
        return AiNewsResponseDto.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .content(entity.getContent())
                .summary(entity.getSummary())
                .createdAt(DataFormatter.getFormattedCreatedAtWithTime(entity.getCreatedAt()))
                .build();
    }
}
