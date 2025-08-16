package com.gaebang.backend.domain.newsData.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.newsData.entity.NewsData;
import com.gaebang.backend.domain.newsData.event.NewsCreatedEvent;
import com.gaebang.backend.domain.newsData.repository.NewsDataRepository;
import com.gaebang.backend.domain.question.openai.util.OpenaiQuestionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
    private final NewsImageService newsImageService;

    // 뉴스 전체 조회 한 것 가공하기
    public String getNewsData() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfYesterday = now.minusDays(1).toLocalDate().atStartOfDay();
        LocalDateTime endOfToday = now.toLocalDate().plusDays(1).atStartOfDay();

        List<NewsData> newsData = newsDataRepository.findNewsByDateRange(startOfYesterday, endOfToday);
//        List<NewsData> newsData = newsDataRepository.findTop40ByOrderByPubDateDesc();

        log.info("뉴스 데이터 개수 확인 ({}부터 {}까지): {}",
                startOfYesterday, endOfToday, newsData.size());

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
        return "SYSTEM: You are a Korean news deduplication expert.\n" +
                "\n" +
                "TASK:\n" +
                "1. Analyze ALL news articles to find groups reporting the SAME EVENT/STORY.\n" +
                "2. Look for groups of 2+ articles (mark 5+ as 'popular').\n" +
                "3. Focus on core elements: WHO did WHAT, WHEN, WHERE.\n" +
                "\n" +
                "DUPLICATE IDENTIFICATION RULES:\n" +
                "- Same entity (company/person/organization) + same action/event\n" +
                "- Same incident/announcement with same timeframe\n" +
                "- Different media outlets reporting identical news event\n" +
                "- Similar quotes from same spokesperson about same matter\n" +
                "\n" +
                "ANALYSIS METHOD:\n" +
                "1. Extract key entities: companies, people, organizations\n" +
                "2. Identify main action/event for each article\n" +
                "3. Group articles with matching entity+action combinations\n" +
                "4. Verify timing consistency (same day/week)\n" +
                "\n" +
                "CRITICAL JSON FORMATTING RULES:\n" +
                "- Output ONLY pure JSON, no markdown formatting\n" +
                "- Do NOT use ```json or ``` code blocks\n" +
                "- Start directly with { and end with }\n" +
                "- No explanatory text before or after JSON\n" +
                "- No comments or additional formatting\n" +
                "\n" +
                "OUTPUT FORMAT:\n" +
                "{\n" +
                "  \"duplicateGroups\": [\n" +
                "    {\n" +
                "      \"groupId\": 1,\n" +
                "      \"theme\": \"주요 사건 요약\",\n" +
                "      \"articles\": [\n" +
                "        {\n" +
                "          \"newsId\": 123,\n" +
                "          \"title\": \"article title\",\n" +
                "          \"description\": \"article description\",\n" +
                "          \"pubDate\": \"article date\"\n" +
                "        }\n" +
                "      ],\n" +
                "      \"articleCount\": 15,\n" +
                "      \"earlyPubDateNewsId\": 123,\n" +
                "      \"isPopular\": true\n" +
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
                "INPUT: [news list]";
    }

    // 이벤트 리스너 - 순차 실행
    @EventListener
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    public void handleNewsCreated(NewsCreatedEvent event) {
        try {
            log.info("뉴스 생성 이벤트 수신 - {}건의 새 뉴스", event.getNewsCount());

            // 1. 중복 분석 (우선)
            log.info("1단계: 중복 뉴스 분석 시작");
            getDuplatedNews();
            log.info("1단계: 중복 뉴스 분석 완료");

            // 2. 이미지 생성 (후순위)
            log.info("2단계: 이미지 생성 시작");
            newsImageService.createNewsImages();
            log.info("2단계: 이미지 생성 완료");

        } catch (Exception e) {
            log.error("뉴스 후속 처리 중 오류", e);
        }
    }

    // 중복된 기사 찾기
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void getDuplatedNews() {
        try {
            log.info("=== 중복 뉴스 탐지 시작 ===");

            String modelToUse = openaiQuestionProperties.getModelToUse("gpt-4o");
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

                        if (Thread.currentThread().isInterrupted()) {
                            log.warn("OpenAI Chat Completions API 스레드 인터럽트 감지 - API 호출 중단");
                            return null;
                        }

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

                        try {
                            String responseBody = new String(httpResponse.getBody().readAllBytes());
                            log.info("OpenAI API 응답 길이: {} 문자", responseBody.length());
                            return responseBody;
                        } catch (Exception e) {
                            log.error("응답 파싱 오류", e);
                            return null;
                        }
                    });

            if (response != null) {
                log.info("=== OpenAI API 응답 성공 ===");
                processDuplicateNews(response);
            } else {
                log.warn("=== OpenAI API 응답이 null ===");
            }

            log.info("=== 중복 뉴스 탐지 완료 ===");

        } catch (Exception e) {
            log.error("중복 뉴스 탐지 중 예외 발생", e);
        }
    }

    // 응답 파싱 메서드
    private void processDuplicateNews(String response) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(response);

            String contentJson = rootNode
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            log.info("추출된 content JSON: {}", contentJson);

            JsonNode contentNode = objectMapper.readTree(contentJson);
            JsonNode duplicateGroups = contentNode.path("duplicateGroups");

            for (JsonNode group : duplicateGroups) {
                int groupId = group.path("groupId").asInt();
                Long earlyPubDateNewsId = group.path("earlyPubDateNewsId").asLong();
                JsonNode articles = group.path("articles");

                log.info("=== 중복 그룹 {} 처리 시작 (기사 {}개) ===", groupId, articles.size());
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

                // DB 업데이트 로직
                if (earlyPubDateNewsId != null && allNewsIds.contains(earlyPubDateNewsId)) {

                    if (articles.size() >= 5) {
                        // 5개 이상: 인기글 처리 + 중복글 비활성화
                        log.info("  📊 5개 이상 그룹 → 인기글 처리 + 중복글 비활성화");

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
                        // 5개 미만: 인기글 처리 안함 + 중복글만 비활성화
                        log.info("  📝 5개 미만 그룹 → 중복글만 비활성화 (인기글 처리 안함)");

                        // 인기글 처리는 하지 않고, 중복글만 비활성화
                        for (Long newsId : allNewsIds) {
                            if (!newsId.equals(earlyPubDateNewsId)) {
                                newsDataRepository.markAsActive(newsId);
                                log.info("  ❌ 비활성화 처리: newsId = {}", newsId);
                            }
                        }
                        log.info("  ℹ️  가장 먼저 작성된 기사 유지 (인기글 아님): newsId = {}", earlyPubDateNewsId);
                    }

                } else {
                    log.warn("  ⚠️ earlyPubDateNewsId를 찾을 수 없거나 유효하지 않음");
                }

                log.info("=== 중복 그룹 {} 처리 완료 ===", groupId);
            }

            log.info("전체 중복 기사 처리 완료: 총 {}개 그룹 처리", duplicateGroups.size());

        } catch (Exception e) {
            log.error("중복 뉴스 응답 파싱 중 오류 발생", e);
        }
    }
}