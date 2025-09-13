package com.gaebang.backend.domain.community.event;

public record BoardChangedEvent(Long boardId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}