package com.gaebang.backend.domain.community.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record BoardListProjectionDto(
        Long boardId,              // 게시글 ID
        String title,              // 제목
        Long commentCount,         // 댓글 수 (COUNT 결과는 Long)
        String writer,             // 작성자(글쓴이)
        String imageUrl,           // 이미지 URL
        LocalDateTime createdDate, // 작성일 (DB 타입)
        Long viewCount,            // 조회수
        Integer writerPoint,       // 작성자 레벨
        Long likeCount             // 추천수 (COUNT 결과는 Long)
) {
}