package com.gaebang.backend.domain.point.dto.response;

import com.gaebang.backend.domain.point.entity.Point;

// 현재 포인트를 조회하기 위한 responseDto
public record CurrentPointResponseDto (
        Long memberId,
        Integer currentPoint
) {

    public static CurrentPointResponseDto fromEntity(Point point, Integer remainingPoint) {
        return new CurrentPointResponseDto(
                point.getMember().getId(),
                remainingPoint
        );
    }
    // 포인트가 없는 신규 사용자 - 오버라이딩
    public static CurrentPointResponseDto fromEntity(Long memberId, Integer remainingPoint) {
        return new CurrentPointResponseDto(
                memberId,
                remainingPoint
        );
    }

}
