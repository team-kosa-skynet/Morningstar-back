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
    private final ObjectMapper objectMapper;

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

        return String.format(sizeInstruction + "The image should match the mood and atmosphere of this news article."
                + "I don't want the news content itself in the image, just a simple image that fits the general vibe.\n\n"
                + "News title: %s\n" + "News description: %s\n\n"
                + "Generate a clean, atmospheric image that complements this news topic. "
                + "Keep it simple and mood-appropriate. " + "No text, no letters, no Korean characters, no written content. "
                + "Just a simple visual that matches the general feeling of the article.", title, description);
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

            final int BATCH_SIZE = 15; // 배치 사이즈를 35→15로 축소
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

            log.info("배치 {}/{}: 총 {}개 뉴스 (인기글: {}개, 일반글: {}개) 처리 시작", batchNumber, totalBatches, batchNews.size(), popularNews.size(),
                    regularNews.size());

            ExecutorService executor = Executors.newFixedThreadPool(3); // 5→3으로 축소

            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                int delayCounter = 0;

                // 인기글 먼저 처리 (우선순위)
                for (NewsData news : popularNews) {
                    int delay = delayCounter * 8; // 2초→8초로 대폭 증가
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
                    int delay = delayCounter * 8; // 2초→8초로 대폭 증가
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
                if (!executor.awaitTermination(300, TimeUnit.SECONDS)) { // 3분→5분으로 연장
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
            // 먼저 쿼터 초과 상태 확인 - 초과 시 즉시 종료
            if (isQuotaExceeded()) {
                log.warn("뉴스 ID {} - API 쿼터 초과로 인해 이미지 생성 스킵", news.getNewsId());
                return;
            }

            String newsType = isPopular ? "인기글" : "일반글";
            String sizeInfo = isPopular ? "500x324" : "기본 크기";

            log.info("뉴스 ID {} 이미지 생성 시작 ({}, {}): {}", news.getNewsId(), newsType, sizeInfo, news.getTitle());

            String imagenUrl = geminiQuestionProperties.getCreateImageUrl();

            // API URL 및 키 검증 추가
            if (imagenUrl == null || imagenUrl.trim().isEmpty()) {
                log.error("뉴스 ID {} - Imagen API URL이 없습니다.", news.getNewsId());
                return;
            }

            if (geminiQuestionProperties.getApiKey() == null || geminiQuestionProperties.getApiKey().trim().isEmpty()) {
                log.error("뉴스 ID {} - API 키가 없습니다.", news.getNewsId());
                return;
            }

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

    public boolean isQuotaExceeded() {
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

    // 재시도 로직이 포함된 API 호출 메서드 - 수정된 부분
    private String callApiWithRetry(Map<String, Object> requestBody, String imagenUrl, Long newsId) {
        final int MAX_RETRIES = 3;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = restClient.post().uri(imagenUrl).header("x-goog-api-key", geminiQuestionProperties.getApiKey()).header("Content-Type",
                        "application/json").body(requestBody).exchange((request, httpResponse) -> {
                    if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                        int status = httpResponse.getStatusCode().value();

                        // 에러 응답 본문 먼저 읽기
                        String errorDetails = "응답 본문 없음";
                        try {
                            byte[] errorBodyBytes = httpResponse.getBody().readAllBytes();
                            if (errorBodyBytes.length > 0) {
                                errorDetails = new String(errorBodyBytes);
                            }
                        } catch (Exception bodyException) {
                            log.warn("뉴스 ID {} - 에러 응답 본문 읽기 실패: {}", newsId, bodyException.getMessage());
                        }

                        // 쿼터 초과 감지 - 429 또는 RESOURCE_EXHAUSTED 메시지
                        if (status == 429 || errorDetails.contains("RESOURCE_EXHAUSTED") || errorDetails.contains("exceeded your current quota")) {
                            apiQuotaExceeded = true;
                            quotaResetTime = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0);
                            log.error("API 쿼터 초과 감지 ({}) - 다음날까지 이미지 생성 중단", status == 429 ? "429" : "RESOURCE_EXHAUSTED");
                            return null; // 재시도 안함
                        }

                        // 500번대 에러는 재시도 가능한 서버 에러로 처리
                        if (status >= 500) {
                            log.error("뉴스 ID {} - 서버 에러 {} 상세 응답: {}", newsId, status, errorDetails);
                            log.warn("뉴스 ID {} - 서버 에러 {}, 재시도 예정", newsId, status);
                            throw new RuntimeException("Retryable server error: " + status);
                        }

                        // 400번대는 재시도 불가능한 클라이언트 에러
                        log.error("뉴스 ID {} - 클라이언트 에러 {} 상세 응답: {}", newsId, status, errorDetails);
                        log.error("뉴스 ID {} - 클라이언트 에러 {}, 재시도 안함", newsId, status);
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

                // 마지막 재시도가 아니면 잠깐 대기
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(2000 * attempt); // 2초, 4초, 6초 대기
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("뉴스 ID {} - 모든 재시도 실패", newsId);
        return null;
    }

    // 개별 뉴스 이미지 처리 (크기 정보 포함) - 수정된 부분
    private void processImagen4Response(String response, Long newsId, boolean isPopular) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);

            // Imagen 4.0 응답 구조 검증 강화
            JsonNode predictions = rootNode.path("predictions");

            if (!predictions.isArray()) {
                log.warn("뉴스 ID {} - predictions가 배열이 아닙니다: {}", newsId, predictions.getNodeType());
                return;
            }

            if (predictions.isEmpty()) {
                log.warn("뉴스 ID {} - Imagen 4.0 응답에 predictions가 비어있습니다.", newsId);
                return;
            }

            // 첫 번째 생성된 이미지 사용
            JsonNode firstPrediction = predictions.get(0);

            if (firstPrediction == null || firstPrediction.isNull()) {
                log.warn("뉴스 ID {} - 첫 번째 prediction이 null입니다.", newsId);
                return;
            }

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

            if (base64Data == null || base64Data.trim().isEmpty()) {
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

    // Base64 이미지를 S3에 업로드하는 메서드 (크기 정보 포함) - 수정된 부분
    private String uploadBase64ImageToS3(String base64Data, String mimeType, boolean isPopular) {
        try {
            // Base64를 MultipartFile로 변환
            MultipartFile multipartFile = convertBase64ToMultipartFile(base64Data, mimeType, isPopular);

            if (multipartFile == null) {
                log.error("Base64를 MultipartFile로 변환 실패");
                return null;
            }

            // S3에 업로드하고 URL 반환
            return s3ImageService.upload(multipartFile);

        } catch (Exception e) {
            log.error("Base64 이미지 S3 업로드 중 오류 발생", e);
            return null;
        }
    }

    // Base64를 MultipartFile로 변환하는 메서드 (파일명에 크기 정보 포함) - 수정된 부분
    private MultipartFile convertBase64ToMultipartFile(String base64Data, String mimeType, boolean isPopular) {
        try {
            // Base64 디코딩 시 예외 처리 강화
            if (base64Data == null || base64Data.trim().isEmpty()) {
                log.error("Base64 데이터가 비어있습니다.");
                return null;
            }

            // Base64 문자열 정리 (공백, 개행 제거)
            String cleanedBase64 = base64Data.replaceAll("\\s+", "");

            byte[] imageBytes = Base64.getDecoder().decode(cleanedBase64);

            if (imageBytes.length == 0) {
                log.error("디코딩된 이미지 바이트 배열이 비어있습니다.");
                return null;
            }

            String extension = getExtensionFromMimeType(mimeType);
            String sizePrefix = isPopular ? "popular-500x324" : "regular";
            String filename = sizePrefix + "-news-image-" + UUID.randomUUID().toString().substring(0, 8) + extension;

            // Spring의 MockMultipartFile 사용
            return new MockMultipartFile("file",           // name
                    filename,         // originalFilename
                    mimeType,         // contentType
                    imageBytes        // content
            );

        } catch (IllegalArgumentException e) {
            log.error("Base64 디코딩 실패 - 잘못된 Base64 형식: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Base64를 MultipartFile로 변환 중 예외 발생", e);
            return null;
        }
    }

    // MIME 타입에서 파일 확장자 추출
    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) {
            return ".jpg"; // 기본값
        }

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
