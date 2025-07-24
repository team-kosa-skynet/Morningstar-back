package com.gaebang.backend.domain.community.dto.reqeust;

import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.member.entity.Member;

import java.util.List;

public record BoardCreateAndEditRequestDto(
        String title,
        String content,
        String category,
        List<String> imageUrl
) {

    public static Board toEntity(Member member, BoardCreateAndEditRequestDto requestDto) {
        return Board.builder()
                .member(member)
                .title(requestDto.title())
                .content(requestDto.content())
                .category(requestDto.category())
                .build();
    }
}
