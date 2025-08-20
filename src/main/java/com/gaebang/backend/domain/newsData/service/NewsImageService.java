package com.gaebang.backend.domain.newsData.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.newsData.entity.NewsData;
import com.gaebang.backend.domain.newsData.repository.NewsDataRepository;
import com.gaebang.backend.domain.question.gemini.util.GeminiQuestionProperties;
import com.gaebang.backend.global.util.S3.S3ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsImageService {

    private final RestClient restClient;
    private final GeminiQuestionProperties geminiQuestionProperties;
    private final NewsDataRepository newsDataRepository;
    private final S3ImageService s3ImageService;

    // 전역 상태 관리
    private volatile boolean apiQuotaExceeded = false;
    private volatile LocalDateTime quotaResetTime = null;

    // 이미지가 없는 뉴스 데이터만 조회
    public List<NewsData> getNewsWithoutImages() {
        List<NewsData> newsData = newsDataRepository.findAllByImageUrlIsNullOrEmpty();
        log.info("이미지가 없는 뉴스 데이터 개수: {}", newsData.size());
        return newsData;
    }

    // 개별 뉴스의 제목과 설명으로 프롬프트 생성 (크기별 분기)
    private String createImagePrompt(String title, String description, boolean isPopular) {
        String sizeInstruction = isPopular ? "Create a high-quality, detailed thumbnail image at 500x324 resolution. " : "Create a simple thumbnail image at standard resolution. ";

        return String.format(sizeInstruction + "The image should match the mood and atmosphere of this news article. " + "I don't want the news content itself in the image, just a simple image that fits the general vibe.\n\n" + "News title: %s\n" + "News description: %s\n\n" + "Generate a clean, atmospheric image that complements this news topic. " + "Keep it simple and mood-appropriate. " + "No text, no letters, no Korean characters, no written content. " + "Just a simple visual that matches the general feeling of the article.", title, description);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createNewsImages() {

        // 쿼터 초과 상태 체크
        if (isQuotaExceeded()) {
            log.warn("API 쿼터 초과 상태 - 이미지 생성 스킵");
            return; // 즉시 종료
        }


        try {
            log.info("=== 배치 단위 뉴스 이미지 생성 시작 ===");

            Long totalCount = newsDataRepository.countNewsWithoutImages();
            if (totalCount == 0) {
                log.info("이미지 생성할 뉴스가 없습니다.");
                return;
            }

            final int BATCH_SIZE = 35; // 배치 사이즈 35개로 설정
            int totalBatches = (int) Math.ceil((double) totalCount / BATCH_SIZE);

            log.info("총 {}개 뉴스를 {}개 배치로 나누어 처리합니다. (배치 크기: {})", totalCount, totalBatches, BATCH_SIZE);

            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int offset = batchIndex * BATCH_SIZE;

                log.info("=== 배치 {}/{} 처리 시작 (offset: {}) ===", batchIndex + 1, totalBatches, offset);

                List<NewsData> batchNews = newsDataRepository.findNewsWithoutImagesByBatch(BATCH_SIZE, offset);

                if (batchNews.isEmpty()) {
                    log.info("배치 {}: 처리할 뉴스가 없습니다.", batchIndex + 1);
                    continue;
                }

                processBatch(batchNews, batchIndex + 1, totalBatches);
            }

            log.info("=== 모든 배치 뉴스 이미지 생성 완료 - 총 {}개 처리 ===", totalCount);

        } catch (Exception e) {
            log.error("배치 뉴스 이미지 생성 중 예외 발생", e);
        }
    }

    // 배치 단위로 뉴스 처리
    private void processBatch(List<NewsData> batchNews, int batchNumber, int totalBatches) {
        try {
            // 인기글과 일반글 분리
            List<NewsData> popularNews = new ArrayList<>();
            List<NewsData> regularNews = new ArrayList<>();

            for (NewsData news : batchNews) {
                if (news.getIsPopular() == 1) {
                    popularNews.add(news);
                } else {
                    regularNews.add(news);
                }
            }

            log.info("배치 {}/{}: 총 {}개 뉴스 (인기글: {}개, 일반글: {}개) 처리 시작", batchNumber, totalBatches, batchNews.size(), popularNews.size(), regularNews.size());

            ExecutorService executor = Executors.newFixedThreadPool(5);

            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                int delayCounter = 0;

                // 인기글 먼저 처리 (우선순위)
                for (NewsData news : popularNews) {
                    int delay = delayCounter * 2;
                    delayCounter++;

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(delay * 1000);
                            generateImageForNews(news, true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("배치 {} - 인기글 스레드 인터럽트: 뉴스 ID {}", batchNumber, news.getNewsId());
                        } catch (Exception e) {
                            log.error("배치 {} - 인기글 뉴스 ID {} 처리 실패", batchNumber, news.getNewsId(), e);
                        }
                    }, executor).exceptionally(throwable -> {
                        log.error("배치 {} - 인기글 CompletableFuture 예외", batchNumber, throwable);
                        return null;
                    });

                    futures.add(future);
                }

                // 일반글 처리
                for (NewsData news : regularNews) {
                    int delay = delayCounter * 2;
                    delayCounter++;

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(delay * 1000);
                            generateImageForNews(news, false);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("배치 {} - 일반글 스레드 인터럽트: 뉴스 ID {}", batchNumber, news.getNewsId());
                        } catch (Exception e) {
                            log.error("배치 {} - 일반글 뉴스 ID {} 처리 실패", batchNumber, news.getNewsId(), e);
                        }
                    }, executor).exceptionally(throwable -> {
                        log.error("배치 {} - 일반글 CompletableFuture 예외", batchNumber, throwable);
                        return null;
                    });

                    futures.add(future);
                }

                // 배치 내 모든 작업 완료 대기
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(180, TimeUnit.SECONDS)) { // 3분으로 연장
                    log.warn("배치 {} - Executor 정상 종료 실패, 강제 종료", batchNumber);
                    executor.shutdownNow();
                }
            }

            log.info("배치 {}/{} 처리 완료 - {}개 뉴스 처리됨", batchNumber, totalBatches, batchNews.size());

        } catch (Exception e) {
            log.error("배치 {} 처리 중 예외 발생", batchNumber, e);
        }
    }


    // 개별 뉴스에 대한 이미지 생성 (인기글 여부에 따른 크기 설정)
    private void generateImageForNews(NewsData news, boolean isPopular) {
        try {
            String newsType = isPopular ? "인기글" : "일반글";
            String sizeInfo = isPopular ? "500x324" : "기본 크기";

            log.info("뉴스 ID {} 이미지 생성 시작 ({}, {}): {}", news.getNewsId(), newsType, sizeInfo, news.getTitle());

            String imagenUrl = geminiQuestionProperties.getCreateImageUrl();
            String prompt = createImagePrompt(news.getTitle(), news.getDescription(), isPopular);

            // Imagen 4.0 API 요청 구조
            Map<String, Object> instance = new HashMap<>();
            instance.put("prompt", prompt);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("sampleCount", 1); // 이미지 1개 생성

            // 인기글인 경우 크기 파라미터 추가
            if (isPopular) {
                parameters.put("aspectRatio", "16:9"); // 500:324에 가까운 비율
                parameters.put("outputImageType", "HIGH_QUALITY");
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("instances", Arrays.asList(instance));
            requestBody.put("parameters", parameters);

            log.info("Imagen 4.0 API 요청 파라미터: prompt 길이={}, 타입={}", prompt.length(), newsType);

            // 재시도 로직 적용된 API 호출
            String response = callApiWithRetry(requestBody, imagenUrl, news.getNewsId());

            if (response != null) {
                processImagen4Response(response, news.getNewsId(), isPopular);
            } else {
                log.error("뉴스 ID {} 이미지 생성 최종 실패", news.getNewsId());
            }

        } catch (Exception e) {
            log.error("뉴스 ID {} 이미지 생성 실패", news.getNewsId(), e);
        }
    }

    private boolean isQuotaExceeded() {
        if (!apiQuotaExceeded) return false;

        // 다음날 자정에 리셋 체크
        if (quotaResetTime != null && LocalDateTime.now().isAfter(quotaResetTime)) {
            apiQuotaExceeded = false;
            quotaResetTime = null;
            log.info("API 쿼터 상태 리셋됨");
            return false;
        }
        return true;
    }


    // 재시도 로직이 포함된 API 호출 메서드
    private String callApiWithRetry(Map<String, Object> requestBody, String imagenUrl, Long newsId) {
        final int MAX_RETRIES = 3;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = restClient.post().uri(imagenUrl).header("x-goog-api-key", geminiQuestionProperties.getApiKey()).header("Content-Type", "application/json").body(requestBody).exchange((request, httpResponse) -> {
                    if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                        int status = httpResponse.getStatusCode().value();

                        // 429 쿼터 초과 체크를 먼저 처리
                        if (status == 429 || status == 500) {
                            apiQuotaExceeded = true;
                            quotaResetTime = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0);
                            log.error("API 쿼터 초과 감지 - 다음날까지 이미지 생성 중단");
                            return null; // 재시도 안함
                        }


                        // 재시도 가능한 에러 (429 Rate Limit, 5xx Server Errors)
                        if (status >= 501) {
                            throw new RuntimeException("Retryable error: " + status);
                        }

                        return null; // 재시도 불가능한 에러 (4xx)
                    }
                    try {
                        return new String(httpResponse.getBody().readAllBytes());
                    } catch (Exception e) {
                        throw new RuntimeException("Response parsing error", e);
                    }
                });

                if (response != null) {
                    log.info("뉴스 ID {} - API 성공 (시도 {})", newsId, attempt);
                    return response; // 성공
                }

            } catch (Exception e) {
                log.warn("뉴스 ID {} - API 시도 {}/{} 실패: {}", newsId, attempt, MAX_RETRIES, e.getMessage());
            }
        }

        log.error("뉴스 ID {} - 모든 재시도 실패", newsId);
        return null;
    }


    // 개별 뉴스 이미지 처리 (크기 정보 포함)
    private void processImagen4Response(String response, Long newsId, boolean isPopular) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(response);

            // Imagen 4.0 응답 구조: { "predictions": [{ "bytesBase64Encoded": "...", "mimeType": "..." }] }
            JsonNode predictions = rootNode.path("predictions");

            if (predictions.isEmpty()) {
                log.warn("뉴스 ID {} - Imagen 4.0 응답에 predictions가 없습니다.", newsId);
                return;
            }

            // 첫 번째 생성된 이미지 사용
            JsonNode firstPrediction = predictions.get(0);

            // Imagen 4.0의 정확한 응답 구조 확인 필요
            String base64Data = null;
            String mimeType = "image/png"; // 기본값

            // 가능한 응답 필드들 확인
            if (firstPrediction.has("bytesBase64Encoded")) {
                base64Data = firstPrediction.path("bytesBase64Encoded").asText();
            } else if (firstPrediction.has("image")) {
                base64Data = firstPrediction.path("image").asText();
            } else if (firstPrediction.has("data")) {
                base64Data = firstPrediction.path("data").asText();
            }

            if (firstPrediction.has("mimeType")) {
                mimeType = firstPrediction.path("mimeType").asText();
            }

            if (base64Data == null || base64Data.isEmpty()) {
                log.warn("뉴스 ID {} - Imagen 4.0 이미지 데이터가 비어있습니다.", newsId);
                return;
            }

            String newsType = isPopular ? "인기글" : "일반글";
            log.info("뉴스 ID {} - Imagen 4.0 이미지 발견 ({}): mimeType={}, 데이터 크기={} bytes", newsId, newsType, mimeType, base64Data.length());

            // Base64 이미지를 S3에 업로드
            String imageUrl = uploadBase64ImageToS3(base64Data, mimeType, isPopular);

            if (imageUrl != null) {
                updateNewsImageUrl(newsId, imageUrl);
                log.info("뉴스 ID {} - 이미지 URL 저장 완료 ({}): {}", newsId, newsType, imageUrl);
            } else {
                log.error("뉴스 ID {} - S3 업로드 실패", newsId);
            }

        } catch (Exception e) {
            log.error("뉴스 ID {} - Imagen 4.0 응답 처리 중 오류 발생", newsId, e);
        }
    }

    // NewsData의 imageUrl 업데이트
    @Transactional
    public void updateNewsImageUrl(Long newsId, String imageUrl) {
        try {
            newsDataRepository.updateImageUrl(newsId, imageUrl);
            log.info("뉴스 ID {} imageUrl 업데이트 완료", newsId);
        } catch (Exception e) {
            log.error("뉴스 ID {} imageUrl 업데이트 실패", newsId, e);
            throw e;
        }
    }

    // Base64 이미지를 S3에 업로드하는 메서드 (크기 정보 포함)
    private String uploadBase64ImageToS3(String base64Data, String mimeType, boolean isPopular) {
        try {
            // Base64를 MultipartFile로 변환
            MultipartFile multipartFile = convertBase64ToMultipartFile(base64Data, mimeType, isPopular);

            // S3에 업로드하고 URL 반환
            return s3ImageService.upload(multipartFile);

        } catch (Exception e) {
            log.error("Base64 이미지 S3 업로드 중 오류 발생", e);
            return null;
        }
    }

    // Base64를 MultipartFile로 변환하는 메서드 (파일명에 크기 정보 포함)
    private MultipartFile convertBase64ToMultipartFile(String base64Data, String mimeType, boolean isPopular) {
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        String extension = getExtensionFromMimeType(mimeType);
        String sizePrefix = isPopular ? "popular-500x324" : "regular";
        String filename = sizePrefix + "-news-image-" + UUID.randomUUID().toString().substring(0, 8) + extension;

        // Spring의 MockMultipartFile 사용
        return new MockMultipartFile("file",           // name
                filename,         // originalFilename
                mimeType,         // contentType
                imageBytes        // content
        );
    }

    // MIME 타입에서 파일 확장자 추출
    private String getExtensionFromMimeType(String mimeType) {
        switch (mimeType.toLowerCase()) {
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            default:
                return ".jpg"; // 기본값
        }
    }
}
