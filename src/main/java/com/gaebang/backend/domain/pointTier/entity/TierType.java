package com.gaebang.backend.domain.pointTier.entity;

public enum TierType {
    MERCURY("수성"),
    VENUS("금성"),
    EARTH("지구"),
    MARS("화성"),
    JUPITER("목성"),
    SATURN("토성"),
    URANUS("천왕성"),
    NEPTUNE("해왕성"),
    SUN("태양"),
    BLACK_HOLE("블랙홀");

    private final String displayName;

    TierType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}