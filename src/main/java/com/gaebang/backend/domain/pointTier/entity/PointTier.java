package com.gaebang.backend.domain.pointTier.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "point_tier")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long tierId;

    // 언랭, 아이언 ...
    @Column(nullable = false, unique = true)
    private TierType tierType;

    // 최소 포인트
    @Column(nullable = false)
    private Integer minPoint;
    // 최대 포인트 (챌린저는 null)
    @Column
    private Integer maxPoint;

    // 등급 순서 (정렬용)
    @Column(nullable = false)
    private Integer tierOrder;
}