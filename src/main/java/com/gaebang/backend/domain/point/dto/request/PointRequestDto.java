package com.gaebang.backend.domain.point.dto.request;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.point.entity.Point;
import com.gaebang.backend.domain.point.entity.PointType;

import java.time.LocalDateTime;

public record PointRequestDto (
        Integer amount,
        PointType type
) {

    public Point toEntity(Member member, Integer newDepositSum, Integer newWithdrawSum, Integer nextVersion) {
        return Point.builder()
                .member(member)
                .amount(amount)
                .type(type)
                .depositSum(newDepositSum)
                .withdrawSum(newWithdrawSum)
                .date(LocalDateTime.now())
                .version(nextVersion)
                .build();
    }

}