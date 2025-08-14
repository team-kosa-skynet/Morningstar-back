package com.gaebang.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.ai.entity.AiUpdate;
import com.gaebang.backend.domain.ai.repository.AiUpdateRepository;
import com.gaebang.backend.global.util.ResponseDTO;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUpdatesService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final String GEMINI_API_KEY = dotenv.get("GEMINI_API_KEY");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiUpdateRepository repository;

    private static class NewsItem {
        String title;
        String link;
        String description;
        String pubDate;

        public NewsItem(String title, String link, String description, String pubDate) {
            this.title = title;
            this.link = link;
            this.description = description;
            this.pubDate = pubDate;
        }
    }

    public ResponseDTO<AiUpdate> getLatestAiUpdates() throws Exception {
        List<NewsItem> allNews = new ArrayList<>();

        // 각 RSS 피드를 개별적으로 처리하여 안정성 확보
        fetchAndAddNews(allNews, "https://openai.com/blog/rss.xml");
        fetchAndAddNews(allNews, "https://blog.google/rss/");
        fetchAndAddNews(allNews, "https://claude.ai/changelog/rss");

        // 모든 피드를 가져오는데 실패했는지 확인
        if (allNews.isEmpty()) {
            AiUpdate errorUpdate = new AiUpdate("업데이트 실패", "최신 AI 소식을 불러오는 데 실패했습니다. 모든 RSS 피드를 확인할 수 없습니다.");
            return ResponseDTO.okWithData(errorUpdate);
        }

        // 본문 크롤링
        for (NewsItem item : allNews) {
            item.description = fetchFullContent(item.link);
        }

        // Gemini API 호출
        String article = generateNewsArticle(allNews);

        // DB 저장
        AiUpdate saved = repository.save(new AiUpdate("최신 AI 업데이트 뉴스", article));

        return ResponseDTO.okWithData(saved);
    }

    private void fetchAndAddNews(List<NewsItem> newsList, String url) {
        try {
            newsList.addAll(fetchFromRss(url));
        } catch (Exception e) {
            // 특정 RSS 피드에서 에러가 발생하면 로그만 남기고 계속 진행
            System.err.println("Failed to fetch RSS feed from " + url + ": " + e.getMessage());
        }
    }

    private List<NewsItem> fetchFromRss(String rssUrl) throws Exception {
        List<NewsItem> newsList = new ArrayList<>();

        // Jsoup으로 연결하고 XML 문서를 받아옴
        Document doc = Jsoup.connect(rssUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36")
                .parser(Parser.xmlParser()) // XML 파서 사용을 명시
                .get();

        // Jsoup의 CSS 선택자를 사용해 'item' 태그들을 가져옴
        Elements items = doc.select("item");
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            Element item = items.get(i);

            // Jsoup 방식으로 태그 내용 추출
            String title = item.select("title").first().text();
            String link = item.select("link").first().text();
            String description = item.select("description").first().text();
            String pubDate = item.select("pubDate").first().text();

            newsList.add(new NewsItem(title, link, description, pubDate));
        }
        return newsList;
    }

    private String fetchFullContent(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/115.0 Safari/537.36")
                    .get();
            Element bodyElement = doc.body();

            Element mainContent = bodyElement.selectFirst("article, .content, .post-content, .post");
            if (mainContent != null) {
                return mainContent.text();
            } else {
                return bodyElement.text();
            }
        } catch (Exception e) {
            return "본문을 불러올 수 없습니다.";
        }
    }

    private String generateNewsArticle(List<NewsItem> items) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 최근 AI 기술 업데이트 소식입니다:\n");
        for (NewsItem item : items) {
            sb.append("- 제목: ").append(item.title).append("\n");
            sb.append("  날짜: ").append(item.pubDate).append("\n");
            sb.append("  내용: ").append(item.description).append("\n\n");
        }

        String prompt = "다음 AI 관련 최신 업데이트 내용을 참고해서, IT 전문 기자가 작성한 한국어 뉴스기사 형식으로 상세하고 흥미롭게 요약 정리해줘. 각 회사별 주요 업데이트를 명확히 구분해서 설명해줘.:\n\n" + sb;

        String requestBody = """
                {
                  "contents": [
                    {
                      "parts": [
                        {
                          "text": "%s"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(prompt.replace("\"", "\\\""));


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=" + GEMINI_API_KEY))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode jsonNode = objectMapper.readTree(response.body());
        return jsonNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
    }
}