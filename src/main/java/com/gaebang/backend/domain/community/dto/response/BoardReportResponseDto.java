package com.gaebang.backend.domain.community.dto.response;

import com.gaebang.backend.domain.community.entity.BoardReport;
import lombok.Builder;

@Builder
public record BoardReportResponseDto(
        Long boardReportId,
        Long boardId,
        Long memberId
) {

    public static BoardReportResponseDto fromEntity(BoardReport boardReport) {
        return BoardReportResponseDto.builder()
                .boardReportId(boardReport.getId())
                .boardId(boardReport.getBoard().getId())
                .memberId(boardReport.getMember().getId())
                .build();
    }

}
