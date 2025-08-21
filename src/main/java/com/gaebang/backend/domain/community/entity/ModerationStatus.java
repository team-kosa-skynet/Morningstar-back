package com.gaebang.backend.domain.community.entity;

public enum ModerationStatus {
    PENDING,     // 검열 대기 중
    APPROVED,    // 검열 통과
    REJECTED     // 검열 차단됨 (내용 교체됨)
}