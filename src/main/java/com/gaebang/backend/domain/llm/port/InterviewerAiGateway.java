package com.gaebang.backend.domain.llm.port;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto;

import java.util.List;
import java.util.Map;

/**
 * 면접 AI 전용 인터페이스 (Port)
 * - LLM Supporting Subdomain의 핵심 인터페이스
 * - Interview Domain에서 AI 기능을 사용하기 위한 Port
 * - DDD Port-Adapter 패턴의 Port 역할
 */
public interface InterviewerAiGateway {

    /**
     * 면접 시작 인사말 생성
     * @param displayName 면접자 이름
     * @return 면접 시작 인사말
     */
    String generateGreeting(String displayName);
    
    /**
     * 면접 계획 수립
     * @param role 면접 역할 (BACKEND, FRONTEND 등)
     * @param profileSnapshotJson 면접자 프로필 정보
     * @param candidates 후보 질문 목록
     * @return 면접 계획 (질문 목록, 의도, 가이드 등)
     */
    Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates);
    
    /**
     * 면접 턴 진행 및 피드백 생성
     * @param planJson 면접 계획 JSON
     * @param questionIndex 현재 질문 인덱스
     * @param transcript 면접자 답변 텍스트
     * @param recentSummaryJson 최근 답변 요약
     * @param previousResponseId AI 응답 컨텍스트 ID
     * @return 턴 피드백 (점수, 코멘트, 가이드 등)
     */
    AiTurnFeedbackDto nextTurn(String planJson, int questionIndex, String transcript, String recentSummaryJson, String previousResponseId) throws Exception;
    
    /**
     * 면접 최종 리포트 생성
     * @param sessionJson 전체 면접 세션 데이터
     * @param previousResponseId AI 응답 컨텍스트 ID
     * @return 최종 면접 리포트
     */
    Map<String, Object> finalizeReport(String sessionJson, String previousResponseId);
    
    /**
     * 질문 의도와 답변 가이드 생성
     * @param questionType 질문 유형
     * @param questionText 질문 텍스트
     * @param role 면접 역할
     * @return 질문 의도 및 답변 가이드
     */
    Map<String, Object> generateQuestionIntentAndGuides(String questionType, String questionText, String role) throws Exception;
    
    /**
     * 배치 평가: 전체 면접 세션을 종합하여 5지표 점수 계산
     * @param evaluationData 평가 데이터
     * @param role 면접 역할
     * @param previousResponseId AI 응답 컨텍스트 ID
     * @return 5지표 평가 결과
     */
    Map<String, Object> generateBatchEvaluation(String evaluationData, String role, String previousResponseId) throws Exception;
    
    /**
     * 문서에서 구조화된 정보 추출
     * @param rawText 원본 문서 텍스트
     * @return 구조화된 정보 (기술스택, 프로젝트, 경력 등)
     */
    Map<String, Object> extractDocumentInfo(String rawText) throws Exception;
    
    /**
     * 컨텐츠 검열
     * @param content 검열할 텍스트 내용
     * @return 검열 결과
     */
    ModerationResult moderateContent(String content) throws Exception;
    
    /**
     * 이미지 검열
     * @param base64Image Base64 인코딩된 이미지
     * @return 검열 결과
     */
    ModerationResult moderateImage(String base64Image) throws Exception;
    
    /**
     * AI 제공자 이름 반환
     * @return AI 제공자 이름
     */
    String getProviderName();
}