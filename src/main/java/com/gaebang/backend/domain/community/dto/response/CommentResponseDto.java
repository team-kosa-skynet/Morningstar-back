package com.gaebang.backend.domain.community.dto.response;

import com.gaebang.backend.domain.community.entity.Comment;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record CommentResponseDto(
        Long commentId,             // 댓글 ID
        String comment,             // 댓글 내용
        String writer,              // 작성자 닉네임
        LocalDateTime createdDate   // 작성일

) {
    public static CommentResponseDto fromEntity(Comment comment) {
        return CommentResponseDto.builder()
                .commentId(comment.getId())
                .comment(comment.getContent())
                .writer(comment.getMember().getMemberBase().getNickname())
                .createdDate(comment.getCreatedAt())
                .build();
    }

}
