package com.gaebang.backend.domain.interviewTurn.dto.response;

import java.util.List;
import java.util.Map;

public record NextTurnResponseDto(
        String nextQuestion,
        String questionIntent,          // 🆕 질문 의도
        List<String> answerGuides,      // 🆕 답변 가이드
        String coachingTips,            // 이전 답변 피드백
        Map<String, Integer> scoreDelta,
        boolean done,
        TtsPayloadDto tts
) {
}
