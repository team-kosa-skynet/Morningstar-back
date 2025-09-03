package com.gaebang.backend.domain.newsData.service;

import com.gaebang.backend.domain.newsData.entity.NewsData;
import com.gaebang.backend.domain.newsData.repository.NewsDataRepository;
import com.gaebang.backend.global.util.S3.S3ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    private final NewsDataRepository newsDataRepository;
    private final S3ImageService s3ImageService;
    private final ImageGenerationCircuitBreakerService imageGenerationService;

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

        try {

            Long totalCount = newsDataRepository.countNewsWithoutImages();
            if (totalCount == 0) {
                log.info("이미지 생성할 뉴스가 없습니다.");
                return;
            }

            final int BATCH_SIZE = 15; // 배치 사이즈를 35→15로 축소
            int totalBatches = (int) Math.ceil((double) totalCount / BATCH_SIZE);

            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int offset = batchIndex * BATCH_SIZE;

                List<NewsData> batchNews = newsDataRepository.findNewsWithoutImagesByBatch(BATCH_SIZE, offset);

                if (batchNews.isEmpty()) {
                    log.info("배치 {}: 처리할 뉴스가 없습니다.", batchIndex + 1);
                    continue;
                }

                processBatch(batchNews, batchIndex + 1, totalBatches);
            }

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

            ExecutorService executor = Executors.newFixedThreadPool(3);

            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                int delayCounter = 0;

                // 인기글 먼저 처리 (우선순위)
                for (NewsData news : popularNews) {
                    int delay = delayCounter * 8;
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
                    int delay = delayCounter * 8;
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
                if (!executor.awaitTermination(300, TimeUnit.SECONDS)) {
                    log.warn("배치 {} - Executor 정상 종료 실패, 강제 종료", batchNumber);
                    executor.shutdownNow();
                }
            }

        } catch (Exception e) {
            log.error("배치 {} 처리 중 예외 발생", batchNumber, e);
        }
    }

    // 개별 뉴스에 대한 이미지 생성 (외부 서비스로 위임)
    private void generateImageForNews(NewsData news, boolean isPopular) {
        try {
            log.info("뉴스 ID {} - 이미지 생성 시작 ({})", news.getNewsId(), isPopular ? "인기글" : "일반글");
            
            String prompt = createImagePrompt(news.getTitle(), news.getDescription(), isPopular);
            
            // 이미지 생성을 CircuitBreakerService에 위임
            String base64Data = imageGenerationService.generateImageWithFallback(prompt, isPopular, news.getNewsId());
            
            // Base64 데이터를 S3에 업로드
            String imageUrl = uploadBase64ImageToS3(base64Data, "image/png", isPopular);
            
            if (imageUrl != null) {
                updateNewsImageUrl(news.getNewsId(), imageUrl);
                log.info("뉴스 ID {} - 이미지 생성 및 S3 업로드 완료", news.getNewsId());
            } else {
                log.error("뉴스 ID {} - S3 업로드 실패", news.getNewsId());
            }

        } catch (Exception e) {
            log.error("뉴스 ID {} - 이미지 생성 실패: {}", news.getNewsId(), e.getMessage());
            // 개별 뉴스 이미지 생성 실패는 전체 배치를 중단시키지 않도록 예외를 삼킴
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
