package com.gaebang.backend.domain.community.dto.reqeust;

import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.BoardReport;
import com.gaebang.backend.domain.member.entity.Member;

public record BoardReportRequestDto(
        Long boardId
) {

    public BoardReport toEntity(Member member, Board board) {
        return BoardReport.builder()
                .member(member)
                .board(board)
                .build();
    }
}
