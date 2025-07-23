package com.gaebang.backend.domain.point.dto.response;

import com.gaebang.backend.domain.point.entity.Point;
import com.gaebang.backend.domain.point.entity.PointType;
import com.gaebang.backend.global.util.DataFormatter;

import java.time.LocalDateTime;

// 전체 포인트 내역을 조회하기 위한 responseDto
public record PointResponseDto (
        Long pointId,
        Long memberId,
        Integer amount,
        PointType type,
        Integer depositSum,
        Integer withdrawSum,
//        LocalDateTime data 로 했을 경우 response 가 [ ... ] 이렇게 배열형식으로 넘어온다
        String date
) {

    public static PointResponseDto fromEntity(Point point) {
        return new PointResponseDto(
                point.getPointId(),
                point.getMember().getId(),
                point.getAmount(),
                point.getType(),
                point.getDepositSum(),
                point.getWithdrawSum(),
//              point.getDate()

                // 이렇게 DataFormatter를 사용하면 "date": "2025.07.23" 이러한 형식으로 넘어온다
                // 위의 출력값은 DataFormatter.getFormattedCreatedAt() 으로 설정했을 때
                DataFormatter.getFormattedCreatedAtWithTime(point.getDate())
        );
    }

}
