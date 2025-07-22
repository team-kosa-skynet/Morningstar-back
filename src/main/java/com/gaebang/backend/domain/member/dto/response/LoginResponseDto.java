package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;

public record LoginResponseDto(
        String email,
        String name,
        String token,
        Long userId
) {

    public static LoginResponseDto fromEntity(Member member, String token) {
        return new LoginResponseDto(
                member.getMemberBase().getEmail(),
                member.getMemberBase().getNickname(),
                token,
                member.getId()
        );
    }
}
