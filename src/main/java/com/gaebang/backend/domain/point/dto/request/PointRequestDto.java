package com.gaebang.backend.domain.point.dto.request;

import com.project.stock.investory.point.entity.PointType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointRequestDto {

    private Integer amount;
    private PointType type;

}