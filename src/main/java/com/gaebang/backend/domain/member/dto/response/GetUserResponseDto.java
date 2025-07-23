package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;

public record GetUserResponseDto(
        String email,
        String nickname,
        int point
) {

    public static GetUserResponseDto fromEntity(Member member) {
        return new GetUserResponseDto(
                member.getMemberBase().getEmail(),
                member.getMemberBase().getNickname(),
                member.getPoints()
        );
    }
}
