package com.gaebang.backend.domain.newsData.event;

public class NewsCreatedEvent {
    private final int newsCount;

    public NewsCreatedEvent(int newsCount) {
        this.newsCount = newsCount;
    }

    public int getNewsCount() {
        return newsCount;
    }
}
