package com.gaebang.backend.domain.ai.dto;

import com.gaebang.backend.domain.ai.entity.AiUpdate;
import com.gaebang.backend.global.util.DataFormatter;

public record AIUpdateNewsResponseDto(
        Long id,
        String title,
        String content,
        String imageUrl,
        String createdAt
) {
    public static AIUpdateNewsResponseDto fromEntity(AiUpdate aiUpdate) {
        return new AIUpdateNewsResponseDto(
                aiUpdate.getId(),
                aiUpdate.getTitle(),
                aiUpdate.getContent(),
                aiUpdate.getImageUrl(),
                DataFormatter.getFormattedCreatedAt(aiUpdate.getUpdatedAt())
        );
    }
}


