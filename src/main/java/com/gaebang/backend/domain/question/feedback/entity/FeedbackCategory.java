package com.gaebang.backend.domain.question.feedback.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FeedbackCategory {
    
    // 긍정적 피드백
    ACCURATE("정확해요", FeedbackType.POSITIVE),
    FAST("빨라요", FeedbackType.POSITIVE),
    SATISFYING("마음에 들어요", FeedbackType.POSITIVE),
    KIND("친절해요", FeedbackType.POSITIVE),
    DETAILED("답변이 자세해요", FeedbackType.POSITIVE),
    
    // 부정적 피드백
    INCORRECT("틀려요", FeedbackType.NEGATIVE),
    HALLUCINATION("환각증상", FeedbackType.NEGATIVE),
    SLOW("느려요", FeedbackType.NEGATIVE),
    TOO_LONG("답변이 너무 길어요", FeedbackType.NEGATIVE),
    NOT_DETAILED("자세하지 않아요", FeedbackType.NEGATIVE);
    
    private final String displayName;
    private final FeedbackType feedbackType;
    
    public enum FeedbackType {
        POSITIVE, NEGATIVE
    }
}