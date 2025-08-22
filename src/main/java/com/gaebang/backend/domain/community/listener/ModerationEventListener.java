package com.gaebang.backend.domain.community.listener;

import com.gaebang.backend.domain.community.event.BoardCreatedEvent;
import com.gaebang.backend.domain.community.event.BoardUpdatedEvent;
import com.gaebang.backend.domain.community.event.CommentCreatedEvent;
import com.gaebang.backend.domain.community.event.CommentUpdatedEvent;
import com.gaebang.backend.domain.community.service.ModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 검열 시스템 이벤트 리스너
 * 트랜잭션 커밋 후 검열을 수행하여 데이터 가시성 문제를 해결
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationEventListener {
    
    private final ModerationService moderationService;
    
    /**
     * 게시글 생성 후 검열 처리
     * 트랜잭션이 완전히 커밋된 후에 실행되어 "게시글을 찾을 수 없습니다" 오류 방지
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBoardCreated(BoardCreatedEvent event) {
        log.debug("게시글 생성 이벤트 처리 시작: boardId={}", event.getBoardId());
        try {
            moderationService.moderateBoardAsync(event.getBoardId());
            log.debug("게시글 검열 요청 완료: boardId={}", event.getBoardId());
        } catch (Exception e) {
            log.error("게시글 검열 처리 중 오류 발생: boardId={}", event.getBoardId(), e);
        }
    }
    
    /**
     * 게시글 수정 후 검열 처리
     * 트랜잭션이 완전히 커밋된 후에 실행되어 데이터 일관성 보장
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBoardUpdated(BoardUpdatedEvent event) {
        log.debug("게시글 수정 이벤트 처리 시작: boardId={}", event.getBoardId());
        try {
            moderationService.moderateBoardAsync(event.getBoardId());
            log.debug("게시글 검열 요청 완료: boardId={}", event.getBoardId());
        } catch (Exception e) {
            log.error("게시글 검열 처리 중 오류 발생: boardId={}", event.getBoardId(), e);
        }
    }
    
    /**
     * 댓글 생성 후 검열 처리
     * 트랜잭션이 완전히 커밋된 후에 실행되어 "댓글을 찾을 수 없습니다" 오류 방지
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCommentCreated(CommentCreatedEvent event) {
        log.debug("댓글 생성 이벤트 처리 시작: commentId={}", event.getCommentId());
        try {
            moderationService.moderateCommentAsync(event.getCommentId());
            log.debug("댓글 검열 요청 완료: commentId={}", event.getCommentId());
        } catch (Exception e) {
            log.error("댓글 검열 처리 중 오류 발생: commentId={}", event.getCommentId(), e);
        }
    }
    
    /**
     * 댓글 수정 후 검열 처리
     * 트랜잭션이 완전히 커밋된 후에 실행되어 데이터 일관성 보장
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCommentUpdated(CommentUpdatedEvent event) {
        log.debug("댓글 수정 이벤트 처리 시작: commentId={}", event.getCommentId());
        try {
            moderationService.moderateCommentAsync(event.getCommentId());
            log.debug("댓글 검열 요청 완료: commentId={}", event.getCommentId());
        } catch (Exception e) {
            log.error("댓글 검열 처리 중 오류 발생: commentId={}", event.getCommentId(), e);
        }
    }
}