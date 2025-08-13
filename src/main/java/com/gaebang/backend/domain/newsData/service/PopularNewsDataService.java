package com.gaebang.backend.domain.newsData.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.newsData.entity.NewsData;
import com.gaebang.backend.domain.newsData.repository.NewsDataRepository;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PopularNewsDataService {

    private final RestClient restClient;
    private final OpenaiQuestionProperties openaiQuestionProperties;
    private final NewsDataRepository newsDataRepository;

    // 뉴스 전체 조회 한 것 가공하기
    public String getNewsData() {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfYesterday = now.minusDays(1).toLocalDate().atStartOfDay();
        LocalDateTime endOfYesterday = now.toLocalDate().atStartOfDay();

        List<NewsData> newsData = newsDataRepository.findNewsByDateRange(startOfYesterday, endOfYesterday);

        log.info("뉴스 데이터 개수 확인 맨 처음: {}", newsDataRepository.findNewsByDateRange(startOfYesterday, endOfYesterday).size());

        StringBuilder data = new StringBuilder();
        data.append("[\n");

        for (int i = 0; i < newsData.size(); i++) {
            Long newsId = newsData.get(i).getNewsId();
            String title = escapeJsonString(newsData.get(i).getTitle());
            String description = escapeJsonString(newsData.get(i).getDescription());

            data.append(String.format("  {\n" +
                    "    \"newsId\": %d,\n" +
                    "    \"title\": \"%s\",\n" +
                    "    \"description\": \"%s\"\n" +
                    "  }", newsId, title, description));

            // 마지막 요소가 아니면 쉼표 추가
            if (i < newsData.size() - 1) {
                data.append(",");
            }
            data.append("\n");
        }

        data.append("]");
        return data.toString();
    }

    // JSON 문자열 이스케이프 처리
    private String escapeJsonString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // content 작성하기
    private static String writeSystem() {
        return "SYSTEM: You are a news deduplication expert that identifies ALL duplicate groups with complete details.\n" +
                "\n" +
                "TASK: \n" +
                "1. Find ALL groups of 5+ articles with 80%+ title+description similarity\n" +
                "2. Include ALL articles in each duplicate group\n" +
                "3. Identify the earliest article (by pubDate) in each group\n" +
                "\n" +
                "OUTPUT FORMAT (VALID JSON ONLY):\n" +
                "{\n" +
                "  \"duplicateGroups\": [\n" +
                "    {\n" +
                "      \"groupId\": 1,\n" +
                "      \"articles\": [\n" +
                "        {\n" +
                "          \"newsId\": 123,\n" +
                "          \"title\": \"article title\",\n" +
                "          \"description\": \"article description\",\n" +
                "          \"pubDate\": \"article date\"\n" +
                "        },\n" +
                "        {\n" +
                "          \"newsId\": 124,\n" +
                "          \"title\": \"article title\",\n" +
                "          \"description\": \"article description\",\n" +
                "          \"pubDate\": \"article date\"\n" +
                "        }\n" +
                "      ],\n" +
                "      \"earlyPubDateNewsId\": 123\n" +
                "    },\n" +
                "    {\n" +
                "      \"groupId\": 2,\n" +
                "      \"articles\": [\n" +
                "        {\n" +
                "          \"newsId\": 456,\n" +
                "          \"title\": \"article title\",\n" +
                "          \"description\": \"article description\",\n" +
                "          \"pubDate\": \"article date\"\n" +
                "        }\n" +
                "      ],\n" +
                "      \"earlyPubDateNewsId\": 456\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "\n" +
                "If no duplicates:\n" +
                "{\n" +
                "  \"duplicateGroups\": [],\n" +
                "  \"message\": \"중복 기사 없음\"\n" +
                "}\n" +
                "\n" +
                "CRITICAL: Output ONLY valid JSON. Do not include comments, explanations, or invalid array structures.\n" +
                "\n" +
                "INPUT: [news list]";
    }

    // 중복된 기사 찾기 (로그 확인용)
//    @Scheduled(cron = "*/30 * * * * *", zone = "Asia/Seoul")
    @Transactional
    public void getDuplatedNews() {
        try {
            log.info("=== 중복 뉴스 탐지 스케줄러 시작 ===");

            String modelToUse = openaiQuestionProperties.getDefaultModel();
            String openaiUrl = openaiQuestionProperties.getResponseUrl();
            log.info("사용 모델: {}, API URL: {}", modelToUse, openaiUrl);

            String promptSystem = writeSystem();
            String data = getNewsData();
            String content = promptSystem + "\n\nNews Data:\n" + data;

            log.info("전송할 프롬프트 길이: {} 문자", content.length());

            // OpenAI API 요청 구조
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", content);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("model", modelToUse);
            parameters.put("messages", Arrays.asList(message));
            parameters.put("max_tokens", 4000);
            parameters.put("temperature", 0);

            log.info("OpenAI API 요청 파라미터: {}", parameters.keySet());
            log.info("OpenAI API 호출 시작...");

            String response = restClient.post()
                    .uri(openaiUrl)
                    .header("Authorization", "Bearer " + openaiQuestionProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(parameters)
                    .exchange((request, httpResponse) -> {
                        log.info("OpenAI API 응답 상태 코드: {}", httpResponse.getStatusCode());

                        // 취소 신호 확인
                        if (Thread.currentThread().isInterrupted()) {
                            log.warn("OpenAI Chat Completions API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

                        // HTTP 상태 코드 검증
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            log.error("OpenAI Chat Completions API 호출 실패: {}", httpResponse.getStatusCode());
                            try {
                                String errorBody = new String(httpResponse.getBody().readAllBytes());
                                log.error("오류 응답 본문: {}", errorBody);
                            } catch (Exception e) {
                                log.error("오류 응답 읽기 실패", e);
                            }
                            return null;
                        }

                        // 응답 본문을 문자열로 변환
                        try {
                            String responseBody = new String(httpResponse.getBody().readAllBytes());
                            log.info("OpenAI API 응답 길이: {} 문자", responseBody.length());
                            log.info("OpenAI API 전체 응답: {}", responseBody);
                            return responseBody;
                        } catch (Exception e) {
                            log.error("응답 파싱 오류", e);
                            return null;
                        }
                    });

            if (response != null) {
                log.info("=== OpenAI API 응답 성공 ===");
                log.info("응답 내용: {}", response);

                processDuplicateNews(response);
            } else {
                log.warn("=== OpenAI API 응답이 null ===");
            }

            log.info("=== 중복 뉴스 탐지 스케줄러 종료 ===");

        } catch (Exception e) {
            log.error("중복 뉴스 탐지 중 예외 발생", e);
            log.error("예외 메시지: {}", e.getMessage());
            log.error("예외 클래스: {}", e.getClass().getSimpleName());
        }
    }

    // 응답 파싱 메서드 추가
    private void processDuplicateNews(String response) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            // 1. 전체 OpenAI 응답 파싱
            JsonNode rootNode = objectMapper.readTree(response);

            // 2. choices[0].message.content 에서 실제 중복 기사 JSON 추출
            String contentJson = rootNode
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            log.info("추출된 content JSON: {}", contentJson);

            // 3. content 내부의 JSON 파싱
            JsonNode contentNode = objectMapper.readTree(contentJson);
            JsonNode duplicateGroups = contentNode.path("duplicateGroups");

            // 4. 각 중복 그룹 처리
            for (JsonNode group : duplicateGroups) {
                int groupId = group.path("groupId").asInt();
                Long earlyPubDateNewsId = group.path("earlyPubDateNewsId").asLong();
                JsonNode articles = group.path("articles");

                log.info("=== 중복 그룹 {} 처리 시작 ===", groupId);
                log.info("  - 가장 먼저 작성된 기사: newsId={}", earlyPubDateNewsId);

                List<Long> allNewsIds = new ArrayList<>();

                // 모든 기사 정보 수집
                for (JsonNode article : articles) {
                    Long newsId = article.path("newsId").asLong();
                    String title = article.path("title").asText();
                    allNewsIds.add(newsId);

                    log.info("  - 중복 기사: newsId={}, title={}", newsId, title);
                }

                log.info("  - 그룹 내 모든 기사: {}", allNewsIds);

                // 5. 데이터베이스 업데이트
                if (earlyPubDateNewsId != null && allNewsIds.contains(earlyPubDateNewsId)) {
                    // 가장 먼저 작성된 기사를 인기 기사로 설정
                    newsDataRepository.markAsPopular(earlyPubDateNewsId);
                    log.info("  ✅ 인기 기사로 설정: newsId = {}", earlyPubDateNewsId);

                    // 나머지 중복 기사들을 비활성화
                    for (Long newsId : allNewsIds) {
                        if (!newsId.equals(earlyPubDateNewsId)) {
                            newsDataRepository.markAsActive(newsId);
                            log.info("  ❌ 비활성화 처리: newsId = {}", newsId);
                        }
                    }
                } else {
                    log.warn("  ⚠️ earlyPubDateNewsId를 찾을 수 없거나 유효하지 않음");
                }

                log.info("=== 중복 그룹 {} 처리 완료 ===", groupId);
            }

            log.info("전체 중복 기사 처리 완료: 총 {}개 그룹 처리", duplicateGroups.size());

        } catch (Exception e) {
            log.error("중복 뉴스 응답 파싱 중 오류 발생", e);
            e.printStackTrace();
        }
    }
}