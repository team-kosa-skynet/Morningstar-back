package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.util.DataFormatter;

public record MemberInfoResponseDto(

        String email,
        String nickname,
        String createdAt,
        Long userId
) {

    public static MemberInfoResponseDto fromEntity(Member member, int likeCount, boolean hasUnreadNotifications) {
        return new MemberInfoResponseDto(
                member.getMemberBase().getEmail(),
                member.getMemberBase().getNickname(),
                DataFormatter.getFormattedCreatedAt(member.getCreatedAt()),
                member.getId()
        );
    }
}
