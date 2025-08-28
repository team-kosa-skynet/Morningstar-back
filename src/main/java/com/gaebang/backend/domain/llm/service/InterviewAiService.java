package com.gaebang.backend.domain.llm.service;

import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.llm.port.InterviewerAiGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Interview AI Domain Service
 * - LLM Supporting Subdomain의 핵심 도메인 서비스
 * - Interview 관련 AI 기능을 캡슐화하고 비즈니스 로직 제공
 * - InterviewerAiGateway Port를 통해 실제 AI 구현체와 연동
 */
@Slf4j
@Service
public class InterviewAiService {

    private final InterviewerAiGateway openAiGateway;
    private final InterviewerAiGateway geminiGateway;
    
    public InterviewAiService(
            @Qualifier("openAiInterviewerGateway") InterviewerAiGateway openAiGateway,
            @Qualifier("geminiInterviewerGateway") InterviewerAiGateway geminiGateway
    ) {
        this.openAiGateway = openAiGateway;
        this.geminiGateway = geminiGateway;
    }
    
    @Value("${ai.provider:gemini}")
    private String aiProvider;

    /**
     * 현재 설정된 AI 제공자를 반환
     */
    public InterviewerAiGateway getAiGateway() {
        boolean useOpenAi = "openai".equalsIgnoreCase(aiProvider);
        InterviewerAiGateway selectedGateway = useOpenAi ? openAiGateway : geminiGateway;
        
        log.debug("[InterviewAiService] Using AI Provider: {} ({})", 
                aiProvider, selectedGateway.getClass().getSimpleName());
        
        return selectedGateway;
    }

