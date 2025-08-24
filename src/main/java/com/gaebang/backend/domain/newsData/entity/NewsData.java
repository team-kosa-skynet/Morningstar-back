package com.gaebang.backend.domain.newsData.entity;


import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "news")
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NewsData extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long newsId;

    @Column(name = "title")
    private String title;

    @Column(name = "original_link")
    private String originalLink;

    @Column(name = "link")
    private String link;

    @Column(name = "description" , columnDefinition = "TEXT")
    private String description;

    @Column(name = "pub_date")
    private LocalDateTime pubDate;

    @Column(name = "is_popular", columnDefinition = "TINYINT DEFAULT 0")
    @Builder.Default
    private Integer isPopular = 0;

    @Column(name = "is_active", columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default
    private Integer isActive = 1;

    @Column(name = "image_url")
    private String imageUrl;

    // API 응답 문자열을 LocalDateTime으로 변환하는 메서드
    public void setPubDateFromString(String pubDateStr) {
        // "Mon, 07 Jul 2025 11:00:00 +0900" 형식 파싱
        DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        ZonedDateTime zonedDateTime = ZonedDateTime.parse(pubDateStr, formatter);

        // 한국 시간대로 변환 후 LocalDateTime으로 변환
        this.pubDate = zonedDateTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"))
                .toLocalDateTime();
    }

}
