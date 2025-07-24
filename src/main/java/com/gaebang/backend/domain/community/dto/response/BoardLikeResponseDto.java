package com.gaebang.backend.domain.community.dto.response;

import lombok.Builder;

@Builder
public record BoardLikeResponseDto(
        boolean liked,  // 현재 사용자의 좋아요 상태
        Long likeCount, // 전체 좋아요 개수
        String message  // 메세지
) {
}
