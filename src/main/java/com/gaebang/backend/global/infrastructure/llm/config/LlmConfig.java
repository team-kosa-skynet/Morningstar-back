package com.gaebang.backend.global.infrastructure.llm.config;

import com.gaebang.backend.global.infrastructure.llm.GeminiLlmGateway;
import com.gaebang.backend.global.infrastructure.llm.LlmGateway;
import com.gaebang.backend.global.infrastructure.llm.OpenAiLlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LLM Gateway 설정 및 Bean 구성
 * - Global Infrastructure Layer의 LLM 서비스 설정
 * - AI Provider 선택 및 Primary/Secondary Bean 구성
 * - Circuit Breaker, Resilience4j와 연동하여 장애 복구 지원
 */
@Slf4j
@Configuration
public class LlmConfig {

    @Value("${ai.provider:gemini}")
    private String primaryAiProvider;

    /**
     * Primary LLM Gateway 결정
     * application-core.yml의 ai.provider 설정에 따라 동적으로 결정
     * 
     * @param geminiGateway Gemini LLM Gateway
     * @param openAiGateway OpenAI LLM Gateway
     * @return Primary로 사용할 LLM Gateway
     */
    @Bean
    @Primary
    public LlmGateway primaryLlmGateway(
            @Qualifier("geminiLlmGateway") GeminiLlmGateway geminiGateway,
            @Qualifier("openAiLlmGateway") OpenAiLlmGateway openAiGateway
    ) {
        LlmGateway primaryGateway;
        
        if ("openai".equalsIgnoreCase(primaryAiProvider)) {
            primaryGateway = openAiGateway;
            log.info("[LLM][Config] Primary AI Provider: OpenAI");
        } else {
            primaryGateway = geminiGateway;
            log.info("[LLM][Config] Primary AI Provider: Gemini (default)");
        }
        
        // Gateway 가용성 검증
        if (!primaryGateway.isAvailable()) {
            log.warn("[LLM][Config] Primary Gateway({})가 사용 불가능합니다. API Key를 확인하세요.", 
                    primaryGateway.getProviderName());
        }
        
        return primaryGateway;
    }
    
    /**
     * Secondary (Fallback) LLM Gateway 결정
     * Primary와 반대되는 Provider를 Fallback용으로 사용
     * 
     * @param geminiGateway Gemini LLM Gateway
     * @param openAiGateway OpenAI LLM Gateway
     * @return Fallback용 LLM Gateway
     */
    @Bean("fallbackLlmGateway")
    public LlmGateway fallbackLlmGateway(
            @Qualifier("geminiLlmGateway") GeminiLlmGateway geminiGateway,
            @Qualifier("openAiLlmGateway") OpenAiLlmGateway openAiGateway
    ) {
        LlmGateway fallbackGateway;
        
        if ("openai".equalsIgnoreCase(primaryAiProvider)) {
            fallbackGateway = geminiGateway;
            log.info("[LLM][Config] Fallback AI Provider: Gemini");
        } else {
            fallbackGateway = openAiGateway;
            log.info("[LLM][Config] Fallback AI Provider: OpenAI");
        }
        
        // Fallback Gateway 가용성 검증
        if (!fallbackGateway.isAvailable()) {
            log.warn("[LLM][Config] Fallback Gateway({})가 사용 불가능합니다. 장애 복구 기능이 제한됩니다.", 
                    fallbackGateway.getProviderName());
        }
        
        return fallbackGateway;
    }
    
    /**
     * LLM 설정 정보 출력
     */
    public void logLlmConfiguration() {
        log.info("[LLM][Config] ================== LLM Configuration ==================");
        log.info("[LLM][Config] Primary Provider: {}", primaryAiProvider);
        log.info("[LLM][Config] Configuration Source: application-core.yml (ai.provider)");
        log.info("[LLM][Config] Supported Providers: Gemini, OpenAI");
        log.info("[LLM][Config] Features: Text/Image Moderation, Document Info Extraction");
        log.info("[LLM][Config] Circuit Breaker: Enabled (Resilience4j)");
        log.info("[LLM][Config] ===================================================");
    }
}