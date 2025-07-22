package com.gaebang.backend.domain.point.dto.response;

import com.project.stock.investory.point.entity.PointType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// 전체 포인트 내역을 조회하기 위한 responseDto
public class PointResponseDto {

    private Long pointId;
    private Long userId;
    private Integer amount;
    private PointType type;
    private Integer depositSum;
    private Integer withdrawSum;
    private LocalDateTime date;

}
