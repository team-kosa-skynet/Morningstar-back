package com.gaebang.backend.domain.community.dto.response;

import com.gaebang.backend.domain.community.entity.Board;
import lombok.Builder;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record BoardDetailResponseDto(
        Long boardId,                       // 게시글 ID
        String title,                       // 제목
        Long commentCount,                  // 댓글 수
        List<String> imageUrl,              // 이미지 URL
        String content,                     // 본문
        String writer,                      // 작성자(글쓴이)
        int writerLevel,                    // 작성자 레벨
        String createdDate,                 // 작성일
        Long viewCount,                     // 조회수
        Long likeCount,                     // 추천수
        Page<CommentResponseDto> comments   // 댓글 목록(페이징)
) {

    public static BoardDetailResponseDto fromEntity(Board board, String displayTime, int writerLevel, Long commentCount, Long likeCount, Page<CommentResponseDto> comments) {
        return BoardDetailResponseDto.builder()
                .boardId(board.getId())
                .title(board.getTitle())
                .commentCount(commentCount)
                .writer(board.getMember().getMemberBase().getNickname())
                .writerLevel(writerLevel)
                .imageUrl(board.getImages().stream().map(img -> img.getImageUrl()).toList())
                .createdDate(displayTime)
                .viewCount(board.getViewCount())
                .likeCount(likeCount)
                .content(board.getContent())
                .comments(comments)
                .build();
    }

}
