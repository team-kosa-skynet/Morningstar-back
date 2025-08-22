package com.gaebang.backend.domain.community.event;

/**
 * 댓글 수정 이벤트
 * 트랜잭션 커밋 후 검열 시스템을 트리거하기 위한 이벤트
 */
public class CommentUpdatedEvent {
    
    private final Long commentId;
    
    public CommentUpdatedEvent(Long commentId) {
        this.commentId = commentId;
    }
    
    public Long getCommentId() {
        return commentId;
    }
}