package com.gaebang.backend.domain.interview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interview.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interview.util.PlanParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component("geminiInterviewerGateway")
public class GeminiInterviewerGateway implements InterviewerAiGateway {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String realtimeModel;
    private final String analysisModel;
    private final String baseUrl;
    private final PlanParser planParser;
    private final ObjectMapper om;
    
    // 컨텍스트 관리를 위한 대화 기록 저장
    private final Map<String, List<Map<String, Object>>> conversationHistory = new ConcurrentHashMap<>();

    public GeminiInterviewerGateway(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.models.realtime:gemini-1.5-flash}") String realtimeModel,
            @Value("${gemini.api.models.analysis:gemini-2.5-flash}") String analysisModel,
            @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            PlanParser planParser,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = new RestTemplate();
        this.apiKey = apiKey;
        this.realtimeModel = realtimeModel;
        this.analysisModel = analysisModel;
        this.baseUrl = baseUrl;
        this.planParser = planParser;
        this.om = objectMapper;
    }

    @PostConstruct
    void log() {
        System.out.println("[AI] Using GeminiInterviewerGateway with Dynamic Model Selection");
        System.out.println("[AI] API Key status: " + (apiKey != null && !apiKey.isBlank() ? "OK (length: " + apiKey.length() + ")" : "MISSING"));
        System.out.println("[AI] Realtime Model: " + realtimeModel);
        System.out.println("[AI] Analysis Model: " + analysisModel);
        System.out.println("[AI] Base URL: " + baseUrl);
    }
    
    private String getOptimalModel(String methodName) {
        return switch (methodName) {
            case "nextTurn" -> realtimeModel;
            case "generatePlan" -> analysisModel;
            default -> analysisModel;
        };
    }

    @Override
    public String generateGreeting(String displayName) {
        return displayName + "님, 안녕하세요. 이번 면접을 진행할 면접관입니다. 만나서 반갑습니다. 질문을 시작하겠습니다.";
    }

    @Override
    public Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates) {
        try {
            return generateQuestionsWithGemini(role, profileSnapshotJson);
        } catch (Exception e) {
            System.err.println("[AI][Gemini] 질문 생성 실패, 기본 질문 사용");
            System.err.println("  - 역할: " + role);
            System.err.println("  - 프로필 스냅샷: " + (profileSnapshotJson != null ? "있음(" + profileSnapshotJson.length() + "자)" : "없음"));
            System.err.println("  - 에러: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            
            if (candidates != null && !candidates.isEmpty()) {
                return createNormalizedQuestions(candidates);
            }
            return getFallbackQuestions();
        }
    }

    private Map<String, Object> createNormalizedQuestions(List<Map<String, Object>> candidates) {
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

    private Map<String, Object> generateQuestionsWithGemini(String role, String profileSnapshotJson) throws Exception {
        String systemPrompt = buildSystemPrompt(role, profileSnapshotJson);
        
        long currentTime = System.currentTimeMillis();
        int randomSeed = new java.util.Random().nextInt(10000);
        int roleHash = role.hashCode();
        int seed = Math.abs((int) (currentTime + randomSeed + roleHash)) % 100000;
        
        String userPrompt = String.format(
            "위 조건에 맞는 면접 질문 10개를 JSON 형식으로 생성해주세요. " +
            "⚠️ 중요: 시드값 %d를 활용하여 매번 완전히 다른 관점의 질문을 생성하세요. " +
            "같은 역할이라도 절대 비슷한 질문 패턴을 반복하지 마세요. " +
            "창의적이고 다양한 각도에서 접근하세요. " +
            "응답은 반드시 다음 JSON 형식으로만 작성해주세요: " +
            "{\"questions\": [{\"idx\": 0, \"type\": \"BEHAVIORAL\", \"text\": \"질문내용\"}, ...]}", 
            seed
        );

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of(
                    "parts", List.of(
                        Map.of("text", systemPrompt + "\n\n" + userPrompt)
                    )
                )
            ),
            "generationConfig", Map.of(
                "temperature", 0.7,
                "maxOutputTokens", 10000,  // 질문+의도+가이드 생성을 위해 증가
                "responseMimeType", "application/json",
                "responseSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "questions", Map.of(
                            "type", "array",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "idx", Map.of("type", "integer"),
                                    "type", Map.of("type", "string"),
                                    "text", Map.of("type", "string"),
                                    "intent", Map.of("type", "string"),
                                    "guides", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string")
                                    )
                                ),
                                "required", List.of("idx", "type", "text", "intent", "guides")
                            )
                        )
                    ),
                    "required", List.of("questions")
                )
            )
        );

        String selectedModel = getOptimalModel("generatePlan");
        String url = baseUrl + "/models/" + selectedModel + ":generateContent?key=" + apiKey;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        
        return parseGeminiResponse(response.getBody());
    }

    private String buildSystemPrompt(String role, String profileSnapshotJson) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("당신은 전문 기술 면접관입니다. ");
        
        String roleSpecificPrompt = getRoleSpecificPrompt(role);
        prompt.append(roleSpecificPrompt);
        
        boolean hasDocument = profileSnapshotJson != null && !profileSnapshotJson.equals("{}");
        
        if (hasDocument) {
            prompt.append("\n\n=== 지원자 정보 ===\n");
            prompt.append(profileSnapshotJson);
            prompt.append("\n\n📋 **개인화 질문 생성 지침:**");
            prompt.append("\n- 지원자의 실제 경험과 기술스택을 바탕으로 구체적인 질문 생성");
            prompt.append("\n- 문서에 언급된 프로젝트나 기술에 대한 심화 질문 포함");
            prompt.append("\n- 지원자의 경력 수준에 맞는 적절한 난이도 조절");
        } else {
            prompt.append("\n\n📋 **다양성 질문 생성 지침:**");
            prompt.append("\n- 같은 역할이라도 매번 다른 관점의 질문 생성");
            prompt.append("\n- 다음 중 랜덤하게 선택하여 질문 방향성 결정:");
            prompt.append("\n  * 성능/최적화 중심 면접");
            prompt.append("\n  * 협업/커뮤니케이션 중심 면접");
            prompt.append("\n  * 문제해결/트러블슈팅 중심 면접");
            prompt.append("\n  * 아키텍처/설계 중심 면접");
            prompt.append("\n  * 학습/성장 중심 면접");
            prompt.append("\n- 시드값으로 현재 시간을 활용하여 매번 다른 질문 조합 생성");
        }
        
        prompt.append("""
                
                🎯 **필수 역할 준수 조건:**
                ⚠️ 경고: 역할과 맞지 않는 기술 질문 시 면접 무효 처리됩니다!
                - 현재 역할: """ + role + """
                - 해당 역할의 기술 스택만 사용하여 질문 생성
                - 다른 분야 기술은 절대 언급 금지
                
                🎯 **구조화된 질문 생성 조건:**
                1. **구간별 질문 타입 (필수 준수):**
                   - 1-2번: BEHAVIORAL (워밍업) - 자기소개, 동기, 기본 경험
                   - 3-6번: TECHNICAL (핵심 역량) - 기술 구현, 코드 품질, 실무 경험  
                   - 7-8번: SYSTEM_DESIGN (설계 사고) - 아키텍처, 확장성, 성능
                   - 9번: TROUBLESHOOT (문제 해결) - 장애 대응, 디버깅, 근본 원인 분석
                   - 10번: TECHNICAL (종합 역량) - 도전적 문제 해결, 기술적 성장
                
                2. **난이도 조절:** 점진적으로 높여가며 생성
                3. **실무 중심:** 구체적이고 실용적인 질문
                4. **질문별 추가 생성 요구사항:**
                   - intent: 해당 질문의 평가 목적을 1-2문장으로 명확히 설명
                   - guides: 좋은 답변을 위한 구체적인 가이드 정확히 3개 제공
                
                JSON 응답 형식:
                {
                  "questions": [
                    {
                      "idx": 0, 
                      "type": "BEHAVIORAL", 
                      "text": "자기소개를 간단히 해주세요.",
                      "intent": "지원자의 커뮤니케이션 능력과 핵심 경험을 파악합니다.",
                      "guides": ["구체적인 경험과 성과를 바탕으로 간결하게 소개하세요.", "담당한 프로젝트와 기술 스택을 명확히 언급하세요.", "회사와 팀에 기여할 수 있는 강점을 어필하세요."]
                    },
                    {"idx": 1, "type": "BEHAVIORAL", "text": "질문 내용", "intent": "의도 설명", "guides": ["가이드1", "가이드2", "가이드3"]},
                    ...
                    {"idx": 9, "type": "WRAPUP", "text": "질문 내용", "intent": "의도 설명", "guides": ["가이드1", "가이드2", "가이드3"]}
                  ]
                }
                """);
        
        return prompt.toString();
    }
    
    private String getRoleSpecificPrompt(String role) {
        return switch (role) {
            case "BACKEND", "BACKEND_DEVELOPER" -> """
                🚨 중요: 당신은 백엔드 개발자 전문 면접관입니다. 반드시 백엔드 기술만 다루세요.
                
                ❌ 절대 금지: JavaScript, React, Vue, 프론트엔드 기술 관련 질문
                ✅ 필수 포함: 
                - Java, Spring Boot/Framework, JPA/Hibernate
                - 서버 아키텍처, REST API 설계, 데이터베이스 (MySQL, PostgreSQL)
                - 동시성 처리, 멀티스레딩, 성능 최적화
                - 시스템 설계, MSA, 캐싱 (Redis), 메시지큐
                - 장애 대응, 모니터링, 보안, 인증/인가
                - Spring Security, JUnit 테스트, CI/CD
                
                역할 확인: 백엔드 개발자는 서버사이드 개발만 담당합니다.
                """;
                
            case "FRONTEND", "FRONTEND_DEVELOPER" -> """
                🚨 중요: 당신은 프론트엔드 개발자 전문 면접관입니다. 반드시 프론트엔드 기술만 다루세요.
                
                ❌ 절대 금지: Java, Spring, 서버사이드 기술 관련 질문
                ✅ 필수 포함:
                - JavaScript ES6+, TypeScript, React/Vue
                - 컴포넌트 설계, 상태 관리 (Redux, Vuex)
                - 브라우저 호환성, 웹 접근성, SEO
                - Webpack, Vite, 빌드 도구, Jest 테스팅
                - 사용자 경험, 성능 최적화, 반응형 디자인
                
                역할 확인: 프론트엔드 개발자는 클라이언트사이드 개발만 담당합니다.
                """;
                
            case "FULLSTACK", "FULLSTACK_DEVELOPER" -> """
                풀스택 개발자 면접을 진행합니다.
                - 프론트엔드와 백엔드 기술 스택 모두 다룸
                - 전체 서비스 아키텍처 설계 능력 평가
                - 기술 선택 기준, 트레이드오프 판단력
                - DevOps, 배포, 모니터링까지 전반적 이해도 확인
                """;
                
            default -> """
                개발자 면접을 진행합니다.
                - 프로그래밍 기초, 문제 해결 능력 중심
                - 협업, 커뮤니케이션, 학습 능력 평가
                - 기술적 호기심, 성장 가능성 확인
                """;
        };
    }

    private Map<String, Object> parseGeminiResponse(String response) throws Exception {
        JsonNode root = om.readTree(response);
        JsonNode candidates = root.path("candidates");
        
        if (candidates.isEmpty()) {
            throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
        }
        
        JsonNode content = candidates.get(0).path("content");
        JsonNode parts = content.path("parts");
        
        if (parts.isEmpty()) {
            throw new RuntimeException("Gemini 응답에 parts가 없습니다");
        }
        
        String text = parts.get(0).path("text").asText();
        
        // JSON 응답이 불완전할 수 있으므로 안전하게 파싱
        try {
            // 텍스트가 JSON으로 시작하는지 확인
            if (!text.trim().startsWith("{")) {
                throw new RuntimeException("Gemini 응답이 JSON 형식이 아닙니다: " + text.substring(0, Math.min(text.length(), 100)));
            }
            
            return om.readValue(text, Map.class);
            
        } catch (Exception e) {
            System.err.println("[Gemini] JSON 파싱 실패. 응답 텍스트: " + text);
            throw new RuntimeException("Gemini JSON 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> getFallbackQuestions() {
        Map<String, Object> q0 = Map.of("idx", 0, "type", "BEHAVIORAL", "text", "자기소개를 간단히 해주세요.");
        Map<String, Object> q1 = Map.of("idx", 1, "type", "BEHAVIORAL", "text", "최근 협업 갈등을 STAR로 설명해 주세요.");
        Map<String, Object> q2 = Map.of("idx", 2, "type", "TECHNICAL", "text", "가장 자신있는 기술 스택에 대해 설명해주세요.");
        Map<String, Object> q3 = Map.of("idx", 3, "type", "TECHNICAL", "text", "최근 해결한 기술적 문제를 설명해주세요.");
        Map<String, Object> q4 = Map.of("idx", 4, "type", "SYSTEM_DESIGN", "text", "대용량 트래픽 처리 경험이 있나요?");
        Map<String, Object> q5 = Map.of("idx", 5, "type", "TECHNICAL", "text", "코드 리뷰 시 중점적으로 보는 부분은?");
        Map<String, Object> q6 = Map.of("idx", 6, "type", "TROUBLESHOOT", "text", "장애 상황에서 어떻게 대응하시나요?");
        Map<String, Object> q7 = Map.of("idx", 7, "type", "BEHAVIORAL", "text", "새로운 기술 학습 방법을 설명해주세요.");
        Map<String, Object> q8 = Map.of("idx", 8, "type", "TECHNICAL", "text", "성능 최적화 경험을 공유해주세요.");
        Map<String, Object> q9 = Map.of("idx", 9, "type", "TECHNICAL", "text", "지금까지 참여한 프로젝트 중 가장 도전적이었던 기술적 문제와 해결 과정을 설명해주세요.");

        return Map.of("questions", List.of(q0, q1, q2, q3, q4, q5, q6, q7, q8, q9));
    }

    @Override
    public AiTurnFeedbackDto nextTurn(String planJson, int questionIndex,
                                      String transcript, String recentSummaryJson,
                                      String previousResponseId) throws Exception {

        PlanQuestionDto q = planParser.getQuestionByIndex(planJson, questionIndex);

        String prompt = """
                당신은 전문 면접 코치입니다. 아래 정보를 바탕으로 건설적인 피드백을 제공해주세요.
                - 질문유형: %s
                - 질문: %s
                - 후보자 답변: %s
                
                피드백 제공 가이드라인:
                1) 답변의 강점과 개선점을 균형있게 언급
                2) 구체적이고 실행 가능한 조언 제시
                3) 질문 유형에 맞는 맞춤형 피드백
                4) 1-2문장으로 간결하고 명확하게 작성
                
                응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                {
                  "coachingTips": "구체적인 개선점과 조언을 1-2문장으로"
                }
                """.formatted(q.type(), q.text(), transcript);

        // 대화 기록에 추가 (컨텍스트 관리)
        String conversationKey = previousResponseId != null ? previousResponseId : "session_" + planJson.hashCode();
        List<Map<String, Object>> history = conversationHistory.computeIfAbsent(conversationKey, k -> new ArrayList<>());
        
        // 히스토리가 비어있으면 시스템 메시지 추가
        if (history.isEmpty()) {
            history.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "당신은 전문 면접 코치입니다. 다음부터 면접 답변에 대해 건설적인 피드백을 제공해주세요."))
            ));
            history.add(Map.of(
                "role", "model", 
                "parts", List.of(Map.of("text", "네, 전문적이고 건설적인 피드백을 제공하겠습니다."))
            ));
        }
        
        // 현재 대화를 히스토리에 추가
        history.add(Map.of(
            "role", "user",
            "parts", List.of(Map.of("text", prompt))
        ));

        Map<String, Object> requestBody = Map.of(
            "contents", history,
            "generationConfig", Map.of(
                "temperature", 0.2,         // 0.3 → 0.2 (더 결정론적)
                "maxOutputTokens", 2000,    // 4000 → 2000 (50% 감소)
                "topK", 10,                 // 후보 단어 수 제한
                "topP", 0.8,                // 확률 임계값 설정
                "responseMimeType", "application/json",
                "responseSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "coachingTips", Map.of("type", "string")
                    ),
                    "required", List.of("coachingTips")
                )
            )
        );

        // API 키 검증
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("[Gemini] API key is missing. Check GEMINI_API_KEY environment variable");
        }
        
        String selectedModel = getOptimalModel("nextTurn");
        String url = baseUrl + "/models/" + selectedModel + ":generateContent?key=" + apiKey;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(url, entity, String.class);
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                // 실패 시 히스토리에서 마지막 사용자 메시지 제거
                if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).get("role"))) {
                    history.remove(history.size() - 1);
                }
                throw new RuntimeException("Gemini API 호출 실패: " + response.getStatusCode() + " - " + response.getBody());
            }
        } catch (Exception e) {
            // 실패 시 히스토리에서 마지막 사용자 메시지 제거
            if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).get("role"))) {
                history.remove(history.size() - 1);
            }
            throw e;
        }
        
        JsonNode root = om.readTree(response.getBody());
        
        // 디버깅을 위한 상세 로깅
        System.err.println("[Gemini Debug] Full response: " + root.toPrettyString());
        
        JsonNode candidates = root.path("candidates");
        
        if (candidates.isEmpty()) {
            System.err.println("[Gemini Debug] No candidates in response");
            throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
        }
        
        JsonNode candidate = candidates.get(0);
        JsonNode content = candidate.path("content");
        JsonNode parts = content.path("parts");
        
        // 후보자 정보 로깅
        System.err.println("[Gemini Debug] Candidate finish reason: " + candidate.path("finishReason").asText("NONE"));
        System.err.println("[Gemini Debug] Content node: " + content.toPrettyString());
        
        if (parts.isEmpty()) {
            System.err.println("[Gemini Debug] Parts is empty. Full candidate: " + candidate.toPrettyString());
            
            // Safety ratings 확인
            JsonNode safetyRatings = candidate.path("safetyRatings");
            if (!safetyRatings.isEmpty()) {
                System.err.println("[Gemini Debug] Safety ratings detected: " + safetyRatings.toPrettyString());
            }
            
            // 실패 시 히스토리에서 마지막 사용자 메시지 제거
            if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).get("role"))) {
                history.remove(history.size() - 1);
            }
            throw new RuntimeException("Gemini 응답에 parts가 없습니다. finishReason: " + candidate.path("finishReason").asText("UNKNOWN"));
        }
        
        String responseText = parts.get(0).path("text").asText();
        
        // 응답을 히스토리에 추가
        history.add(Map.of(
            "role", "model",
            "parts", List.of(Map.of("text", responseText))
        ));
        
        // 응답 ID 생성 (Gemini에는 없으므로 시간 기반으로 생성)
        String responseId = "gemini_" + System.currentTimeMillis() + "_" + questionIndex;
        
        // 안전한 JSON 파싱
        Map<String, Object> parsedResponse;
        try {
            if (!responseText.trim().startsWith("{")) {
                throw new RuntimeException("Gemini 응답이 JSON 형식이 아닙니다: " + responseText.substring(0, Math.min(responseText.length(), 100)));
            }
            parsedResponse = om.readValue(responseText, Map.class);
        } catch (Exception e) {
            System.err.println("[Gemini] nextTurn JSON 파싱 실패. 응답 텍스트: " + responseText);
            // 폴백: 기본 응답 생성
            parsedResponse = Map.of(
                "coachingTips", "답변을 더 구체적으로 보완해주세요."
            );
        }
        
        String tips = (String) parsedResponse.getOrDefault("coachingTips", "핵심부터 1~2문장으로.");
        
        return new AiTurnFeedbackDto(tips, responseId);
    }

    @Override
    public Map<String, Object> generateQuestionIntentAndGuides(String questionType, String questionText, String role) throws Exception {
        try {
            String roleGuide = getRoleSpecificGuidePrompt(role);
            String typeGuide = getQuestionTypeGuidePrompt(questionType);

            String prompt = """
                    당신은 전문 면접 코치입니다. 다음 면접 질문에 대한 의도와 답변 가이드를 생성해주세요.
                    
                    **면접 질문 정보:**
                    - 직무: %s
                    - 질문 유형: %s  
                    - 질문: %s
                    
                    **역할별 가이드:**
                    %s
                    
                    **질문 유형별 가이드:**
                    %s
                    
                    **생성 요구사항:**
                    1. intent: 이 질문을 통해 무엇을 평가하려는지 1-2문장으로 명확히 설명
                    2. guides: 좋은 답변을 위한 구체적인 가이드 3개를 배열로 제공
                    
                    **답변 가이드 작성 원칙:**
                    - 각 가이드는 구체적이고 실행 가능한 조언으로 작성
                    - STAR 구조를 직접 언급하지 말고, 자연스럽게 포함되도록 작성
                    - 기술적 깊이와 비즈니스 임팩트를 모두 강조
                    - 수치나 구체적 사례 포함을 권장하되 자연스럽게 유도
                    - "~을 명확히 설명하고", "~에 대해 구체적으로 보여주세요" 스타일로 작성
                    
                    **가이드 예시 (참고용):**
                    "상황을 명확히 설명하고, 대규모 사용자 트래픽을 처리해야 하는 이유와 목표를 제시하세요."
                    "아키텍처 설계에서 고려한 주요 요소(확장성, 가용성, 일관성)에 대해 구체적으로 설명하세요."
                    "사용한 기술 스택과 그 선택 이유를 명확히 하고, 각 기술의 장단점을 언급하세요."
                    
                    응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                    {
                      "intent": "질문 의도 설명",
                      "guides": ["가이드1", "가이드2", "가이드3"]
                    }
                    """.formatted(role, questionType, questionText, roleGuide, typeGuide);

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "parts", List.of(
                            Map.of("text", prompt)
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 0.3,
                    "maxOutputTokens", 6000,  // 4000 → 6000 (MAX_TOKENS 해결)
                    "responseMimeType", "application/json",
                    "responseSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "intent", Map.of("type", "string"),
                            "guides", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            )
                        ),
                        "required", List.of("intent", "guides")
                    )
                )
            );

            String selectedModel = getOptimalModel("generateQuestionIntentAndGuides");
            String url = baseUrl + "/models/" + selectedModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            JsonNode root = om.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
            }
            
            JsonNode content = candidates.get(0).path("content");
            JsonNode parts = content.path("parts");
            
            if (parts.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 parts가 없습니다");
            }
            
            String responseText = parts.get(0).path("text").asText();
            
            // 안전한 JSON 파싱
            try {
                if (!responseText.trim().startsWith("{")) {
                    throw new RuntimeException("Gemini 응답이 JSON 형식이 아닙니다: " + responseText.substring(0, Math.min(responseText.length(), 100)));
                }
                return om.readValue(responseText, Map.class);
            } catch (Exception e) {
                System.err.println("[Gemini] generateQuestionIntentAndGuides JSON 파싱 실패. 응답 텍스트: " + responseText);
                throw new RuntimeException("Gemini JSON 응답 파싱 실패: " + e.getMessage(), e);
            }
            
        } catch (Exception e) {
            System.err.println("[AI][Gemini] 질문 의도/가이드 생성 실패");
            System.err.println("  - 질문 유형: " + questionType);
            System.err.println("  - 역할: " + role);
            System.err.println("  - 에러: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return getFallbackIntentAndGuides(questionType, role);
        }
    }

    private String getRoleSpecificGuidePrompt(String role) {
        return switch (role) {
            case "BACKEND", "BACKEND_DEVELOPER" -> """
                🔹 백엔드 개발자 전용 가이드 (서버사이드 개발만):
                - Java/Spring 기반 서버 아키텍처, 성능, 확장성 관점
                - 데이터베이스 설계, JPA/Hibernate, SQL 최적화
                - REST API 설계, Spring Security, 인증/인가
                - 멀티스레딩, 동시성, 시스템 장애 대응
                ❌ 금지: JavaScript, React, 프론트엔드 관련 내용
                """;
            case "FRONTEND", "FRONTEND_DEVELOPER" -> """
                🔹 프론트엔드 개발자 전용 가이드 (클라이언트사이드 개발만):
                - 사용자 경험(UX)과 성능 최적화
                - 컴포넌트 설계 및 상태 관리
                - 브라우저 호환성 및 접근성
                - 최신 프론트엔드 기술 트렌드 활용
                ❌ 금지: Java, Spring, 서버사이드 관련 내용
                """;
            case "FULLSTACK", "FULLSTACK_DEVELOPER" -> """
                풀스택 개발자로서 다음 관점에서 답변하도록 가이드:
                - 전체 시스템 아키텍처 이해도
                - 프론트엔드-백엔드 연동 경험
                - 기술 선택의 트레이드오프 이해
                - DevOps 및 배포 프로세스 경험
                """;
            default -> "개발자로서 기술적 깊이와 문제 해결 과정을 중심으로 답변하도록 가이드";
        };
    }

    private String getQuestionTypeGuidePrompt(String questionType) {
        return switch (questionType) {
            case "BEHAVIORAL" -> """
                행동 면접 질문으로서 다음을 강조:
                - STAR 방식으로 구체적 사례 제시
                - 개인의 역할과 기여도 명확히
                - 갈등 해결, 리더십, 협업 능력
                - 학습과 성장하는 자세
                """;
            case "TECHNICAL" -> """
                기술 면접 질문으로서 다음을 강조:
                - 기술적 깊이와 이해도 확인
                - 실무 적용 경험과 노하우
                - 문제 해결 접근 방식
                - 기술 선택의 근거와 트레이드오프
                """;
            case "SYSTEM_DESIGN" -> """
                시스템 설계 질문으로서 다음을 강조:
                - 전체 아키텍처 관점에서 접근
                - 확장성, 가용성, 일관성 고려
                - 기술 선택 근거와 한계점
                - 단계적 확장 전략
                """;
            case "TROUBLESHOOT" -> """
                문제 해결 질문으로서 다음을 강조:
                - 체계적인 문제 분석 과정
                - 근본 원인 찾기와 해결책
                - 재발 방지 대책
                - 팀 커뮤니케이션 과정
                """;
            default -> "해당 질문의 의도에 맞는 구체적이고 체계적인 답변 가이드 제공";
        };
    }

    private Map<String, Object> getFallbackIntentAndGuides(String questionType, String role) {
        String intent = switch (questionType) {
            case "BEHAVIORAL" -> "지원자의 협업 능력과 문제 해결 경험을 통해 조직 적합성을 평가합니다.";
            case "TECHNICAL" -> "지원자의 기술적 깊이와 실무 적용 능력을 확인합니다.";
            case "SYSTEM_DESIGN" -> "대규모 시스템 설계 능력과 아키텍처 이해도를 평가합니다.";
            case "TROUBLESHOOT" -> "문제 상황에서의 분석 능력과 해결 과정을 확인합니다.";
            default -> "지원자의 역량과 적합성을 종합적으로 평가합니다.";
        };

        List<String> guides = generateRoleSpecificGuides(questionType, role);

        return Map.of(
            "intent", intent,
            "guides", guides
        );
    }

    private List<String> generateRoleSpecificGuides(String questionType, String role) {
        List<String> commonGuides = List.of(
            "구체적인 상황과 배경을 명확히 설명하고, 당시 직면한 과제를 구체적으로 제시하세요.",
            "문제 해결을 위해 취한 행동과 접근 방법을 단계별로 설명하고, 기술적 근거를 포함하세요."
        );

        List<String> roleSpecificGuides = switch (role) {
            case "BACKEND", "BACKEND_DEVELOPER" -> List.of(
                "Java/Spring 기반 서버 아키텍처와 데이터베이스 설계 관점에서 기술적 결정을 구체적으로 설명하세요.",
                "JPA/Hibernate, 멀티스레딩, 성능 최적화 경험을 포함하여 비즈니스 임팩트를 수치로 보여주세요."
            );
            case "FRONTEND", "FRONTEND_DEVELOPER" -> List.of(
                "React/Vue, JavaScript 기반 사용자 경험과 성능 최적화 관점에서 기술적 접근을 설명하세요.",
                "브라우저 호환성, 번들링, 접근성을 고려한 설계 결정과 그 결과를 보여주세요."
            );
            case "FULLSTACK", "FULLSTACK_DEVELOPER" -> List.of(
                "프론트엔드와 백엔드를 아우르는 전체 시스템 관점에서 기술적 의사결정을 설명하세요.",
                "다양한 기술 스택 선택의 근거와 트레이드오프를 구체적으로 언급하세요."
            );
            default -> List.of(
                "기술적 근거와 함께 의사결정 과정을 단계별로 설명하세요.",
                "도전적인 상황을 극복한 과정과 그로부터 얻은 학습을 구체적으로 보여주세요."
            );
        };

        List<String> questionTypeGuides = switch (questionType) {
            case "BEHAVIORAL" -> List.of("최종 결과와 비즈니스 임팩트를 수치나 구체적 사례로 보여주고, 해당 경험에서 얻은 인사이트를 언급하세요.");
            case "TECHNICAL" -> List.of("구현한 기술의 장단점과 대안 기술과의 비교를 통해 기술적 판단력을 보여주세요.");
            case "SYSTEM_DESIGN" -> List.of("확장성과 가용성을 고려한 설계 결정과 실제 운영 결과를 구체적으로 설명하세요.");
            case "TROUBLESHOOT" -> List.of("근본 원인 분석과 재발 방지 대책을 포함하여 체계적인 문제 해결 능력을 보여주세요.");
            default -> List.of("최종 성과와 그 과정에서 배운 핵심 인사이트를 구체적으로 언급하세요.");
        };

        List<String> combined = new java.util.ArrayList<>(commonGuides);
        combined.addAll(roleSpecificGuides);
        combined.addAll(questionTypeGuides);
        
        return combined.size() > 3 ? combined.subList(0, 3) : combined;
    }

    @Override
    public Map<String, Object> generateBatchEvaluation(String evaluationData, String role, String previousResponseId) throws Exception {
        try {
            String prompt = """
                    당신은 전문적이고 관대한 면접관입니다. 아래 전체 면접 내용을 종합하여 격려적이고 현실적인 점수를 산정해주세요.
                    
                    **평가 데이터:**
                    %s
                    
                    **평가 지표 (0-100점):**
                    - clarity: 명확한 의사소통 능력
                    - structure_STAR: 체계적인 답변 구조 (상황-과제-행동-결과)
                    - tech_depth: 기술적 깊이와 전문성
                    - tradeoff: 트레이드오프 인식과 판단력
                    - root_cause: 근본 원인 분석 능력
                    
                    **점수 기준 (관대한 기준):**
                    - 0-39점: 부족 (답변 회피, 기본 지식 심각한 부족)
                    - 40-60점: 보통 (기본적 이해, 실무 학습으로 보완 가능)
                    - 61-75점: 좋음 (실무에서 충분히 활용 가능한 수준)
                    - 76-90점: 우수 (깊이 있는 전문성, 뛰어난 역량)
                    - 91-100점: 최우수 (탁월한 전문성, 리더십 수준)
                    
                    **관대한 평가 원칙:**
                    - ChatGPT나 AI 답변 수준이면 65-75점 범위로 평가
                    - 기본적인 답변이라도 논리적 흐름이 있으면 60점 이상
                    - 완벽하지 않더라도 실무에서 학습 가능한 수준이면 좋음 점수
                    - 역할(%s)에 맞는 현실적이고 격려적인 관점으로 평가
                    - 성장 가능성과 학습 의지를 긍정적으로 인정
                    - 각 지표별로 60점 이상을 기본 출발점으로 고려
                    
                    응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                    {
                      "scores": {
                        "clarity": 68,
                        "structure_STAR": 64,
                        "tech_depth": 72,
                        "tradeoff": 66,
                        "root_cause": 62
                      }
                    }
                    """.formatted(evaluationData, role);

            // 컨텍스트 관리
            String conversationKey = previousResponseId != null ? previousResponseId : "batch_" + evaluationData.hashCode();
            List<Map<String, Object>> history = conversationHistory.computeIfAbsent(conversationKey, k -> new ArrayList<>());
            
            // 히스토리가 비어있으면 시스템 메시지 추가
            if (history.isEmpty()) {
                history.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", "당신은 전문적이고 관대한 면접관입니다. 전체 면접 세션을 종합하여 현실적이고 격려적인 점수를 산정해주세요."))
                ));
                history.add(Map.of(
                    "role", "model", 
                    "parts", List.of(Map.of("text", "네, 면접 전체를 종합하여 현실적이고 격려적인 관점에서 점수를 산정하겠습니다."))
                ));
            }
            
            history.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt))
            ));

            Map<String, Object> requestBody = Map.of(
                "contents", history,
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "maxOutputTokens", 8000,
                    "responseMimeType", "application/json",
                    "responseSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "scores", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "clarity", Map.of("type", "integer"),
                                    "structure_STAR", Map.of("type", "integer"),
                                    "tech_depth", Map.of("type", "integer"),
                                    "tradeoff", Map.of("type", "integer"),
                                    "root_cause", Map.of("type", "integer")
                                ),
                                "required", List.of("clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause")
                            )
                        ),
                        "required", List.of("scores")
                    )
                ),
                "safetySettings", List.of(
                    Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"),
                    Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"),
                    Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"),
                    Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE")
                )
            );

            String selectedModel = getOptimalModel("generateBatchEvaluation");
            String url = baseUrl + "/models/" + selectedModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            String maskedUrl = url.replaceAll("key=[^&]+", "key=***MASKED***");
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            JsonNode root = om.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
            }
            
            // 디버깅: finishReason과 token 사용량 로깅
            JsonNode firstCandidate = candidates.get(0);
            String finishReason = firstCandidate.path("finishReason").asText("UNKNOWN");
            JsonNode usageMetadata = root.path("usageMetadata");
            int thoughtsTokenCount = usageMetadata.path("thoughtsTokenCount").asInt(0);
            int totalTokenCount = usageMetadata.path("totalTokenCount").asInt(0);
            
            log.info("[AI] Gemini generateBatchEvaluation - finishReason: {}, thoughtsTokens: {}, totalTokens: {}", 
                    finishReason, thoughtsTokenCount, totalTokenCount);
            
            JsonNode content = firstCandidate.path("content");
            JsonNode parts = content.path("parts");
            
            if (parts.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 parts가 없습니다 - finishReason: " + finishReason + 
                        ", thoughtsTokens: " + thoughtsTokenCount + ", totalTokens: " + totalTokenCount);
            }
            
            String responseText = parts.get(0).path("text").asText();
            
            // 응답을 히스토리에 추가
            history.add(Map.of(
                "role", "model",
                "parts", List.of(Map.of("text", responseText))
            ));
            
            // 안전한 JSON 파싱
            try {
                if (!responseText.trim().startsWith("{")) {
                    throw new RuntimeException("Gemini 응답이 JSON 형식이 아닙니다: " + responseText.substring(0, Math.min(responseText.length(), 100)));
                }
                return om.readValue(responseText, Map.class);
            } catch (Exception e) {
                System.err.println("[Gemini] generateBatchEvaluation JSON 파싱 실패. 응답 텍스트: " + responseText);
                // 폴백: 기본 점수 반환
                return Map.of(
                    "scores", Map.of(
                        "clarity", 45,
                        "structure_STAR", 40,
                        "tech_depth", 50,
                        "tradeoff", 42,
                        "root_cause", 38
                    )
                );
            }
            
        } catch (Exception e) {
            System.err.println("[AI][Gemini] generateBatchEvaluation 실패: " + e.getMessage());
            return Map.of(
                "scores", Map.of(
                    "clarity", 45,
                    "structure_STAR", 40,
                    "tech_depth", 50,
                    "tradeoff", 42,
                    "root_cause", 38
                )
            );
        }
    }

    @Override
    public Map<String, Object> extractDocumentInfo(String documentText) throws Exception {
        try {
            String prompt = """
                    당신은 전문 HR 담당자입니다. 아래 문서에서 면접에 필요한 종합적인 정보를 추출해주세요.
                    
                    **문서 내용:**
                    %s
                    
                    **추출할 정보:**
                    1. **기술 스택**: 모든 프로그래밍 언어, 프레임워크, 라이브러리, 도구
                       - 다양한 표현 인식: "React.js", "리액트", "ReactJS" 모두 "React"로 통합
                       - 버전 정보 포함: "Java 17", "Spring Boot 3.x" 등
                    
                    2. **프로젝트 경험**: 개발 프로젝트 정보
                       - 기간: 시작-종료 날짜 또는 기간
                       - 역할: 팀장, 리더, 백엔드, 프론트엔드, 풀스택 등
                       - 규모: 팀 규모나 프로젝트 규모 (있는 경우)
                    
                    3. **경력 정보**: 실무 경험 (회사명 제외)
                       - 기간: 총 경력 또는 각 회사별 기간
                       - 직무: 개발자, 엔지니어, 팀장 등
                       - 수준: 신입, 경력, 시니어 등
                    
                    4. **학력 정보**: 교육 배경 (학교명 제외)
                       - 학위: 학사, 석사, 박사 등
                       - 전공: 컴퓨터공학, 소프트웨어학과 등
                       - 상태: 졸업, 재학, 수료 등
                    
                    5. **자격증/인증**: 보유 자격증 및 인증
                       - IT 자격증: 정보처리기사, 네트워크관리사 등
                       - 클라우드 인증: AWS, Azure, GCP 등
                       - 기타 전문 인증: PMP, SCRUM 등
                    
                    6. **성과/수상**: 수상 경력 및 특별한 성과
                       - 해커톤, 공모전, 경진대회 수상
                       - 논문 발표, 특허 출원
                       - 오픈소스 기여, 커뮤니티 활동
                    
                    7. **포트폴리오**: 온라인 활동 및 포트폴리오
                       - GitHub 활동 여부
                       - 기술 블로그 운영 여부  
                       - 개인 웹사이트/포트폴리오 사이트
                    
                    8. **언어 능력**: 구사 가능한 언어
                       - 한국어, 영어, 일본어, 중국어 등
                       - 수준: 원어민, 비즈니스, 일상회화 등
                    
                    9. **전문 분야**: 관심 영역 및 전문성
                       - 백엔드, 프론트엔드, DevOps, 데이터 등
                       - AI/ML, 블록체인, IoT 등 신기술
                       - 도메인 전문성: 핀테크, 이커머스 등
                    
                    10. **개발 선호도**: 선호하는 도구나 방법론
                        - 개발 도구: IDE, 에디터
                        - 협업 도구: Git, Slack, Notion 등
                        - 방법론: Agile, Scrum, TDD 등
                    
                    **중요 지침:**
                    - 개인 식별 정보 절대 포함 금지 (이름, 회사명, 학교명 등)
                    - 맥락을 고려한 정확한 정보만 추출
                    - 애매하거나 확실하지 않은 정보는 포함하지 않음
                    - 각 항목은 빈 배열로라도 반드시 포함
                    - 기술 스택은 표준 명칭으로 통일
                    
                    응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                    {
                      "techStacks": ["Java", "Spring Boot", "React", "MySQL"],
                      "projects": [
                        {
                          "duration": "2023년 3월 ~ 2023년 12월",
                          "role": "백엔드 개발자",
                          "scale": "5명 팀"
                        }
                      ],
                      "careers": [
                        {
                          "duration": "3년 6개월",
                          "role": "백엔드 개발자",
                          "level": "경력"
                        }
                      ],
                      "education": [
                        {
                          "degree": "학사",
                          "major": "컴퓨터공학",
                          "status": "졸업"
                        }
                      ],
                      "certifications": ["정보처리기사", "AWS SAA", "리눅스마스터"],
                      "achievements": ["해커톤 1위", "오픈소스 기여 100+ commits", "기술블로그 월 평균 1만 조회수"],
                      "portfolio": {
                        "github": "활발한 활동",
                        "blog": "기술 블로그 운영",
                        "website": "개인 포트폴리오 사이트"
                      },
                      "languages": ["한국어(원어민)", "영어(비즈니스)", "일본어(일상회화)"],
                      "specialties": ["백엔드 개발", "클라우드 인프라", "데이터베이스 설계"],
                      "preferences": ["IntelliJ IDEA", "Git/GitHub", "Agile 방법론", "TDD"]
                    }
                    """.formatted(documentText);

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "parts", List.of(
                            Map.of("text", prompt)
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,           // 매우 결정론적
                    "maxOutputTokens", 7000,      // 문서 추출용 충분한 토큰 (5000 → 7000)
                    "responseMimeType", "application/json",
                    "responseSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "techStacks", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            ),
                            "projects", Map.of(
                                "type", "array", 
                                "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "duration", Map.of("type", "string"),
                                        "role", Map.of("type", "string"),
                                        "scale", Map.of("type", "string")
                                    )
                                )
                            ),
                            "careers", Map.of(
                                "type", "array",
                                "items", Map.of(
                                    "type", "object", 
                                    "properties", Map.of(
                                        "duration", Map.of("type", "string"),
                                        "role", Map.of("type", "string"),
                                        "level", Map.of("type", "string")
                                    )
                                )
                            ),
                            "education", Map.of(
                                "type", "array",
                                "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "degree", Map.of("type", "string"),
                                        "major", Map.of("type", "string"),
                                        "status", Map.of("type", "string")
                                    )
                                )
                            ),
                            "certifications", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            ),
                            "achievements", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            ),
                            "portfolio", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "github", Map.of("type", "string"),
                                    "blog", Map.of("type", "string"),
                                    "website", Map.of("type", "string")
                                )
                            ),
                            "languages", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            ),
                            "specialties", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            ),
                            "preferences", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                            )
                        ),
                        "required", List.of("techStacks", "projects", "careers", "education", "certifications", "achievements", "portfolio", "languages", "specialties", "preferences")
                    )
                )
            );

            String selectedModel = getOptimalModel("extractDocumentInfo");
            String url = baseUrl + "/models/" + selectedModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            JsonNode root = om.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
            }
            
            // 디버깅: finishReason과 token 사용량 로깅
            JsonNode firstCandidate = candidates.get(0);
            String finishReason = firstCandidate.path("finishReason").asText("UNKNOWN");
            JsonNode usageMetadata = root.path("usageMetadata");
            int thoughtsTokenCount = usageMetadata.path("thoughtsTokenCount").asInt(0);
            int totalTokenCount = usageMetadata.path("totalTokenCount").asInt(0);
            
            log.info("[AI] Gemini extractDocumentInfo - finishReason: {}, thoughtsTokens: {}, totalTokens: {}", 
                    finishReason, thoughtsTokenCount, totalTokenCount);
            
            JsonNode content = firstCandidate.path("content");
            JsonNode parts = content.path("parts");
            
            if (parts.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 parts가 없습니다 - finishReason: " + finishReason + 
                        ", thoughtsTokens: " + thoughtsTokenCount + ", totalTokens: " + totalTokenCount);
            }
            
            String responseText = parts.get(0).path("text").asText();
            
            // 안전한 JSON 파싱
            try {
                if (!responseText.trim().startsWith("{")) {
                    throw new RuntimeException("Gemini 응답이 JSON 형식이 아닙니다: " + responseText.substring(0, Math.min(responseText.length(), 100)));
                }
                return om.readValue(responseText, Map.class);
            } catch (Exception e) {
                System.err.println("[Gemini] extractDocumentInfo JSON 파싱 실패. 응답 텍스트: " + responseText);
                // 폴백: 10개 항목 빈 구조 반환
                return Map.of(
                    "techStacks", List.of(),
                    "projects", List.of(),
                    "careers", List.of(),
                    "education", List.of(),
                    "certifications", List.of(),
                    "achievements", List.of(),
                    "portfolio", Map.of("github", "정보 없음", "blog", "정보 없음", "website", "정보 없음"),
                    "languages", List.of(),
                    "specialties", List.of(),
                    "preferences", List.of()
                );
            }
            
        } catch (Exception e) {
            System.err.println("[AI][Gemini] extractDocumentInfo 실패: " + e.getMessage());
            throw e; // 상위에서 폴백 처리
        }
    }

    @Override
    public Map<String, Object> finalizeReport(String sessionJson, String previousResponseId) {
        try {
            String prompt = """
                    당신은 전문적이고 격려적인 면접 코치입니다. 아래 facts는 서버가 계산/정리한 공식 정보이므로 사실로 간주하고 반드시 반영하세요.
                    
                    점수 기준 (100점 만점, 관대한 기준):
                    - 0-39점: 부족 (답변 회피, 기본 지식 심각한 부족)
                    - 40-60점: 보통 (기본적 이해, 실무 학습으로 보완 가능)
                    - 61-75점: 좋음 (실무에서 충분히 활용 가능한 수준)
                    - 76-90점: 우수 (깊이 있는 전문성, 뛰어난 역량)
                    - 91-100점: 최우수 (탁월한 전문성, 리더십 수준)
                    
                    격려적 평가 원칙:
                    1. overallScore와 subscores를 반영하되 성장 가능성을 강조한 긍정적 관점
                    2. 60점 이상이면 실무 활용 가능한 좋은 수준으로 인정
                    3. 강점을 적극적으로 발견하고 개선점도 학습 기회로 제시
                    4. 실제 답변 내용을 근거로 구체적이고 건설적인 피드백 제공
                    5. ChatGPT 수준 답변도 65-75점 범위의 좋은 평가로 인정
                    
                    작성 요구사항:
                    - strengths: 점수와 관계없이 긍정적 측면을 적극 발견하여 상세히 분석
                    - areasToImprove: 개선점을 학습과 성장 기회로 프레이밍하여 격려적으로 설명
                    - nextSteps: 현재 수준을 인정하고 한 단계 더 발전할 수 있는 현실적 방안 제시
                    - 각 항목 3-4문장으로 상세하고 구체적으로 작성
                    - 한국어, 존댓말 없이 전문적이면서도 격려적이고 따뜻한 톤 유지
                    - 점수에 관계없이 응시자의 노력과 성장 가능성을 인정하는 피드백
                    
                    응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                    {
                      "strengths": "강점 분석 (3-4문장)",
                      "areasToImprove": "개선점 분석 (3-4문장)",
                      "nextSteps": "다음 단계 가이드 (3-4문장)"
                    }
                    
                    [facts JSON]
                    %s
                    """.formatted(sessionJson);

            // 컨텍스트 관리
            String conversationKey = previousResponseId != null ? previousResponseId : "report_" + sessionJson.hashCode();
            List<Map<String, Object>> history = conversationHistory.computeIfAbsent(conversationKey, k -> new ArrayList<>());
            
            // 히스토리가 비어있으면 시스템 메시지 추가
            if (history.isEmpty()) {
                history.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", "당신은 엄격한 시니어 면접 코치입니다. 면접 결과를 종합하여 리포트를 작성해주세요."))
                ));
                history.add(Map.of(
                    "role", "model", 
                    "parts", List.of(Map.of("text", "네, 면접 결과를 엄격하고 정확하게 분석하여 리포트를 작성하겠습니다."))
                ));
            }
            
            history.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt))
            ));

            Map<String, Object> requestBody = Map.of(
                "contents", history,
                "generationConfig", Map.of(
                    "temperature", 0.3,
                    "maxOutputTokens", 6000,  // 4000 → 6000 (MAX_TOKENS 해결)
                    "responseMimeType", "application/json",
                    "responseSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "strengths", Map.of("type", "string"),
                            "areasToImprove", Map.of("type", "string"),
                            "nextSteps", Map.of("type", "string")
                        ),
                        "required", List.of("strengths", "areasToImprove", "nextSteps")
                    )
                )
            );

            String selectedModel = getOptimalModel("finalizeReport");
            String url = baseUrl + "/models/" + selectedModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            JsonNode root = om.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
            }
            
            JsonNode content = candidates.get(0).path("content");
            JsonNode parts = content.path("parts");
            
            if (parts.isEmpty()) {
                throw new RuntimeException("Gemini 응답에 parts가 없습니다");
            }
            
            String responseText = parts.get(0).path("text").asText();
            
            // 응답을 히스토리에 추가
            history.add(Map.of(
                "role", "model",
                "parts", List.of(Map.of("text", responseText))
            ));
            
            // 안전한 JSON 파싱
            try {
                if (!responseText.trim().startsWith("{")) {
                    throw new RuntimeException("Gemini 응답이 JSON 형식이 아닙니다: " + responseText.substring(0, Math.min(responseText.length(), 100)));
                }
                return om.readValue(responseText, Map.class);
            } catch (Exception e) {
                System.err.println("[Gemini] finalizeReport JSON 파싱 실패. 응답 텍스트: " + responseText);
                // 폴백: 기본 응답 생성
                return Map.of(
                    "strengths", "논리 전개가 명확합니다.",
                    "areasToImprove", "사례 기반 근거를 보강하세요.",
                    "nextSteps", "핵심 경험을 STAR로 1분 요약하는 연습."
                );
            }
            
        } catch (Exception e) {
            System.err.println("[AI][Gemini] finalizeReport 실패: " + e.getMessage());
            return Map.of(
                    "strengths", "논리 전개가 명확합니다.",
                    "areasToImprove", "사례 기반 근거를 보강하세요.",
                    "nextSteps", "핵심 경험을 STAR로 1분 요약하는 연습."
            );
        }
    }
}