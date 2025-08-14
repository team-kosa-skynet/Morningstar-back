package com.gaebang.backend.domain.ai.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_news")
@Builder
public class AiNews extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) // 제목은 필수
    private String title;

    @Column(columnDefinition = "TEXT") // 기사 요약(네이버 제공)
    private String content;

    @Column(columnDefinition = "TEXT") // AI 요약 (선택)
    private String summary;

}

