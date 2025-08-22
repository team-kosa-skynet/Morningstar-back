package com.gaebang.backend.domain.community.event;

/**
 * 게시글 생성 이벤트
 * 트랜잭션 커밋 후 검열 시스템을 트리거하기 위한 이벤트
 */
public class BoardCreatedEvent {
    
    private final Long boardId;
    
    public BoardCreatedEvent(Long boardId) {
        this.boardId = boardId;
    }
    
    public Long getBoardId() {
        return boardId;
    }
}