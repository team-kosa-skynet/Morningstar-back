package com.gaebang.backend.domain.community.event;

/**
 * 게시글 수정 이벤트
 * 트랜잭션 커밋 후 검열 시스템을 트리거하기 위한 이벤트
 */
public class BoardUpdatedEvent {
    
    private final Long boardId;
    
    public BoardUpdatedEvent(Long boardId) {
        this.boardId = boardId;
    }
    
    public Long getBoardId() {
        return boardId;
    }
}