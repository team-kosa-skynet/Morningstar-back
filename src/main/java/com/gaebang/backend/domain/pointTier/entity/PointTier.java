package com.gaebang.backend.domain.pointTier.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
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
public class PointTier extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long tierId;

    // 수성, 금성
    @Enumerated(EnumType.STRING)
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