package com.gaebang.backend.domain.interviewTurn.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interviewTurn.util.PlanParser;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

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

    @PostConstruct
    void log() {
        System.out.println("[AI] Using OpenAiInterviewerAiGateway");
    }

    @Override
    public String generateGreeting(String displayName) {
        return displayName + "님, 안녕하세요. 이번 면접을 진행할 면접관입니다. 만나서 반갑습니다. 질문을 시작하겠습니다.";
    }

    @Override
    public Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates) {
        if (candidates != null && !candidates.isEmpty()) {
            // idx가 겹치지 않도록 0..N 재부여
            List<Map<String,Object>> normalized = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                Map<String,Object> c = candidates.get(i);
                normalized.add(Map.of(
                        "idx", i,
                        "type", String.valueOf(c.get("type")),
                        "text", String.valueOf(c.get("text"))
                ));
            }
            return Map.of("questions", normalized);
        }

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

        // JSON Schema (strict + 키 고정)
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "coachingTips", Map.of("type", "string"),
                        "scoreDelta", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "clarity", Map.of("type", "integer", "minimum", -2, "maximum", 3, "default", 0),
                                        "structure_STAR", Map.of("type", "integer", "minimum", -2, "maximum", 3, "default", 0),
                                        "tech_depth", Map.of("type", "integer", "minimum", -2, "maximum", 3, "default", 0),
                                        "tradeoff", Map.of("type", "integer", "minimum", -2, "maximum", 3, "default", 0),
                                        "root_cause", Map.of("type", "integer", "minimum", -2, "maximum", 3, "default", 0)
                                ),
                                "required", List.of("clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause"),
                                "additionalProperties", false
                        )
                ),
                "required", List.of("coachingTips", "scoreDelta"),
                "additionalProperties", false
        );

        Map<String, Object> format = Map.of(
                "type", "json_schema",
                "name", "AiTurnFeedback",
                "schema", schema,
                "strict", true
        );

        String prompt = """
                당신은 모의면접 코치입니다. 아래 정보를 바탕으로 간단 코칭과 지표별 증감치를 반환하세요.
                - 질문유형: %s
                - 질문: %s
                - 후보자 답변: %s
                
                규칙:
                1) coachingTips: 1~2문장 한국어
                2) scoreDelta: clarity, structure_STAR, tech_depth, tradeoff, root_cause **5개 키를 모두 포함**하고, 해당 없음은 0. 각 값은 -2..+3 정수.
                3) 반드시 지정된 JSON 스키마로 출력
                """.formatted(q.type(), q.text(), transcript);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", prompt);
        body.put("text", Map.of("format", format));
        body.put("store", true); // ★ 체인 안정화
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            body.put("previous_response_id", previousResponseId);
        }

        String raw = postJson("/responses", body);
        JsonNode root = om.readTree(raw);
        String responseId = root.path("id").asText(null);

        JsonNode parsed = findParsed(root);

        if (parsed == null) {
            parsed = tryParseJsonFromText(root);
        }

        if (parsed != null && !parsed.isMissingNode() && !parsed.isNull()) {
            String tips = parsed.path("coachingTips").asText("핵심부터 1~2문장으로.").trim();

            // 5개 키 모두 채우기
            String[] KEYS = {"clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause"};
            Map<String, Integer> delta = new HashMap<>();
            JsonNode sd = parsed.path("scoreDelta");
            for (String k : KEYS) {
                int v = (sd.has(k) && sd.get(k).isInt()) ? sd.get(k).asInt() : 0;
                delta.put(k, v);
            }
            return new AiTurnFeedbackDto(tips, delta, responseId);
        }

        // 폴백: 텍스트
        String text = findText(root);
        Map<String, Integer> delta = Map.of("clarity", 1);
        return new AiTurnFeedbackDto(text.isBlank() ? "핵심부터 1~2문장으로." : text, delta, responseId);
    }

    @Override
    public Map<String, Object> finalizeReport(String sessionJson, String previousResponseId) {
        try {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "strengths", Map.of("type", "string"),
                            "areasToImprove", Map.of("type", "string"),
                            "nextSteps", Map.of("type", "string")
                    ),
                    "required", List.of("strengths", "areasToImprove", "nextSteps"),
                    "additionalProperties", false
            );

            Map<String, Object> format = Map.of(
                    "type", "json_schema",
                    "name", "FinalReportSummary",   // ★ 이름 변경
                    "schema", schema,
                    "strict", true
            );

            String prompt = """
                    당신은 시니어 한국어 면접 코치입니다. 직전까지의 대화 맥락은 previous_response_id로 제공됩니다.
                    아래 facts는 서버가 계산/정리한 공식 정보이므로 사실로 간주하고 반드시 반영하세요. facts와 모순되는 내용은 쓰지 마세요.
                    
                    목표: strengths / areasToImprove / nextSteps를 생성합니다.
                    
                    요구사항:
                    - 각 항목 2~3문장, 한국어, 존댓말 없이 간결한 서술체로 작성.
                    - strengths에는 topStrengthKeys 중 최소 1개를 자연스럽게 명시하고,
                      qa[].transcriptExcerpt 또는 coachingTips에서 근거 1가지를 끌어와 연결하세요.
                    - areasToImprove에는 areasToImproveKeys 중 최소 1개를 명시하고,
                      왜 필요한지(영향/리스크)를 한 문장으로 설명하세요.
                    - nextSteps는 1~2주 내 실행 가능한 행동 1~2개를 제시하고,
                      측정 기준(예: 횟수/분/산출물)을 포함하세요.
                    - 수치/사례가 facts에 있으면 반드시 포함. 상투적 표현과 모호한 표현 금지.
                    - 출력은 지정된 JSON 스키마(FinalReportSummary)에 정확히 맞춰 생성하고,
                      JSON 이외의 텍스트는 출력하지 마세요.
                    
                    [facts JSON]
                    %s
                    """.formatted(sessionJson);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", prompt);
            body.put("store", true);
            body.put("text", Map.of("format", format));
            if (previousResponseId != null && !previousResponseId.isBlank()) {
                body.put("previous_response_id", previousResponseId);
            }

            String raw = postJson("/responses", body);
            JsonNode root = om.readTree(raw);

            JsonNode parsed = findParsed(root);

            if (parsed == null) {
                parsed = tryParseJsonFromText(root);
            }

            if (parsed != null) {
                String strengths = parsed.path("strengths").asText("").trim();
                String areas = parsed.path("areasToImprove").asText("").trim();
                String next = parsed.path("nextSteps").asText("").trim();
                Map<String, Object> out = new HashMap<>();
                out.put("strengths", strengths);
                out.put("areasToImprove", areas);
                out.put("nextSteps", next);
                return out;
            }

            /// 폴백
            String text = findText(root);
            return Map.of(
                    "strengths", text.isBlank() ? "강점을 간결히 요약해 주세요." : text,
                    "areasToImprove", "구체적 보완 포인트를 1~2문장으로.",
                    "nextSteps", "다음 면접 전 준비할 행동을 한 줄로."
            );
        } catch (Exception e) {
            return Map.of(
                    "strengths", "논리 전개가 명확합니다.",
                    "areasToImprove", "사례 기반 근거를 보강하세요.",
                    "nextSteps", "핵심 경험을 STAR로 1분 요약하는 연습."
            );
        }
    }

    private String postJson(String path, Map<String, Object> body) {
        return webClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp ->
                        resp.bodyToMono(String.class).map(b -> {
                            if (resp.statusCode().isError()) {
                                // ★ OpenAI 에러 원인 확인용 전체 바디 출력
                                System.err.println("[OpenAI][HTTP " + resp.statusCode() + "] " + b);
                                throw new RuntimeException("OpenAI error: " + b);
                            }
                            return b;
                        })
                )
                .block(Duration.ofSeconds(30));
    }

    // 클래스 내부 private 메서드 두 개 추가
    private JsonNode findParsed(JsonNode root) {
        // 1) 구버전/편의 필드
        JsonNode parsed = root.path("output_parsed");
        if (parsed != null && !parsed.isMissingNode() && !parsed.isNull()) return parsed;

        // 2) 표준 위치: output[].content[].parsed
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode msg : output) {
                JsonNode content = msg.path("content");
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        String t = c.path("type").asText("");
                        if (c.hasNonNull("parsed") && (
                                "json_schema".equals(t) || "output_json".equals(t) || "tool_result".equals(t)
                        )) {
                            return c.get("parsed");
                        }
                    }
                }
            }
        }
        return null;
    }

    private String findText(JsonNode root) {
        // 1) 구버전/편의 필드
        String txt = root.path("output_text").asText(null);
        if (txt != null) return txt;

        // 2) 표준 위치: output[].content[].text (type=output_text)
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode msg : output) {
                JsonNode content = msg.path("content");
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        if ("output_text".equals(c.path("type").asText(""))) {
                            return c.path("text").asText("");
                        }
                    }
                }
            }
        }
        return "";
    }

    private JsonNode tryParseJsonFromText(JsonNode root) {
        String txt = findText(root);
        if (txt == null) return null;
        txt = txt.trim();
        if (!(txt.startsWith("{") || txt.startsWith("["))) return null;
        try {
            return om.readTree(txt);
        } catch (Exception ignore) {
            return null;
        }
    }
}
