package com.gaebang.backend.domain.interviewTurn.llm;

import com.gaebang.backend.domain.interviewTurn.dto.internal.AiTurnFeedbackDto;

import java.util.List;
import java.util.Map;

public interface InterviewerAiGateway {

    String generateGreeting(String displayName);
    Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates);
    AiTurnFeedbackDto nextTurn(String planJson, int questionIndex, String transcript, String recentSummaryJson, String previousResponseId) throws Exception;
    Map<String, Object> finalizeReport(String sessionJson, String previousResponseId);
    
    // 🆕 질문 의도와 답변 가이드 생성
    Map<String, Object> generateQuestionIntentAndGuides(String questionType, String questionText, String role) throws Exception;
}
