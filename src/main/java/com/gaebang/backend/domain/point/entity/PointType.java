package com.gaebang.backend.domain.point.entity;

public enum PointType {

    SPONSORSHIP("후원"),
    ATTENDANCE("출석"),
    BOARD("게시글"),
    COMMENT("댓글"),
    FEEDBACK("피드백");

    private final String description;  // 필드

    // 생성자 (enum에서는 항상 private)
    PointType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

}