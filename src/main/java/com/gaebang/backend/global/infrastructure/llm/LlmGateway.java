package com.gaebang.backend.global.infrastructure.llm;

import com.gaebang.backend.domain.community.dto.ModerationResult;

import java.util.Map;

/**
 * 범용 LLM Gateway 인터페이스
 * - 여러 도메인에서 공통으로 사용하는 LLM 기능 정의
 * - 외부 AI 서비스 (OpenAI, Gemini 등)와의 통합 인터페이스
 * - DDD 아키텍처에서 Infrastructure Layer에 위치
 */
public interface LlmGateway {
    
    /**
     * 텍스트 컨텐츠 검열
     * @param content 검열할 텍스트 내용
     * @return 검열 결과 (부적절 여부 및 사유)
     */
    ModerationResult moderateContent(String content);
    
    /**
     * 이미지 컨텐츠 검열
     * @param base64Image Base64로 인코딩된 이미지 데이터
     * @return 검열 결과 (부적절 여부 및 사유)
     */
    ModerationResult moderateImage(String base64Image);
    
    /**
     * 문서에서 구조화된 정보 추출
     * @param rawText 원본 텍스트 내용
     * @return 추출된 구조화 정보 (기술스택, 프로젝트, 경력 등)
     */
    Map<String, Object> extractDocumentInfo(String rawText);
    
    /**
     * LLM Gateway의 상태 확인
     * @return 서비스 가용 여부
     */
    boolean isAvailable();
    
    /**
     * 현재 사용 중인 AI Provider 이름
     * @return AI Provider 식별자 (gemini, openai 등)
     */
    String getProviderName();
}