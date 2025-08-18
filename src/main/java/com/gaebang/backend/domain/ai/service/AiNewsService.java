package com.gaebang.backend.domain.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.ai.dto.AiNewsResponseDto;
import com.gaebang.backend.domain.ai.entity.AiNews;
import com.gaebang.backend.domain.ai.repository.AiNewsRepository;
import com.gaebang.backend.global.util.ResponseDTO;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiNewsService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final String naverClientId = dotenv.get("X_Naver_Client_Id");
    private static final String naverClientSecret = dotenv.get("X_Naver_Client_Secret");
    private static final String openaiApiKey = dotenv.get("OPENAI_API_KEY");

    private static final int newsCount = 5;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiNewsRepository repository;

    /**
     * 최신 AI 뉴스 원문 저장
     */
    public ResponseDTO<List<AiNewsResponseDto>> refreshLatestNews() throws Exception {
        String newsJson = searchAiNewsFromNaver();

        JsonNode root = objectMapper.readTree(newsJson);
        List<AiNewsResponseDto> savedNews = root.get("items")
                .findParents("title").stream()
                .map(node -> {
                    String title = node.get("title").asText().replaceAll("<[^>]*>", "");
                    String content = node.get("description").asText().replaceAll("<[^>]*>", "");

                    AiNews news = repository.save(
                            AiNews.builder()
                                    .title(title)
                                    .content(content)
                                    .summary("요약 생성전")
                                    .build()
                    );
                    return AiNewsResponseDto.fromEntity(news);
                })
                .collect(Collectors.toList());

        return ResponseDTO.okWithData(savedNews);
    }


    /**
     * 특정 뉴스 요약 (OpenAI 호출)
     */
    public ResponseDTO<AiNewsResponseDto> summarizeNews(Long id) throws Exception {
        AiNews news = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("뉴스를 찾을 수 없습니다. ID=" + id));

        if (news.getSummary() != null) {
            return ResponseDTO.okWithData(toDto(news));
        }

        String summary = summarizeToKorean(news.getTitle());
        news.setSummary(summary);
        repository.save(news);

        return ResponseDTO.okWithData(toDto(news));
    }

    /**
     * 저장된 뉴스 전체 이력 조회
     */
    public ResponseDTO<List<AiNewsResponseDto>> getNewsHistory() {
        List<AiNewsResponseDto> history = repository.findTop10ByOrderByCreatedAtDesc()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseDTO.okWithData(history);
    }

    private AiNewsResponseDto toDto(AiNews news) {
        return AiNewsResponseDto.fromEntity(news);
    }

    private String searchAiNewsFromNaver() throws Exception {
        String query = URLEncoder.encode("최근 AI 기술 개발 OR 제품 출시", StandardCharsets.UTF_8);
        String url = "https://openapi.naver.com/v1/search/news.json?query=" + query
                + "&display=" + newsCount
                + "&sort=date";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Naver-Client-Id", naverClientId)
                .header("X-Naver-Client-Secret", naverClientSecret)
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }

    private String summarizeToKorean(String content) throws Exception {
        String prompt = "다음 내용을 간결하게 한국어로 요약해줘:\n" + content;

        String requestBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("model", "gpt-4o-mini")
                        .set("messages", objectMapper.createArrayNode()
                                .add(objectMapper.createObjectNode()
                                        .put("role", "system")
                                        .put("content", "You are a helpful assistant."))
                                .add(objectMapper.createObjectNode()
                                        .put("role", "user")
                                        .put("content", prompt)))
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openaiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode jsonResponse = objectMapper.readTree(response.body());
        return jsonResponse
                .path("choices").get(0)
                .path("message")
                .path("content").asText();
    }
}
