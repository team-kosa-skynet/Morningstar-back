package com.gaebang.backend.domain.community.entity;

/**
 * 게시글 카테고리
 */
public enum BoardCategory {
    GENERAL("일반"),
    QUESTION("질문");

    private final String displayName;

    BoardCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * displayName으로 BoardCategory 찾기
     */
    public static BoardCategory fromDisplayName(String displayName) {
        for (BoardCategory category : values()) {
            if (category.displayName.equals(displayName)) {
                return category;
            }
        }
        throw new IllegalArgumentException("잘못된 카테고리명: " + displayName);
    }
}