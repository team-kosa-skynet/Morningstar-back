package com.gaebang.backend.domain.newsData.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gaebang.backend.domain.newsData.entity.NewsData;

import java.time.LocalDateTime;

public record NewsDataResponseDTO (
    Long newsId,
    String title,
    String originalLink,
    String link,
    String description,
    Integer isPopular,
    Integer isActive,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    LocalDateTime pubDate
) {

    public static NewsDataResponseDTO fromEntity(NewsData newsData) {
        return new NewsDataResponseDTO(
            newsData.getNewsId(),
            newsData.getTitle(),
            newsData.getOriginalLink(),
            newsData.getLink(),
            newsData.getDescription(),
            newsData.getIsPopular(),
            newsData.getIsActive(),
            newsData.getPubDate()
        );
    }

}
