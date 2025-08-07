package com.gaebang.backend.domain.newsData.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NewsDataResponseDTO {

    private Long newsId;
    private String title;
    private String originalLink;
    private String link;
    private String description;
    private LocalDateTime pubDate;

}
