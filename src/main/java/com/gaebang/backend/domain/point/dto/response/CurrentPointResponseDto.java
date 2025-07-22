package com.gaebang.backend.domain.point.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// 현재 포인트를 조회하기 위한 responseDto
public class CurrentPointResponseDto {

    private Long pointId;
    private Long userId;
    private Integer currentPoint;

}
