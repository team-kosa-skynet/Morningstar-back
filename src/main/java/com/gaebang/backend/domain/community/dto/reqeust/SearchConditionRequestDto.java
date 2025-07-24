package com.gaebang.backend.domain.community.dto.reqeust;

public record SearchConditionRequestDto(
        String title,
        String writer,
        String content
) {
}
