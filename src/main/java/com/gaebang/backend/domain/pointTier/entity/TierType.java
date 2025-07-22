package com.gaebang.backend.domain.pointTier.entity;

public enum TierType {
    UNRANKED("언랭크"),
    IRON("아이언"),
    BRONZE("브론즈"),
    SILVER("실버"),
    GOLD("골드"),
    PLATINUM("플레티넘"),
    EMERALD("에메랄드"),
    DIAMOND("다이아"),
    MASTER("마스터"),
    GRANDMASTER("그랜드마스터"),
    CHALLENGER("챌린저");

    private final String displayName;

    TierType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}