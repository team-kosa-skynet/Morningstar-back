package com.gaebang.backend.domain.community.dto.reqeust;

import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Comment;
import com.gaebang.backend.domain.member.entity.Member;

public record CommentRequestDto(
        Long boardId,
        String content
) {
    public Comment toEntity(Member member, Board board) {
        return Comment.builder()
                .member(member)
                .board(board)
                .content(this.content())
                .build();
    }
}
