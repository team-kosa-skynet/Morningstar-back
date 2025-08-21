package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.domain.interview.llm.GeminiInterviewerGateway;
import com.gaebang.backend.domain.interview.llm.OpenAiInterviewerGateway;
import com.gaebang.backend.global.util.S3.S3ImageService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
@Service
public class ImageModerationService {

    private final GeminiInterviewerGateway geminiInterviewerGateway;
    private final OpenAiInterviewerGateway openAiInterviewerGateway;
    private final S3ImageService s3ImageService;

    @Value("${moderation.enabled:true}")
    private boolean moderationEnabled;

    @Value("${ai.provider:gemini}")
    private String primaryAiProvider;

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
            
            ModerationResult result;
            
            // AI 제공자 선택 (설정 기반)
            if ("openai".equalsIgnoreCase(primaryAiProvider)) {
                log.debug("[IMAGE] Primary AI Provider: OpenAI");
                result = openAiInterviewerGateway.moderateImage(base64Image);
            } else {
                log.debug("[IMAGE] Primary AI Provider: Gemini");
                result = geminiInterviewerGateway.moderateImage(base64Image);
            }

            log.debug("이미지 검열 완료 - URL: {}, 부적절: {}, 사유: {}", imageUrl, result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            log.error("이미지 검열 중 오류 발생 - URL: {}, 오류: {}", imageUrl, e.getMessage());
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
            
            ModerationResult result;
            
            // Primary가 Gemini면 OpenAI로, OpenAI면 Gemini로 폴백
            if ("openai".equalsIgnoreCase(primaryAiProvider)) {
                log.info("[IMAGE] Fallback to Gemini");
                result = geminiInterviewerGateway.moderateImage(base64Image);
            } else {
                log.info("[IMAGE] Fallback to OpenAI");
                result = openAiInterviewerGateway.moderateImage(base64Image);
            }

            log.info("폴백 AI 이미지 검열 성공 - URL: {}, 부적절: {}, 사유: {}", imageUrl, result.isInappropriate(), result.getReason());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("폴백 AI 이미지 검열도 실패, 안전하게 승인 처리 - URL: {}, 오류: {}", imageUrl, e.getMessage());
            // 모든 AI 제공자 실패 시 안전하게 승인
            return CompletableFuture.completedFuture(new ModerationResult(false, "AI 검열 시스템 일시 장애"));
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