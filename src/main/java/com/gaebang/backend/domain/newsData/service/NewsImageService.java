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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

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

    // 이미지가 없는 뉴스 데이터만 조회
    public List<NewsData> getNewsWithoutImages() {
        List<NewsData> newsData = newsDataRepository.findAllByImageUrlIsNullOrEmpty();
        log.info("이미지가 없는 뉴스 데이터 개수: {}", newsData.size());
        return newsData;
    }

    // 개별 뉴스의 제목과 설명으로 프롬프트 생성
    private String createImagePrompt(String title, String description) {
        return String.format(
                "Create a clean, professional image related to this Korean news story. " +
                        "Important: NO TEXT, NO LETTERS, NO WORDS in the image at all.\n\n" +
                        "News: %s - %s\n\n" +
                        "Generate a photograph or illustration showing objects, people, or scenes " +
                        "that represent this news topic. No Korean text, no English text, " +
                        "no signs, no written content. Pure visual image only. " +
                        "Professional news media style.",
                title, description
        );
    }

    @Scheduled(cron = "*/30 * * * * *", zone = "Asia/Seoul") // 매일 새벽 2시
    @Transactional
    public void createNewsImage() {
        try {
            log.info("=== 개별 뉴스 이미지 생성 시작 ===");

            List<NewsData> newsWithoutImages = getNewsWithoutImages();
            if (newsWithoutImages.isEmpty()) {
                return;
            }

            int batchSize = Math.min(5, newsWithoutImages.size());
            List<NewsData> batchNews = newsWithoutImages.subList(0, batchSize);

            // 커스텀 스레드 풀 사용
            ExecutorService executor = Executors.newFixedThreadPool(3);

            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (int i = 0; i < batchNews.size(); i++) {
                    NewsData news = batchNews.get(i);
                    int delay = i * 2;

                    CompletableFuture<Void> future = CompletableFuture
                            .runAsync(() -> {
                                try {
                                    Thread.sleep(delay * 1000);
                                    generateImageForNews(news);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    log.warn("스레드 인터럽트: 뉴스 ID {}", news.getNewsId());
                                } catch (Exception e) {
                                    log.error("뉴스 ID {} 처리 실패", news.getNewsId(), e);
                                }
                            }, executor) // 커스텀 executor 사용
                            .exceptionally(throwable -> {
                                log.error("CompletableFuture 예외", throwable);
                                return null;
                            });

                    futures.add(future);
                }

                // 모든 작업 완료 대기
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            }

            log.info("=== 개별 뉴스 이미지 생성 완료 ===");

        } catch (Exception e) {
            log.error("뉴스 이미지 생성 중 예외 발생", e);
        }
    }

    // 개별 뉴스에 대한 이미지 생성
    private void generateImageForNews(NewsData news) {
        try {
            log.info("뉴스 ID {} 이미지 생성 시작: {}", news.getNewsId(), news.getTitle());

            String geminiUrl = geminiQuestionProperties.getCreateImageUrl();
            String prompt = createImagePrompt(news.getTitle(), news.getDescription());

            // Gemini API 요청 구조 (curl과 동일하게)
            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);

            Map<String, Object> content = new HashMap<>();
            content.put("parts", Arrays.asList(part));

            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("responseModalities", Arrays.asList("TEXT", "IMAGE")); // 수정됨!

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("contents", Arrays.asList(content));
            parameters.put("generationConfig", generationConfig);


            // API 호출
            String response = restClient.post()
                    .uri(geminiUrl)
                    .header("x-goog-api-key", geminiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, httpResponse) -> {
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            log.error("Gemini API 호출 실패: {}", httpResponse.getStatusCode());
                            try {
                                String errorBody = new String(httpResponse.getBody().readAllBytes());
                                log.error("오류 응답 본문: {}", errorBody);
                            } catch (Exception e) {
                                log.error("오류 응답 읽기 실패", e);
                            }
                            return null;
                        }
                        try {
                            return new String(httpResponse.getBody().readAllBytes());
                        } catch (Exception e) {
                            log.error("응답 파싱 오류", e);
                            return null;
                        }
                    });

            if (response != null) {
                processIndividualNewsImage(response, news.getNewsId());
            }

        } catch (Exception e) {
            log.error("뉴스 ID {} 이미지 생성 실패", news.getNewsId(), e);
        }
    }

    // 개별 뉴스 이미지 처리
    private void processIndividualNewsImage(String response, Long newsId) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(response);

            JsonNode candidates = rootNode.path("candidates");
            if (candidates.isEmpty()) {
                log.warn("뉴스 ID {} - Gemini 응답에 candidates가 없습니다.", newsId);
                return;
            }

            JsonNode content = candidates.get(0).path("content");
            JsonNode parts = content.path("parts");

            // parts 배열에서 이미지 데이터 찾기
            for (JsonNode part : parts) {
                if (part.has("inlineData")) {
                    JsonNode inlineData = part.path("inlineData");
                    String mimeType = inlineData.path("mimeType").asText();
                    String base64Data = inlineData.path("data").asText();

                    log.info("뉴스 ID {} - 이미지 발견: mimeType={}, 데이터 크기={} bytes",
                            newsId, mimeType, base64Data.length());

                    // Base64 이미지를 S3에 업로드
                    String imageUrl = uploadBase64ImageToS3(base64Data, mimeType);

                    if (imageUrl != null) {
                        // 데이터베이스에 이미지 URL 저장
                        updateNewsImageUrl(newsId, imageUrl);
                        log.info("뉴스 ID {} - 이미지 URL 저장 완료: {}", newsId, imageUrl);
                    } else {
                        log.error("뉴스 ID {} - S3 업로드 실패", newsId);
                    }
                    break; // 첫 번째 이미지만 처리
                }
            }

        } catch (Exception e) {
            log.error("뉴스 ID {} - 이미지 응답 처리 중 오류 발생", newsId, e);
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

    // Base64 이미지를 S3에 업로드하는 메서드
    private String uploadBase64ImageToS3(String base64Data, String mimeType) {
        try {
            // Base64를 MultipartFile로 변환
            MultipartFile multipartFile = convertBase64ToMultipartFile(base64Data, mimeType);

            // S3에 업로드하고 URL 반환
            return s3ImageService.upload(multipartFile);

        } catch (Exception e) {
            log.error("Base64 이미지 S3 업로드 중 오류 발생", e);
            return null;
        }
    }

    // Base64를 MultipartFile로 변환하는 메서드
    private MultipartFile convertBase64ToMultipartFile(String base64Data, String mimeType) {
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        String extension = getExtensionFromMimeType(mimeType);
        String filename = "news-image-" + UUID.randomUUID().toString().substring(0, 8) + extension;

        // Spring의 MockMultipartFile 사용
        return new MockMultipartFile(
                "file",           // name
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
