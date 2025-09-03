package com.gaebang.backend.domain.newsData.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.newsData.exception.ImageApiConfigurationException;
import com.gaebang.backend.domain.newsData.exception.ImageApiException;
import com.gaebang.backend.domain.newsData.exception.ImageGenerationException;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationCircuitBreakerService {

    private final RestClient restClient;
    private final GeminiQuestionProperties geminiQuestionProperties;
    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    
    // Circuit Breaker와 Retry 인스턴스는 매번 조회 (간단한 방식)

    /**
     * Circuit Breaker와 Retry를 활용한 이미지 생성 (Fallback 포함)
     * @param prompt 이미지 생성 프롬프트
     * @param isPopular 인기글 여부 (크기/품질 결정)
     * @param newsId 뉴스 ID (로깅용)
     * @return Base64 이미지 데이터 또는 null
     */
    public String generateImageWithFallback(String prompt, boolean isPopular, Long newsId) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("imagen-api");
        Retry retry = retryRegistry.retry("imagen-api");
        
        // Circuit Breaker 상태 로깅
        logCircuitBreakerState(circuitBreaker, newsId);
        
        try {
            // Imagen API를 Circuit Breaker + Retry로 감싸기
            java.util.function.Supplier<String> retryableSupplier = 
                Retry.decorateSupplier(retry, () -> callImagenApi(prompt, isPopular, newsId));
            String imagenResult = circuitBreaker.executeSupplier(retryableSupplier);
            
            if (imagenResult != null) {
                return imagenResult;
            }
            
        } catch (Exception e) {
            log.warn("뉴스 ID {} - Imagen API 실패, DALL-E로 fallback: {}", newsId, e.getMessage());
        }
        
        // Imagen 실패 시 DALL-E로 fallback
        log.info("뉴스 ID {} - DALL-E 3 API로 fallback 시도", newsId);
        String dalleResult = callDallEApi(prompt, isPopular, newsId);
        
        if (dalleResult == null) {
            throw new ImageGenerationException("모든 이미지 생성 API 호출 실패 (뉴스 ID: " + newsId + ")");
        }
        
        return dalleResult;
    }

    /**
     * Circuit Breaker 상태 로깅
     */
    private void logCircuitBreakerState(CircuitBreaker circuitBreaker, Long newsId) {
        CircuitBreaker.State state = circuitBreaker.getState();
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        log.info("뉴스 ID {} - Circuit Breaker 상태: {} | 실패율: {:.1f}% | 실패: {}/{}",
            newsId,
            state,
            metrics.getFailureRate(),
            metrics.getNumberOfFailedCalls(),
            metrics.getNumberOfBufferedCalls()
        );
    }

    /**
     * Imagen API 호출 (Circuit Breaker용)
     */
    private String callImagenApi(String prompt, boolean isPopular, Long newsId) {
        // API 키 검증
        validateImagenApiSettings();

        // Imagen API 요청 구조
        Map<String, Object> instance = new HashMap<>();
        instance.put("prompt", prompt);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("sampleCount", 1);

        if (isPopular) {
            parameters.put("aspectRatio", "16:9");
            parameters.put("outputImageType", "HIGH_QUALITY");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("instances", Arrays.asList(instance));
        requestBody.put("parameters", parameters);

        String response = restClient.post()
                .uri(geminiQuestionProperties.getCreateImageUrl())
                .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                .header("Content-Type", "application/json")
                .body(requestBody)
                .exchange((request, httpResponse) -> {
                    if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                        int status = httpResponse.getStatusCode().value();
                        String errorDetails = readErrorResponse(httpResponse, newsId);
                        
                        log.error("뉴스 ID {} - Imagen API 호출 실패 {}: {}", newsId, status, errorDetails);
                        throw new ImageApiException("Imagen API 호출 실패 (HTTP " + status + "): " + errorDetails);
                    }
                    
                    try {
                        return new String(httpResponse.getBody().readAllBytes());
                    } catch (Exception e) {
                        throw new ImageApiException("Imagen API 응답 파싱 실패: " + e.getMessage());
                    }
                });

        return parseImagenResponse(response, newsId);
    }

    /**
     * DALL-E 3 API 호출 (Fallback)
     */
    private String callDallEApi(String prompt, boolean isPopular, Long newsId) {
        try {
            log.info("뉴스 ID {} - DALL-E 3 이미지 생성 시작", newsId);
            
            // API 키 검증
            validateOpenAiApiSettings();
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "dall-e-3");
            requestBody.put("prompt", prompt);
            requestBody.put("n", 1);
            
            // 인기글 여부에 따른 크기 및 품질 설정
            if (isPopular) {
                requestBody.put("size", "1792x1024"); // 16:9 비율
                requestBody.put("quality", "hd");
            } else {
                requestBody.put("size", "1024x1024");
                requestBody.put("quality", "standard");
            }
            
            requestBody.put("response_format", "b64_json"); // base64로 직접 받기

            String response = restClient.post()
                    .uri("https://api.openai.com/v1/images/generations")
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .exchange((request, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            int status = httpResponse.getStatusCode().value();
                            String errorDetails = readErrorResponse(httpResponse, newsId);
                            
                            log.error("뉴스 ID {} - DALL-E API 호출 실패 {}: {}", newsId, status, errorDetails);
                            throw new ImageApiException("DALL-E API 호출 실패 (HTTP " + status + "): " + errorDetails);
                        }
                        
                        try {
                            return new String(httpResponse.getBody().readAllBytes());
                        } catch (Exception e) {
                            throw new ImageApiException("DALL-E API 응답 파싱 실패: " + e.getMessage());
                        }
                    });

            if (response != null) {
                return parseDallEResponse(response, newsId);
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("뉴스 ID {} - DALL-E 이미지 생성 실패", newsId, e);
            return null;
        }
    }

    /**
     * Imagen API 설정 검증
     */
    private void validateImagenApiSettings() {
        String imagenUrl = geminiQuestionProperties.getCreateImageUrl();
        if (imagenUrl == null || imagenUrl.trim().isEmpty()) {
            throw new ImageApiConfigurationException("Imagen API URL이 설정되지 않음");
        }
        
        if (geminiQuestionProperties.getApiKey() == null || geminiQuestionProperties.getApiKey().trim().isEmpty()) {
            throw new ImageApiConfigurationException("Imagen API 키가 설정되지 않음");
        }
    }

    /**
     * OpenAI API 설정 검증
     */
    private void validateOpenAiApiSettings() {
        if (openaiQuestionProperties.getApiKey() == null || openaiQuestionProperties.getApiKey().trim().isEmpty()) {
            throw new ImageApiConfigurationException("OpenAI API 키가 설정되지 않음");
        }
    }

    /**
     * HTTP 에러 응답 본문 읽기
     */
    private String readErrorResponse(org.springframework.http.client.ClientHttpResponse httpResponse, Long newsId) {
        try {
            byte[] errorBodyBytes = httpResponse.getBody().readAllBytes();
            return errorBodyBytes.length > 0 ? new String(errorBodyBytes) : "응답 본문 없음";
        } catch (Exception e) {
            log.warn("뉴스 ID {} - 에러 응답 본문 읽기 실패", newsId);
            return "응답 본문 읽기 실패";
        }
    }

    /**
     * Imagen 응답 파싱
     */
    private String parseImagenResponse(String response, Long newsId) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode predictions = rootNode.path("predictions");

            if (!predictions.isArray() || predictions.isEmpty()) {
                log.warn("뉴스 ID {} - Imagen 응답에 predictions가 없음", newsId);
                return null;
            }

            JsonNode firstPrediction = predictions.get(0);
            if (firstPrediction == null || firstPrediction.isNull()) {
                log.warn("뉴스 ID {} - 첫 번째 prediction이 null", newsId);
                return null;
            }

            String base64Data = null;
            if (firstPrediction.has("bytesBase64Encoded")) {
                base64Data = firstPrediction.path("bytesBase64Encoded").asText();
            } else if (firstPrediction.has("image")) {
                base64Data = firstPrediction.path("image").asText();
            } else if (firstPrediction.has("data")) {
                base64Data = firstPrediction.path("data").asText();
            }

            if (base64Data == null || base64Data.trim().isEmpty()) {
                log.warn("뉴스 ID {} - Imagen 이미지 데이터가 비어있음", newsId);
                return null;
            }

            return base64Data;

        } catch (Exception e) {
            log.error("뉴스 ID {} - Imagen 응답 파싱 중 오류", newsId, e);
            return null;
        }
    }

    /**
     * DALL-E 응답 파싱
     */
    private String parseDallEResponse(String response, Long newsId) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode dataArray = rootNode.path("data");

            if (dataArray.isEmpty()) {
                log.warn("뉴스 ID {} - DALL-E 응답에 data가 없습니다.", newsId);
                return null;
            }

            JsonNode firstData = dataArray.get(0);
            if (firstData.has("b64_json")) {
                String base64Data = firstData.path("b64_json").asText();
                if (base64Data != null && !base64Data.trim().isEmpty()) {
                    log.info("뉴스 ID {} - DALL-E 이미지 생성 성공", newsId);
                    return base64Data;
                }
            }

            log.warn("뉴스 ID {} - DALL-E 이미지 데이터가 비어있습니다.", newsId);
            return null;

        } catch (Exception e) {
            log.error("뉴스 ID {} - DALL-E 응답 파싱 중 오류", newsId, e);
            return null;
        }
    }
}