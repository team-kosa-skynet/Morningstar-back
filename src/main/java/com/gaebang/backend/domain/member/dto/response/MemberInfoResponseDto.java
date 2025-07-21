package com.gaebang.backend.domain.member.dto.response;


import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.util.DataFormatter;

public record MemberInfoResponseDto(

        String email,
        String nickname,
        String profileImage,
        String profileMessage,
        String createdAt,
        Long userId
) {

    public static MemberInfoResponseDto fromEntity(Member member, int likeCount, boolean hasUnreadNotifications) {
        return new MemberInfoResponseDto(
                member.getMemberBase().getEmail(),
                member.getMemberBase().getNickname(),
                member.getProfileImage(),
                member.getProfileMessage(),
                DataFormatter.getFormattedCreatedAt(member.getCreatedAt()),
                member.getId()
        );
    }
}
