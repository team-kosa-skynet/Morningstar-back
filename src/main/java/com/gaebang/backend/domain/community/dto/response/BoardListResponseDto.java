package com.gaebang.backend.domain.community.dto.response;

import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.member.dto.response.TestUserResponseDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.util.DataFormatter;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Builder
public record BoardListResponseDto(
        Long boardId,               // 게시글 ID
        String title,               // 제목
        Long commentCount,          // 댓글 수
        String writer,              // 작성자(글쓴이)
        int writerLevel,            // 작성자 레벨
        String imageUrl,            // 이미지 URL
        String createdDate,         // 작성일
        Long viewCount,             // 조회수
        Long likeCount              // 추천수
) {
    /*public static BoardListResponseDto fromEntity(Board board) {
        return new BoardListResponseDto(
                board.getId(),
                board.getTitle(),
                board.getComments().size(),
                board.getMember().getMemberBase().getNickname(),
                board.getImages().get(0).getImageUrl(),
                DataFormatter.getFormattedCreatedAt(board.getCreatedAt()),
                0, //TODO 테이블에 컬럼 추가
                board.getBoardLikes().size()
        );
    }*/
}
