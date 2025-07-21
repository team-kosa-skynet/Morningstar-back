package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;

public record SignUpResponseDto(

        Long memberId,
        String email,
        String name

) {

    public static SignUpResponseDto fromEntity(Member member) {
        return new SignUpResponseDto(
                member.getId(),
                member.getMemberBase().getEmail(),
                member.getMemberBase().getNickname()
        );
    }
}
