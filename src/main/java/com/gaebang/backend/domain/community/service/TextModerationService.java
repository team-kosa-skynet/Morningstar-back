package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.domain.interview.llm.GeminiInterviewerGateway;
import com.gaebang.backend.domain.interview.llm.OpenAiInterviewerGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 텍스트 컨텐츠 전용 검열 서비스
 * - 제목/내용 통합 검열
 * - Circuit Breaker를 통한 AI 제공자 폴백 (Gemini → OpenAI)
 * - 재사용 가능한 독립적인 서비스
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TextModerationService {

    private final GeminiInterviewerGateway geminiInterviewerGateway;
    private final OpenAiInterviewerGateway openAiInterviewerGateway;

    @Value("${moderation.enabled:true}")
    private boolean moderationEnabled;

    @Value("${ai.provider:gemini}")
    private String primaryAiProvider;

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
            ModerationResult result;
            
            // AI 제공자 선택 (설정 기반)
            if ("openai".equalsIgnoreCase(primaryAiProvider)) {
                log.debug("[TEXT] Primary AI Provider: OpenAI");
                result = openAiInterviewerGateway.moderateContent(content);
            } else {
                log.debug("[TEXT] Primary AI Provider: Gemini");
                result = geminiInterviewerGateway.moderateContent(content);
            }

            log.debug("텍스트 검열 완료 - 부적절: {}, 사유: {}", result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("텍스트 검열 중 오류 발생: {}", e.getMessage());
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
     * @return 폴백 AI 제공자의 검열 결과
     */
    public CompletableFuture<ModerationResult> fallbackModeration(String content, Exception exception) {
        log.warn("Primary AI 검열 실패, 폴백 AI로 전환 - 예외: {}", exception.getMessage());
        
        try {
            ModerationResult result;
            
            // Primary가 Gemini면 OpenAI로, OpenAI면 Gemini로 폴백
            if ("openai".equalsIgnoreCase(primaryAiProvider)) {
                log.info("[TEXT] Fallback to Gemini");
                result = geminiInterviewerGateway.moderateContent(content);
            } else {
                log.info("[TEXT] Fallback to OpenAI");
                result = openAiInterviewerGateway.moderateContent(content);
            }

            log.info("폴백 AI 검열 성공 - 부적절: {}, 사유: {}", result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("폴백 AI 검열도 실패, 보수적으로 차단 처리: {}", e.getMessage());
            // 모든 AI 제공자 실패 시 보수적으로 차단 (보안 우선)
            return CompletableFuture.completedFuture(new ModerationResult(true, "AI 검열 시스템 장애로 인한 임시 차단 - 관리자 검토 필요"));
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
     * 현재 사용 중인 Primary AI 제공자
     * @return AI 제공자명
     */
    public String getPrimaryAiProvider() {
        return primaryAiProvider;
    }
}