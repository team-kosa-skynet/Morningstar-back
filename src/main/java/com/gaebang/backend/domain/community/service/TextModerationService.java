package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.global.infrastructure.llm.LlmGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 텍스트 컨텐츠 전용 검열 서비스
 * - 제목/내용 통합 검열
 * - Circuit Breaker를 통한 AI 제공자 폴백 (Primary → Fallback)
 * - Global Infrastructure Layer의 LLM Gateway 사용
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TextModerationService {

    private final LlmGateway primaryLlmGateway;
    
    @Qualifier("fallbackLlmGateway")
    private final LlmGateway fallbackLlmGateway;

    @Value("${moderation.enabled:true}")
    private boolean moderationEnabled;

    /**
     * 텍스트 내용 검열 (Circuit Breaker 적용)
     * @param content 검열할 텍스트 내용
     * @return 검열 결과
     */
    @CircuitBreaker(name = "text-moderation", fallbackMethod = "fallbackModeration")
    @Retry(name = "text-moderation")
    @TimeLimiter(name = "text-moderation")
    public CompletableFuture<ModerationResult> moderateText(String content) {
        if (!moderationEnabled) {
            log.debug("텍스트 검열이 비활성화되어 있습니다.");
            return CompletableFuture.completedFuture(new ModerationResult(false, null));
        }

        try {
            log.debug("[TEXT] Using Primary LLM Gateway: {}", primaryLlmGateway.getProviderName());
            ModerationResult result = primaryLlmGateway.moderateContent(content);
            
            log.debug("[TEXT] 검열 완료 - 부적절: {}, 사유: {}", result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("[TEXT] Primary LLM Gateway 오류 발생: {}", e.getMessage());
            throw e; // Circuit Breaker가 폴백 메서드 호출
        }
    }

    /**
     * 제목과 내용을 통합하여 검열
     * @param title 게시글 제목
     * @param content 게시글 내용
     * @return 검열 결과
     */
    public CompletableFuture<ModerationResult> moderateTitleAndContent(String title, String content) {
        String combinedContent = (title != null ? title : "") + "\n" + (content != null ? content : "");
        return moderateText(combinedContent.trim());
    }

    /**
     * Circuit Breaker 폴백 메서드
     * @param content 검열할 텍스트 내용
     * @param exception 발생한 예외
     * @return 폴백 LLM Gateway의 검열 결과
     */
    public CompletableFuture<ModerationResult> fallbackModeration(String content, Exception exception) {
        log.warn("[TEXT] Primary LLM Gateway 실패, Fallback Gateway로 전환 - 예외: {}", exception.getMessage());
        
        try {
            log.info("[TEXT] Fallback to: {}", fallbackLlmGateway.getProviderName());
            ModerationResult result = fallbackLlmGateway.moderateContent(content);

            log.info("[TEXT] Fallback 검열 성공 - 부적절: {}, 사유: {}", result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("[TEXT] Fallback LLM Gateway도 실패, 보수적 차단 처리: {}", e.getMessage());
            // 모든 LLM Gateway 실패 시 보수적으로 차단 (보안 우선)
            return CompletableFuture.completedFuture(new ModerationResult(true, "LLM 검열 시스템 전체 장애 - 관리자 검토 필요"));
        }
    }

    /**
     * 텍스트 길이에 따른 최적화된 검열
     * @param content 검열할 텍스트
     * @return 검열 결과
     */
    public CompletableFuture<ModerationResult> moderateOptimized(String content) {
        if (content == null || content.trim().isEmpty()) {
            return CompletableFuture.completedFuture(new ModerationResult(false, null));
        }

        // 짧은 텍스트는 빠른 검열, 긴 텍스트는 정밀 검열
        if (content.length() < 100) {
            log.debug("단문 텍스트 검열 모드");
        } else {
            log.debug("장문 텍스트 검열 모드");
        }

        return moderateText(content);
    }

    /**
     * 검열 서비스 상태 확인
     * @return 서비스 활성화 여부
     */
    public boolean isEnabled() {
        return moderationEnabled;
    }

    /**
     * 현재 사용 중인 Primary LLM Gateway 제공자
     * @return LLM 제공자명
     */
    public String getPrimaryLlmProvider() {
        return primaryLlmGateway.getProviderName();
    }
    
    /**
     * Fallback LLM Gateway 제공자
     * @return Fallback LLM 제공자명
     */
    public String getFallbackLlmProvider() {
        return fallbackLlmGateway.getProviderName();
    }
}