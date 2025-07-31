package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;

public record GetUserResponseDto(
        String email,
        String nickname,
        int point,
        int level
) {

    public static GetUserResponseDto fromEntity(Member member,int level) {
        return new GetUserResponseDto(
                member.getMemberBase().getEmail(),
                member.getMemberBase().getNickname(),
                member.getPoints(),
                level
        );
    }
}
