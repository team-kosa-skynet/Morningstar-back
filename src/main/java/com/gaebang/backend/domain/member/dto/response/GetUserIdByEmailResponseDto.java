package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;

public record GetUserIdByEmailResponseDto(
       Long userId
) {

    public static GetUserIdByEmailResponseDto fromEntity(Member member) {
        return new GetUserIdByEmailResponseDto(
                member.getId()
        );
    }
}
