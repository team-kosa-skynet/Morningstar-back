package com.gaebang.backend.domain.member.dto.request;

public record PutMemberInfoRequestDto(

        String profileImage,
        String profileMessage,
        String nickname
) {
}
