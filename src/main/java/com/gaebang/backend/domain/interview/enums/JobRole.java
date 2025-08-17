package com.gaebang.backend.domain.interview.enums;

public enum JobRole {
    FULLSTACK("풀스택"), 
    FRONTEND("프론트엔드"), 
    BACKEND("백엔드");

    private final String displayName;

    JobRole(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}