package com.gaebang.backend.domain.interviewTurn.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interviewTurn.util.PlanParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "openai")
public class OpenAiInterviewerAiGateway implements InterviewerAiGateway {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final PlanParser planParser;
    private final ObjectMapper om;

    public OpenAiInterviewerAiGateway(
            WebClient.Builder builder,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
            PlanParser planParser,
            ObjectMapper objectMapper
    ) {
        this.webClient = builder.baseUrl("https://api.openai.com/v1").build();
        this.apiKey = apiKey;
        this.model = model;
        this.planParser = planParser;
        this.om = objectMapper;
    }

    @Override
    public String generateGreeting(String displayName) {
        return displayName + "님, 안녕하세요. 이번 면접을 진행할 면접관입니다. 만나서 반갑습니다. 질문을 시작하겠습니다.";
    }

    @Override
    public Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates) {
        Map<String, Object> q0 = Map.of("idx", 0, "type", "BEHAVIORAL", "text", "자기소개를 간단히 해주세요.");
        Map<String, Object> q1 = Map.of("idx", 1, "type", "BEHAVIORAL", "text", "최근 협업 갈등을 STAR로 설명해 주세요.");
        Map<String, Object> q2 = Map.of("idx", 2, "type", "SYSTEM_DESIGN", "text", "피크 트래픽 10배에서 확장 전략은?");
        Map<String, Object> q3 = Map.of("idx", 3, "type", "DB_TX", "text", "트랜잭션 격리수준과 JPA의 관계를 설명해 주세요.");
        Map<String, Object> q4 = Map.of("idx", 4, "type", "PERF", "text", "N+1 문제를 어떻게 탐지/해결했나요?");
        Map<String, Object> q5 = Map.of("idx", 5, "type", "SECURITY_TEST", "text", "인증/인가 설계를 요약해 주세요.");
        Map<String, Object> q6 = Map.of("idx", 6, "type", "SYSTEM_DESIGN", "text", "캐시 일관성과 무효화 전략은?");
        Map<String, Object> q7 = Map.of("idx", 7, "type", "TROUBLESHOOT", "text", "최근 장애 RCA를 설명해 주세요.");
        Map<String, Object> q8 = Map.of("idx", 8, "type", "PERF", "text", "성능 측정과 검증 방법은?");
        Map<String, Object> q9 = Map.of("idx", 9, "type", "WRAPUP", "text", "질문 있으신가요? 마지막으로 강조하고 싶은 점은?");

        return Map.of("questions", List.of(q0, q1, q2, q3, q4, q5, q6, q7, q8, q9));

        // throw new UnsupportedOperationException("generatePlan: later");
    }

    @Override
    public AiTurnFeedbackDto nextTurn(String planJson, int questionIndex,
                                      String transcript, String recentSummaryJson,
                                      String previousResponseId) throws Exception {

        PlanQuestionDto q = planParser.getQuestionByIndex(planJson, questionIndex);

        // === JSON Schema for Structured Outputs ===
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "coachingTips", Map.of("type", "string"),
                        "scoreDelta", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "integer")
                        )
                ),
                "required", List.of("coachingTips", "scoreDelta"),
                "additionalProperties", false
        );
        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of("name", "AiTurnFeedback", "schema", schema)
        );

        String prompt = """
            당신은 모의면접 코치입니다. 아래 정보를 바탕으로 간단 코칭과 지표별 증감치를 반환하세요.
            - 질문유형: %s
            - 질문: %s
            - 후보자 답변: %s

            규칙:
            1) coachingTips: 1~2문장 한국어
            2) scoreDelta: {clarity, structure_STAR, tech_depth, tradeoff, root_cause} 중 해당 키만 -2..+3 정수
            3) 반드시 지정된 JSON 스키마로 출력
        """.formatted(q.type(), q.text(), transcript);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", prompt);
        body.put("response_format", responseFormat);
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            body.put("previous_response_id", previousResponseId);  // ← 얇은 컨텍스트 체인
        }

        String raw = webClient.post()
                .uri("/responses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode root = om.readTree(raw);
        String responseId = root.path("id").asText(null);

        // 구조화 출력 우선
        JsonNode parsed = root.path("output_parsed");
        if (parsed != null && !parsed.isMissingNode() && !parsed.isNull()) {
            String tips = parsed.path("coachingTips").asText("핵심부터 1~2문장으로.");
            Map<String, Integer> delta = om.convertValue(
                    parsed.path("scoreDelta"),
                    om.getTypeFactory().constructMapType(Map.class, String.class, Integer.class)
            );
            return new AiTurnFeedbackDto(tips, delta, responseId);
        }

        // 폴백: 텍스트
        String text = root.path("output_text").asText("");
        Map<String, Integer> delta = Map.of("clarity", 1);
        return new AiTurnFeedbackDto(text.isBlank() ? "핵심부터 1~2문장으로." : text, delta, responseId);
    }

    @Override
    public Map<String, Object> finalizeReport(String sessionJson) {
        throw new UnsupportedOperationException("finalizeReport: later");
    }
}
