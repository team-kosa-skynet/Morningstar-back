package com.gaebang.backend.domain.interview.llm;

import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto;

import java.util.List;
import java.util.Map;

public interface InterviewerAiGateway {

    String generateGreeting(String displayName);
    Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates);
    AiTurnFeedbackDto nextTurn(String planJson, int questionIndex, String transcript, String recentSummaryJson, String previousResponseId) throws Exception;
    Map<String, Object> finalizeReport(String sessionJson, String previousResponseId);
    
    // 질문 의도와 답변 가이드 생성
    Map<String, Object> generateQuestionIntentAndGuides(String questionType, String questionText, String role) throws Exception;
    
    // 배치 평가: 전체 면접 세션을 종합하여 5지표 점수 계산
    Map<String, Object> generateBatchEvaluation(String evaluationData, String role, String previousResponseId) throws Exception;
    
    // 문서 정보 추출: 포트폴리오/자소서에서 구조화된 정보 추출
    Map<String, Object> extractDocumentInfo(String documentText) throws Exception;
}
