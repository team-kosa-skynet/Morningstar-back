package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.reqeust.CommentRequestDto;
import com.gaebang.backend.domain.community.dto.response.CommentResponseDto;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Comment;
import com.gaebang.backend.domain.community.exception.BoardNotFoundException;
import com.gaebang.backend.domain.community.exception.CommentNotFoundException;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.community.repository.CommentRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.entity.PointType;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiredArgsConstructor
@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final BoardRepository boardRepository;
    private final PointService pointService;

    // 게시판에 엮인 댓글 조회
    public Page<CommentResponseDto> getCommentsByBoardId(Long boardId, Pageable pageable, PrincipalDetails principalDetails) {
        Board findBoard = boardRepository.findById(boardId)
                .orElseThrow(() -> new BoardNotFoundException());

        return commentRepository.findByBoardIdAndDeleteYnOrderByCreatedAtDesc(findBoard.getId(), "N", pageable)
                .map(CommentResponseDto::fromEntity);
    }

    // 댓글 수정
    public void editComment(Long commentId, CommentRequestDto commentRequestDto, PrincipalDetails principalDetails) {
        Long findMemberId = principalDetails.getMember().getId();
        
        Comment editComment = commentRepository.findByIdAndMemberIdAndDeleteYn(commentId, findMemberId, "N")
                .orElseThrow(() -> new CommentNotFoundException());

        editComment.update(commentRequestDto.content());
    }

    // 댓글 생성
    public void createComment(CommentRequestDto commentRequestDto, PrincipalDetails principalDetails) {
        Member loginMember = principalDetails.getMember();
        Board findBoard = boardRepository.findById(commentRequestDto.boardId())
                .orElseThrow(() -> new BoardNotFoundException());

        Comment createComment = commentRequestDto.toEntity(loginMember, findBoard);

        commentRepository.save(createComment);

        PointRequestDto pointRequestDto = PointRequestDto.builder()
                .type(PointType.COMMENT)
                .amount(5)
                .build();
        pointService.createPoint(pointRequestDto, principalDetails);
    }

    // 댓글 삭제
    public void deleteComment(Long commentId, PrincipalDetails principalDetails) {
        Long findMemberId = principalDetails.getMember().getId();

        Comment findComment = commentRepository.findByIdAndMemberIdAndDeleteYn(commentId, findMemberId, "N")
                .orElseThrow(CommentNotFoundException::new);

        findComment.softDelete();
    }


}
