package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.global.infrastructure.llm.LlmGateway;
import com.gaebang.backend.global.util.S3.S3ImageService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
@Service
public class ImageModerationService {

    private final LlmGateway primaryLlmGateway;
    
    @Qualifier("fallbackLlmGateway")
    private final LlmGateway fallbackLlmGateway;
    
    private final S3ImageService s3ImageService;

    @Value("${moderation.enabled:true}")
    private boolean moderationEnabled;

    /**
     * 이미지 URL로 검열 수행 (Circuit Breaker 적용)
     * @param imageUrl S3 이미지 URL
     * @return 검열 결과
     */
    @CircuitBreaker(name = "image-moderation", fallbackMethod = "fallbackModeration")
    @Retry(name = "image-moderation")
    @TimeLimiter(name = "image-moderation")
    public CompletableFuture<ModerationResult> moderateImage(String imageUrl) {
        if (!moderationEnabled) {
            log.debug("이미지 검열이 비활성화되어 있습니다.");
            return CompletableFuture.completedFuture(new ModerationResult(false, null));
        }

        try {
            // S3ImageService로 Base64 변환 (기존 ImageEncodingUtil 대체)
            String base64Image = s3ImageService.encodeImageToBase64(imageUrl);
            
            log.debug("[IMAGE] Using Primary LLM Gateway: {}", primaryLlmGateway.getProviderName());
            ModerationResult result = primaryLlmGateway.moderateImage(base64Image);

            log.debug("[IMAGE] 검열 완료 - URL: {}, 부적절: {}, 사유: {}", imageUrl, result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            log.error("[IMAGE] Primary LLM Gateway 오류 발생 - URL: {}, 오류: {}", imageUrl, e.getMessage());
            throw new RuntimeException("이미지 검열 실패", e); // Circuit Breaker가 폴백 메서드 호출
        }
    }

    /**
     * Circuit Breaker 폴백 메서드
     * @param imageUrl 이미지 URL
     * @param exception 발생한 예외
     * @return 폴백 AI 제공자의 검열 결과
     */
    public CompletableFuture<ModerationResult> fallbackModeration(String imageUrl, Exception exception) {
        log.warn("Primary AI 이미지 검열 실패, 폴백 AI로 전환 - URL: {}, 예외: {}", imageUrl, exception.getMessage());
        
        try {
            // S3ImageService로 Base64 변환
            String base64Image = s3ImageService.encodeImageToBase64(imageUrl);
            
            log.info("[IMAGE] Fallback to: {}", fallbackLlmGateway.getProviderName());
            ModerationResult result = fallbackLlmGateway.moderateImage(base64Image);

            log.info("[IMAGE] Fallback 검열 성공 - URL: {}, 부적절: {}, 사유: {}", imageUrl, result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("[IMAGE] Fallback LLM Gateway도 실패, 보수적 차단 처리 - URL: {}, 오류: {}", imageUrl, e.getMessage());
            // 모든 LLM Gateway 실패 시 보수적으로 차단 (보안 우선)
            return CompletableFuture.completedFuture(new ModerationResult(true, "LLM 이미지 검열 시스템 전체 장애 - 관리자 검토 필요"));
        }
    }

    /**
     * 여러 이미지 URL들을 순차적으로 검열
     * @param imageUrls 이미지 URL 리스트
     * @return 부적절한 이미지 URL 리스트
     */
    public List<String> moderateImages(List<String> imageUrls) {
        if (!moderationEnabled || imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }

        return imageUrls.stream()
                .filter(this::isImageInappropriate)
                .toList();
    }

    /**
     * 단일 이미지가 부적절한지 확인
     */
    private boolean isImageInappropriate(String imageUrl) {
        try {
            CompletableFuture<ModerationResult> resultFuture = moderateImage(imageUrl);
            ModerationResult result = resultFuture.get(); // 동기 처리
            return result.isInappropriate();
        } catch (Exception e) {
            log.error("이미지 검열 실패 - URL: {}", imageUrl, e);
            return false; // 오류 시 안전하게 승인
        }
    }

    /**
     * 지원되는 이미지 형식인지 확인
     */
    public boolean isSupportedImageFormat(String imageUrl) {
        if (imageUrl == null) return false;
        
        String lowerUrl = imageUrl.toLowerCase();
        return lowerUrl.endsWith(".jpg") || 
               lowerUrl.endsWith(".jpeg") || 
               lowerUrl.endsWith(".png") || 
               lowerUrl.endsWith(".webp");
    }
}