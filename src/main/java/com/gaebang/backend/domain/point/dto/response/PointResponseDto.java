package com.gaebang.backend.domain.point.dto.response;

import com.gaebang.backend.domain.point.entity.Point;
import com.gaebang.backend.domain.point.entity.PointType;
import java.time.LocalDateTime;

// 전체 포인트 내역을 조회하기 위한 responseDto
public record PointResponseDto (
        Long pointId,
        Long memberId,
        Integer amount,
        PointType type,
        Integer depositSum,
        Integer withdrawSum,
        LocalDateTime date
) {

    public static PointResponseDto fromEntity(Point point) {
        return new PointResponseDto(
                point.getPointId(),
                point.getMember().getId(),
                point.getAmount(),
                point.getType(),
                point.getDepositSum(),
                point.getWithdrawSum(),
                point.getDate()
        );
    }

}
