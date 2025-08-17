package com.gaebang.backend.domain.newsData.service;

import com.gaebang.backend.domain.newsData.dto.response.NewsDataResponseDTO;
import com.gaebang.backend.domain.newsData.entity.NewsData;
import com.gaebang.backend.domain.newsData.repository.NewsDataRepository;
import com.gaebang.backend.domain.newsData.util.HtmlUtils;
import com.gaebang.backend.domain.newsData.util.HttpClientUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsDataService {

    private final PopularNewsDataService popularNewsDataService;
    private final HttpClientUtil httpClient;
    private final NewsDataRepository newsRepository;
    private static Dotenv dotenv = Dotenv.load();

    private String clientId = dotenv.get("X_Naver_Client_Id");
    private String clientSecret = dotenv.get("X_Naver_Client_Secret");

    private static final String NAVER_NEWS_API_URL = "https://openapi.naver.com/v1/search/news.json";
    private static final int DEFAULT_DISPLAY_COUNT = 100;

    // 뉴스 전체 조회
    public List<NewsDataResponseDTO> getNewsData() {

//        List<NewsData> newsData = newsRepository.findTop100ByOrderByPubDateDesc();
        List<NewsData> newsData = newsRepository.findAllActiveNewsOrderByPubDateDesc();

        return newsData.stream()
                .map(news -> NewsDataResponseDTO.fromEntity(news))
                .collect(Collectors.toList());
    }

    // 뉴스 데이터를 조회하고 DB에 저장
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul") // 5분마다 실행
//    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul") // 10분마다 실행
//    @Scheduled(cron = "*/30 * * * * *", zone = "Asia/Seoul") // 30초마다 실행
    @Transactional
    public void fetchAndSaveNews() {
        try {
            log.info("scheduled 실행 중");
            
            String response = getNewsApiResponse();
            List<NewsData> newsDataList = parseNewsResponse(response);

            // 중복 제거 (링크 기준)
            newsDataList = removeDuplicates(newsDataList);

            // 이미 존재하는 뉴스 필터링
            newsDataList = filterExistingNews(newsDataList);

            if (!newsDataList.isEmpty()) {
                // 배치 저장
                newsRepository.saveAll(newsDataList);
                log.info("뉴스 데이터 {}건 저장 완료", newsDataList.size());
                
                // 인기글과 중복글 가려내는 메서드 실행
                popularNewsDataService.getDuplatedNews();
            } else {
                log.info("저장할 새로운 뉴스가 없습니다.");
            }

        } catch (Exception e) {
            log.error("뉴스 데이터 처리 중 오류", e);
            throw new RuntimeException("뉴스 데이터 처리 실패", e);
        }
    }

    // API 응답 조회
    private String getNewsApiResponse() throws Exception {
        String encodedQuery = URLEncoder.encode("it+기술", StandardCharsets.UTF_8);
        String apiUrl = buildApiUrl(encodedQuery, DEFAULT_DISPLAY_COUNT, 1, "sim");
        Map<String, String> headers = buildHeaders();

        log.info("네이버 뉴스 API 호출 - 키워드: {}, 개수: {}, 시작: {}, 정렬: {}", "it", DEFAULT_DISPLAY_COUNT, 1, "sim");

        String response = httpClient.get(apiUrl, headers);
        log.info("네이버 뉴스 API 응답 수신 완료");

        return response;
    }

    // JSON 응답을 NewsData 엔티티 리스트로 변환
    private List<NewsData> parseNewsResponse(String response) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(response);

        List<NewsData> newsDataList = new ArrayList<>();
        JsonNode items = jsonNode.get("items");

        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                try {
                    NewsData newsData = createNewsDataFromJson(item);
                    newsDataList.add(newsData);
                } catch (Exception e) {
                    log.warn("뉴스 아이템 파싱 중 오류 - 해당 아이템 건너뜀: {}", e.getMessage());
                }
            }
        }

        return newsDataList;
    }

    // JSON 아이템을 NewsData 엔티티로 변환
    private NewsData createNewsDataFromJson(JsonNode item) {
        NewsData newsData = NewsData.builder()
                .title(HtmlUtils.cleanText(item.get("title").asText()))
                .description(HtmlUtils.cleanText(item.get("description").asText()))
                .link(item.get("link").asText())
                .originalLink(item.get("originallink").asText())
                .build();

        // pubDate 설정
        String pubDateStr = item.get("pubDate").asText();
        if (pubDateStr != null && !pubDateStr.isEmpty()) {
            newsData.setPubDateFromString(pubDateStr);
        }

        return newsData;
    }

    // 중복 제거 (링크 기준)
    private List<NewsData> removeDuplicates(List<NewsData> newsDataList) {
        return newsDataList.stream()
                .collect(Collectors.toMap(
                        NewsData::getLink,
                        Function.identity(),
                        (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    // 이미 DB에 존재하는 뉴스 필터링
    private List<NewsData> filterExistingNews(List<NewsData> newsList) {
        return newsList.stream()
                .filter(news -> {
                    Long count = newsRepository.countExistingByLink(news.getLink());
                    return count == 0; // 0이면 존재하지 않음
                })
                .collect(Collectors.toList());
    }

    // API URL을 생성 (정렬 옵션 포함)
    private String buildApiUrl(String encodedQuery, int display, int start, String sort) {
        return String.format("%s?query=%s&display=%d&start=%d&sort=%s",
                NAVER_NEWS_API_URL, encodedQuery, display, start, sort);
    }

    // header 생성
    private Map<String, String> buildHeaders() {
        return Map.of(
                "X-Naver-Client-Id", clientId,
                "X-Naver-Client-Secret", clientSecret
        );
    }
}