    /**
     * 면접 인사말 생성 (도메인 서비스 레벨)
     * @param displayName 면접자 이름
     * @return 면접 시작 인사말
     */
    public String generateGreeting(String displayName) {
        try {
            log.info("[InterviewAiService] 면접 인사말 생성 시작 - 면접자: {}", displayName);
            
            String greeting = getAiGateway().generateGreeting(displayName);
            
            log.info("[InterviewAiService] 면접 인사말 생성 완료 - 길이: {}자", greeting.length());
            return greeting;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 면접 인사말 생성 실패 - 면접자: {}, 에러: {}", 
                    displayName, e.getMessage(), e);
            
            // 폴백: 기본 인사말 제공
            return displayName + "님, 안녕하세요. 면접을 시작하겠습니다.";
        }
    }

    /**
     * 면접 계획 수립 (도메인 서비스 레벨)
     * @param role 면접 역할
     * @param profileSnapshotJson 면접자 프로필
     * @param candidates 후보 질문들
     * @return 면접 계획
     */
    public Map<String, Object> generateInterviewPlan(String role, String profileSnapshotJson, 
                                                    List<Map<String, Object>> candidates) {
        try {
            log.info("[InterviewAiService] 면접 계획 수립 시작 - 역할: {}, 프로필: {}자", 
                    role, profileSnapshotJson != null ? profileSnapshotJson.length() : 0);
            
            Map<String, Object> plan = getAiGateway().generatePlan(role, profileSnapshotJson, candidates);
            
            log.info("[InterviewAiService] 면접 계획 수립 완료 - 질문 수: {}", 
                    plan.containsKey("questions") ? ((List<?>) plan.get("questions")).size() : 0);
            
            return plan;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 면접 계획 수립 실패 - 역할: {}, 에러: {}", 
                    role, e.getMessage(), e);
            throw new RuntimeException("면접 계획 수립에 실패했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 면접 턴 진행 및 피드백 생성 (도메인 서비스 레벨)
     * @param planJson 면접 계획
     * @param questionIndex 현재 질문 인덱스
     * @param transcript 면접자 답변
     * @param recentSummaryJson 최근 요약
     * @param previousResponseId 이전 응답 ID
     * @return 턴 피드백
     */
    public AiTurnFeedbackDto processInterviewTurn(String planJson, int questionIndex, String transcript,
                                                 String recentSummaryJson, String previousResponseId) throws Exception {
        try {
            log.info("[InterviewAiService] 면접 턴 처리 시작 - 질문: {}번, 답변: {}자", 
                    questionIndex + 1, transcript.length());
            
            AiTurnFeedbackDto feedback = getAiGateway().nextTurn(planJson, questionIndex, transcript, 
                                                               recentSummaryJson, previousResponseId);
            
            log.info("[InterviewAiService] 면접 턴 처리 완료 - 코칭팁: {}자", 
                    feedback.coachingTips().length());
            
            return feedback;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 면접 턴 처리 실패 - 질문: {}번, 에러: {}", 
                    questionIndex + 1, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 최종 면접 리포트 생성 (도메인 서비스 레벨)
     * @param sessionJson 전체 면접 세션 데이터
     * @param previousResponseId 이전 응답 ID
     * @return 최종 면접 리포트
     */
    public Map<String, Object> generateFinalReport(String sessionJson, String previousResponseId) {
        try {
            log.info("[InterviewAiService] 최종 리포트 생성 시작 - 세션 데이터: {}자", sessionJson.length());
            
            Map<String, Object> report = getAiGateway().finalizeReport(sessionJson, previousResponseId);
            
            log.info("[InterviewAiService] 최종 리포트 생성 완료");
            
            return report;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 최종 리포트 생성 실패 - 에러: {}", e.getMessage(), e);
            throw new RuntimeException("최종 리포트 생성에 실패했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 질문 의도 및 가이드 생성 (도메인 서비스 레벨)
     * @param questionType 질문 유형
     * @param questionText 질문 텍스트
     * @param role 면접 역할
     * @return 질문 의도 및 가이드
     */
    public Map<String, Object> generateQuestionGuides(String questionType, String questionText, String role) throws Exception {
        try {
            log.debug("[InterviewAiService] 질문 가이드 생성 시작 - 유형: {}, 역할: {}", questionType, role);
            
            Map<String, Object> guides = getAiGateway().generateQuestionIntentAndGuides(questionType, questionText, role);
            
            log.debug("[InterviewAiService] 질문 가이드 생성 완료");
            
            return guides;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 질문 가이드 생성 실패 - 유형: {}, 에러: {}", questionType, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 배치 평가 수행 (도메인 서비스 레벨)
     * @param evaluationData 평가 데이터
     * @param role 면접 역할
     * @param previousResponseId 이전 응답 ID
     * @return 5지표 평가 결과
     */
    public Map<String, Object> performBatchEvaluation(String evaluationData, String role, String previousResponseId) throws Exception {
        try {
            log.info("[InterviewAiService] 배치 평가 수행 시작 - 역할: {}", role);
            
            Map<String, Object> evaluation = getAiGateway().generateBatchEvaluation(evaluationData, role, previousResponseId);
            
            log.info("[InterviewAiService] 배치 평가 수행 완료");
            
            return evaluation;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 배치 평가 수행 실패 - 역할: {}, 에러: {}", role, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 문서 정보 추출 (도메인 서비스 레벨)
     * @param rawText 원본 문서 텍스트
     * @return 구조화된 문서 정보
     */
    public Map<String, Object> extractDocumentInfo(String rawText) throws Exception {
        try {
            log.info("[InterviewAiService] 문서 정보 추출 시작 - 원본 텍스트: {}자", rawText.length());
            
            Map<String, Object> extractedInfo = getAiGateway().extractDocumentInfo(rawText);
            
            log.info("[InterviewAiService] 문서 정보 추출 완료");
            
            return extractedInfo;
            
        } catch (Exception e) {
            log.error("[InterviewAiService] 문서 정보 추출 실패 - 에러: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 현재 사용 중인 AI 제공자 이름 반환
     */
    public String getCurrentAiProvider() {
        return aiProvider;
    }
}