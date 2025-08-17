package com.gaebang.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.ai.dto.OpenAiImageRequest;
import com.gaebang.backend.domain.ai.entity.AiUpdate;
import com.gaebang.backend.domain.ai.exception.AINewsIsNotGeneratedException;
import com.gaebang.backend.domain.ai.repository.AiUpdateRepository;
import com.gaebang.backend.global.util.ResponseDTO;
import com.gaebang.backend.global.util.S3.Base64DecodedMultipartFile;
import com.gaebang.backend.global.util.S3.S3ImageService;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUpdatesService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final String GEMINI_API_KEY = dotenv.get("GEMINI_API_KEY");
    private static final String STABILITY_API_KEY = dotenv.get("STABILITY_API_KEY");
    private static final String OPENAI_API_KEY = dotenv.get("OPENAI_API_KEY");
    String DEEPAI_API_KEY = dotenv.get("DEEPAI_API_KEY");

    private final S3ImageService s3ImageService;

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

        fetchAndAddNews(allNews, "https://openai.com/blog/rss.xml");
        fetchAndAddNews(allNews, "https://blog.google/rss/");

        if (allNews.isEmpty()) {
            throw new AINewsIsNotGeneratedException();
        }

        for (NewsItem item : allNews) {
            item.description = fetchFullContent(item.link);
        }

        // 1. 기사 생성
        String article = generateNewsArticle(allNews);

        // 2. 제목 생성
        String title = generateNewsTitle(article);

        // 3. 기사 본문을 기반으로 이미지 생성을 위한 '영어' 프롬프트 생성
        String imagePrompt = generateImagePromptFromArticle(article);

        // 4. 생성된 프롬프트로 이미지 생성
        String imageUrl = generateImageWithDalle3(imagePrompt);

        // 5. 저장
        AiUpdate saved = repository.save(
                AiUpdate.builder()
                        .title(title)
                        .content(article)
                        .imageUrl(imageUrl)
                        .build()
        );

        return ResponseDTO.okWithData(saved);
    }

    private void fetchAndAddNews(List<NewsItem> newsList, String url) {
        try {
            newsList.addAll(fetchFromRss(url));
        } catch (Exception e) {
            System.err.println("Failed to fetch RSS feed from " + url + ": " + e.getMessage());
        }
    }

    private List<NewsItem> fetchFromRss(String rssUrl) throws Exception {
        List<NewsItem> newsList = new ArrayList<>();
        Document doc = Jsoup.connect(rssUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36")
                .parser(Parser.xmlParser())
                .get();
        Elements items = doc.select("item");
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            Element item = items.get(i);
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
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36")
                    .get();
            Element mainContent = doc.body().selectFirst("article, .content, .post-content, .post");
            return (mainContent != null) ? mainContent.text() : doc.body().text();
        } catch (Exception e) {
            return "본문을 불러올 수 없습니다.";
        }
    }

    private String generateNewsArticle(List<NewsItem> items) throws Exception {
        StringBuilder sb = new StringBuilder("다음은 최근 AI 기술 업데이트 소식입니다:\n");
        for (NewsItem item : items) {
            sb.append("- 제목: ").append(item.title).append("\n");
            sb.append("  날짜: ").append(item.pubDate).append("\n");
            sb.append("  내용: ").append(item.description).append("\n\n");
        }
        String prompt = "다음 AI 관련 최신 업데이트 내용을 참고해서, IT 전문 기자가 작성한 한국어 뉴스기사 형식으로 상세하고 흥미롭게 요약 정리해줘:\n\n" + sb;
        return callGemini(prompt);
    }

    private String generateNewsTitle(String articleText) throws Exception {
        String prompt = "다음은 AI 관련 최신 뉴스를 다룬 기사입니다. 이 기사에 어울리는 간결하고 주목을 끌 수 있는 한국어 제목을 하나만 정해줘:\n\n" + articleText;
        return callGemini(prompt);
    }

    // 중복되는 Gemini 호출 로직을 위한 헬퍼 메소드
    private String callGemini(String prompt) throws Exception {
        String requestBody = """
        {
          "contents": [{"parts": [{"text": "%s"}]}]
        }
        """.formatted(prompt.replace("\"", "\\\""));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=" + GEMINI_API_KEY))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gemini API 요청 실패: " + response.body());
        }

        JsonNode jsonNode = objectMapper.readTree(response.body());
        JsonNode candidates = jsonNode.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = jsonNode.path("promptFeedback").path("blockReason").asText("알 수 없는 이유");
            throw new IllegalStateException("Gemini 응답 생성 실패: 후보가 없습니다. 차단 이유: " + blockReason);
        }
        return candidates.get(0).path("content").path("parts").get(0).path("text").asText();
    }

    // 기사 본문으로 이미지 프롬프트를 생성하는 메소드
    private String generateImagePromptFromArticle(String articleText) throws Exception {
        String prompt = "다음은 AI 뉴스 기사 전문이야. 이 기사의 핵심 내용을 가장 잘 나타내는, 상징적이고 멋진 이미지를 생성할 수 있도록 짧고 시각적인 영어 프롬프트를 만들어줘 (예: A futuristic cityscape with glowing data streams, symbolizing the rapid advancement of AI technology):\n\n" + articleText;
        // Gemini를 재사용하여 프롬프트를 생성
        return callGemini(prompt);
    }

    // 이미지를 생성하고 S3에 업로드한 뒤, 그 URL을 반환하는 메소드
    private String generateImageWithStability(String promptText) throws Exception {
        String boundary = "Boundary-" + System.currentTimeMillis();
        String multipartBody = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n" +
                promptText + "\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"output_format\"\r\n\r\n" +
                "jpeg" + "\r\n" +
                "--" + boundary + "--";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stability.ai/v2beta/stable-image/generate/sd3"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + STABILITY_API_KEY)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(multipartBody))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("Stability 이미지 생성 실패: " + response.body());
            return "https://via.placeholder.com/512.png?text=Image+Gen+Failed";
        }

        JsonNode jsonNode = objectMapper.readTree(response.body());
        String base64Image = jsonNode.path("image").asText();

        if (base64Image.isEmpty()) {
            System.err.println("응답에 이미지 데이터가 없습니다: " + response.body());
            return "https://via.placeholder.com/512.png?text=No+Image+Data";
        }

        MultipartFile imageFile = new Base64DecodedMultipartFile(base64Image, "image/jpeg");
        String s3ImageUrl = s3ImageService.upload(imageFile);
        return s3ImageUrl;
    }

    private String generateImageWithDeepAI(String promptText) throws Exception {
        String requestBody = "text=" + URLEncoder.encode(promptText, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepai.org/api/text2img"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("api-key", DEEPAI_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("DeepAI 이미지 생성 실패: " + response.body());
            return null;
        }

        JsonNode jsonNode = objectMapper.readTree(response.body());
        return jsonNode.path("output_url").asText();
    }

    public String generateImageWithDalle3(String promptText) throws Exception {

        OpenAiImageRequest payload = new OpenAiImageRequest(
                "dall-e-3",
                promptText,
                1,
                "1024x1024",
                "standard"
        );

        String requestBody = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/images/generations"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("OpenAI 이미지 생성 실패: " + response.body());
            return null;
        }

        JsonNode jsonNode = objectMapper.readTree(response.body());
        return jsonNode.path("data").get(0).path("url").asText();
    }
}