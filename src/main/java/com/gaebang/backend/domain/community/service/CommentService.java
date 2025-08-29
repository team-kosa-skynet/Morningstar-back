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
import com.gaebang.backend.domain.member.service.MemberService;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.entity.PointType;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.domain.community.event.CommentCreatedEvent;
import com.gaebang.backend.domain.community.event.CommentUpdatedEvent;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final MemberRepository memberRepository;
    private final PointService pointService;
    private final MemberService memberService;
    private final ModerationService moderationService;
    private final ApplicationEventPublisher eventPublisher;

    // 게시판에 엮인 댓글 조회
    public Page<CommentResponseDto> getCommentsByBoardId(Long boardId, Pageable pageable, PrincipalDetails principalDetails) {
        Board findBoard = boardRepository.findById(boardId)
                .orElseThrow(BoardNotFoundException::new);

        return commentRepository.findByBoardIdAndDeleteYnOrderByCreatedAtDesc(findBoard.getId(), "N", pageable)
                .map(comment -> CommentResponseDto.fromEntity(comment,
                        memberService.getMemberTierOrder(comment.getMember())));
    }

    // 댓글 수정
    public void editComment(Long commentId, CommentRequestDto commentRequestDto, PrincipalDetails principalDetails) {
        Long findMemberId = principalDetails.getMember().getId();

        Comment editComment = commentRepository.findByIdAndMemberIdAndDeleteYn(commentId, findMemberId, "N")
                .orElseThrow(CommentNotFoundException::new);

        editComment.update(commentRequestDto.content());
        
        // 트랜잭션 커밋 후 검열을 위한 이벤트 발행
        eventPublisher.publishEvent(new CommentUpdatedEvent(commentId));
    }

    // 댓글 생성
    public void createComment(CommentRequestDto commentRequestDto, PrincipalDetails principalDetails) {
        Member loginMember = principalDetails.getMember();
        Board findBoard = boardRepository.findById(commentRequestDto.boardId())
                .orElseThrow(BoardNotFoundException::new);

        Comment createComment = commentRequestDto.toEntity(loginMember, findBoard);

        Comment savedComment = commentRepository.save(createComment);

        PointRequestDto pointRequestDto = PointRequestDto.builder()
                .type(PointType.COMMENT)
                .amount(5)
                .build();
        pointService.createPoint(pointRequestDto, principalDetails);
        
        // 트랜잭션 커밋 후 검열을 위한 이벤트 발행
        eventPublisher.publishEvent(new CommentCreatedEvent(savedComment.getId()));
    }

    // 댓글 삭제
    public void deleteComment(Long commentId, PrincipalDetails principalDetails) {
        Long findMemberId = principalDetails.getMember().getId();

        Comment findComment = commentRepository.findByIdAndMemberIdAndDeleteYn(commentId, findMemberId, "N")
                .orElseThrow(CommentNotFoundException::new);

        findComment.softDelete();
    }

    /**
     * AI 봇 전용 댓글 생성
     * @param boardId 게시글 ID
     * @param content AI 답변 내용
     * @param aiProvider AI 제공자 이름
     * @param confidence 답변 신뢰도
     * @return 생성된 댓글
     */
    public Comment createAiComment(Long boardId, String content, String aiProvider, Double confidence) {
        Board findBoard = boardRepository.findById(boardId)
                .orElseThrow(BoardNotFoundException::new);

        // AI 어시스턴트 전용 계정 조회 (Member ID = 999)
        Member aiMember = memberRepository.findById(999L)
                .orElseThrow(() -> new RuntimeException("AI 어시스턴트 계정이 존재하지 않습니다 (ID: 999)"));

        // AI 답변 내용 (기술 정보 제거하여 자연스럽게)
        String finalContent = content;

        Comment aiComment = Comment.builder()
                .member(aiMember)
                .board(findBoard)
                .content(finalContent)
                .build();

        Comment savedComment = commentRepository.save(aiComment);

        // AI 댓글은 검열하지 않음 (이미 생성 단계에서 검열 완료)
        // 포인트도 지급하지 않음 (AI 봇이므로)

        return savedComment;
    }


}
