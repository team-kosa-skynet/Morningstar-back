package com.gaebang.backend.domain.community.event;

/**
 * 검열 완료 이벤트
 * 검열 통과한 게시글에 대해서만 발행됨
 */
public class ModerationCompletedEvent {
    
    private final Long boardId;

    public ModerationCompletedEvent(Long boardId) {
        this.boardId = boardId;
    }

    public Long getBoardId() {
        return boardId;
    }

    @Override
    public String toString() {
        return "ModerationCompletedEvent{" +
                "boardId=" + boardId +
                '}';
    }
}