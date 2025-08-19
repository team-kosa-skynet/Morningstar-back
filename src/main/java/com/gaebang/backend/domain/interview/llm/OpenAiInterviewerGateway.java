package com.gaebang.backend.domain.interview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interview.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interview.util.PlanParser;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component("openAiInterviewerGateway")
public class OpenAiInterviewerGateway implements InterviewerAiGateway {

    private static final Dotenv dotenv = Dotenv.load();
    
    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final PlanParser planParser;
    private final ObjectMapper om;

    public OpenAiInterviewerGateway(
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
            PlanParser planParser,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = new RestTemplate();
        
        // Dotenv로 직접 .env 파일에서 API 키 로드
        String dotenvKey = dotenv.get("OPENAI_API_KEY");
        String systemKey = System.getenv("OPENAI_API_KEY");
        
        String finalApiKey = null;
        if (dotenvKey != null && !dotenvKey.isBlank()) {
            finalApiKey = dotenvKey;
            System.out.println("[OpenAI] API key loaded from .env file: OK (length: " + finalApiKey.length() + ")");
        } else if (systemKey != null && !systemKey.isBlank()) {
            finalApiKey = systemKey;
            System.out.println("[OpenAI] API key loaded from system env: OK (length: " + finalApiKey.length() + ")");
        }
        
        // API 키 최종 검증
        if (finalApiKey == null || finalApiKey.isBlank()) {
            throw new IllegalStateException("[OpenAI] API key is missing. " +
                "Dotenv: " + (dotenvKey != null ? "'" + dotenvKey + "'" : "null") + ", " +
                "System.getenv: " + (systemKey != null ? "OK" : "null"));
        }
        this.apiKey = finalApiKey.trim();
        
        this.model = model;
        this.planParser = planParser;
        this.om = objectMapper;
    }

    @PostConstruct
    void log() {
        System.out.println("[AI] Using OpenAiInterviewerAiGateway");
        System.out.println("[AI] API Key status: " + (apiKey != null && !apiKey.isBlank() ? "OK (length: " + apiKey.length() + ")" : "MISSING"));
        System.out.println("[AI] Model: " + model);
    }

    @Override
    public String generateGreeting(String displayName) {
        return displayName + "님, 안녕하세요. 이번 면접을 진행할 면접관입니다. 만나서 반갑습니다. 질문을 시작하겠습니다.";
    }

    @Override
    public Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates) {
        try {
            // OpenAI로 다양한 맞춤 질문 생성 (문서 있음/없음 모두 처리)
            return generateQuestionsWithOpenAI(role, profileSnapshotJson);
            
        } catch (Exception e) {
            System.err.println("[AI] 질문 생성 실패, 기본 질문 사용");
            System.err.println("  - 역할: " + role);
            System.err.println("  - 프로필 스냅샷: " + (profileSnapshotJson != null ? "있음(" + profileSnapshotJson.length() + "자)" : "없음"));
            System.err.println("  - 후보 질문: " + (candidates != null ? candidates.size() + "개" : "없음"));
            System.err.println("  - 에러: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("  - 원인: " + e.getCause().getMessage());
            }
            e.printStackTrace();
            // 폴백으로 후보 질문 활용
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
    
    private Map<String, Object> generateQuestionsWithOpenAI(String role, String profileSnapshotJson) throws Exception {
        String systemPrompt = buildSystemPrompt(role, profileSnapshotJson);
        
        // 다양성을 위한 시드 생성 (현재 시간 + 랜덤 + 역할 해시)
        long currentTime = System.currentTimeMillis();
        int randomSeed = new java.util.Random().nextInt(10000);
        int roleHash = role.hashCode();
        int seed = Math.abs((int) (currentTime + randomSeed + roleHash)) % 100000;
        
        String userPrompt = String.format(
            "위 조건에 맞는 면접 질문 10개를 JSON 형식으로 생성해주세요. " +
            "⚠️ 중요: 시드값 %d를 활용하여 매번 완전히 다른 관점의 질문을 생성하세요. " +
            "같은 역할이라도 절대 비슷한 질문 패턴을 반복하지 마세요. " +
            "창의적이고 다양한 각도에서 접근하세요.", 
            seed
        );

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "interview_questions",
                    "schema", Map.of(
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
                                    "required", List.of("idx", "type", "text", "intent", "guides"),
                                    "additionalProperties", false
                                )
                            )
                        ),
                        "required", List.of("questions"),
                        "additionalProperties", false
                    )
                )
            ),
            "temperature", 0.8,  // 다양성을 위해 temperature 증가
            "max_tokens", 6000   // 질문+의도+가이드 생성을 위해 대폭 증가
        );

        // API 키 검증
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("[OpenAI] API key is null. Check environment variables: OPENAI_API_KEY=" + System.getenv("OPENAI_API_KEY"));
        }

        // RestTemplate으로 HTTP 요청
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        ResponseEntity<String> response = restTemplate.postForEntity(
            "https://api.openai.com/v1/chat/completions", 
            entity, 
            String.class
        );

        return parseOpenAiResponse(response.getBody());
    }
    
    private String buildSystemPrompt(String role, String profileSnapshotJson) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("당신은 전문 기술 면접관입니다. ");
        
        // 역할별 맞춤 프롬프트
        String roleSpecificPrompt = getRoleSpecificPrompt(role);
        prompt.append(roleSpecificPrompt);
        
        // 문서 기반 개인화 vs 일반 질문
        boolean hasDocument = profileSnapshotJson != null && !profileSnapshotJson.equals("{}");
        
        if (hasDocument) {
            // 문서가 있는 경우: 개인화된 질문
            prompt.append("\n\n=== 지원자 정보 ===\n");
            prompt.append(profileSnapshotJson);
            prompt.append("\n\n📋 **개인화 질문 생성 지침:**");
            prompt.append("\n- 지원자의 실제 경험과 기술스택을 바탕으로 구체적인 질문 생성");
            prompt.append("\n- 문서에 언급된 프로젝트나 기술에 대한 심화 질문 포함");
            prompt.append("\n- 지원자의 경력 수준에 맞는 적절한 난이도 조절");
        } else {
            // 문서가 없는 경우: 다양한 일반 질문
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
    
    private Map<String, Object> parseOpenAiResponse(String response) throws Exception {
        JsonNode root = om.readTree(response);
        JsonNode choices = root.path("choices");
        
        if (choices.isEmpty()) {
            throw new RuntimeException("OpenAI 응답에 choices가 없습니다");
        }
        
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").asText();
        
        return om.readValue(content, Map.class);
    }
    
    private Map<String, Object> getFallbackQuestions() {
        // OpenAI 실패시 사용할 기본 질문들
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

        // JSON Schema (코칭팁만 생성)
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "coachingTips", Map.of("type", "string")
                ),
                "required", List.of("coachingTips"),
                "additionalProperties", false
        );

        Map<String, Object> format = Map.of(
                "type", "json_schema",
                "name", "AiTurnFeedback",
                "schema", schema,
                "strict", true
        );

        String prompt = """
                면접 코치로서 간결한 피드백을 제공하세요.
                질문: %s
                답변: %s
                
                1-2문장으로 구체적인 개선점을 제시하세요.
                """.formatted(q.text(), transcript);

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
            return new AiTurnFeedbackDto(tips, responseId);
        }

        String text = findText(root);
        return new AiTurnFeedbackDto(text.isBlank() ? "핵심부터 1~2문장으로." : text, responseId);
    }

    @Override
    public Map<String, Object> generateQuestionIntentAndGuides(String questionType, String questionText, String role) throws Exception {
        try {
            Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                    "intent", Map.of("type", "string"),
                    "guides", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string")
                    )
                ),
                "required", List.of("intent", "guides"),
                "additionalProperties", false
            );

            Map<String, Object> format = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "QuestionIntentAndGuides",
                    "schema", schema,
                    "strict", true
                )
            );

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
                    """.formatted(role, questionType, questionText, roleGuide, typeGuide);

            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content", "당신은 전문 면접 코치입니다. 질문 의도와 답변 가이드를 JSON 형식으로 정확히 생성합니다."),
                    Map.of("role", "user", "content", prompt)
                ),
                "response_format", format,
                "temperature", 0.3,
                "max_tokens", 1000
            );

            // RestTemplate으로 HTTP 요청
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/chat/completions", 
                entity, 
                String.class
            );

            return parseQuestionIntentResponse(response.getBody());
            
        } catch (Exception e) {
            System.err.println("[AI] 질문 의도/가이드 생성 실패");
            System.err.println("  - 질문 유형: " + questionType);
            System.err.println("  - 역할: " + role);
            System.err.println("  - 에러: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("  - 원인: " + e.getCause().getMessage());
            }
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

    private Map<String, Object> parseQuestionIntentResponse(String response) throws Exception {
        JsonNode root = om.readTree(response);
        JsonNode choices = root.path("choices");
        
        if (choices.isEmpty()) {
            throw new RuntimeException("OpenAI 응답에 choices가 없습니다");
        }
        
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").asText();
        
        return om.readValue(content, Map.class);
    }

    private Map<String, Object> getFallbackIntentAndGuides(String questionType, String role) {
        String intent = switch (questionType) {
            case "BEHAVIORAL" -> "지원자의 협업 능력과 문제 해결 경험을 통해 조직 적합성을 평가합니다.";
            case "TECHNICAL" -> "지원자의 기술적 깊이와 실무 적용 능력을 확인합니다.";
            case "SYSTEM_DESIGN" -> "대규모 시스템 설계 능력과 아키텍처 이해도를 평가합니다.";
            case "TROUBLESHOOT" -> "문제 상황에서의 분석 능력과 해결 과정을 확인합니다.";
            default -> "지원자의 역량과 적합성을 종합적으로 평가합니다.";
        };

        // 역할별/질문유형별 맞춤형 가이드 생성
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

        // 공통 + 역할별 + 질문유형별 가이드 조합 (최대 3개)
        List<String> combined = new java.util.ArrayList<>(commonGuides);
        combined.addAll(roleSpecificGuides);
        combined.addAll(questionTypeGuides);
        
        return combined.size() > 3 ? combined.subList(0, 3) : combined;
    }

    @Override
    public Map<String, Object> generateBatchEvaluation(String evaluationData, String role, String previousResponseId) throws Exception {
        try {
            String prompt = """
                    당신은 엄격한 시니어 면접관입니다. 아래 전체 면접 내용을 종합하여 정확한 점수를 산정해주세요.
                    
                    **평가 데이터:**
                    %s
                    
                    **평가 지표 (0-100점):**
                    - clarity: 명확한 의사소통 능력
                    - structure_STAR: 체계적인 답변 구조 (상황-과제-행동-결과)
                    - tech_depth: 기술적 깊이와 전문성
                    - tradeoff: 트레이드오프 인식과 판단력
                    - root_cause: 근본 원인 분석 능력
                    
                    **점수 기준:**
                    - 0-20점: 매우 부족 (답변 회피, 기본 지식 부족)
                    - 21-40점: 부족 (피상적 이해)
                    - 41-60점: 보통 (기본 수준)
                    - 61-80점: 좋음 (실무 활용 가능)
                    - 81-100점: 우수 (깊이 있는 전문성)
                    
                    **주의사항:**
                    - 전체 답변의 일관성과 패턴을 종합 고려
                    - "잘 모르겠습니다" 답변은 해당 지표에서 감점
                    - 역할(%s)에 맞는 기술적 깊이로 평가
                    - 각 지표별로 구체적 근거와 함께 점수 산정
                    """.formatted(evaluationData, role);

            Map<String, Object> schema = Map.of(
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
            );

            Map<String, Object> format = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "BatchEvaluationSchema",
                    "schema", schema,
                    "strict", true
                )
            );

            // OpenAI Responses API 사용
            String responseId = null;
            if (previousResponseId != null && !previousResponseId.isBlank()) {
                responseId = previousResponseId;
            }

            // OpenAI API 직접 호출 (배치 평가용)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o-mini",
                "messages", List.of(
                    Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.1,
                "max_tokens", 2000,
                "response_format", format
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> apiResponse = restTemplate.postForEntity(
                "https://api.openai.com/v1/chat/completions", entity, String.class);

            JsonNode root = om.readTree(apiResponse.getBody());
            JsonNode choices = root.path("choices");
            
            if (choices.isEmpty()) {
                throw new RuntimeException("OpenAI 응답에 choices가 없습니다");
            }
            
            String content = choices.get(0).path("message").path("content").asText();
            Map<String, Object> parsed = om.readValue(content, Map.class);
            System.out.println("[AI][batch] OpenAI 배치 평가 완료");
            
            return parsed;

        } catch (Exception e) {
            System.err.println("[AI][OpenAI] generateBatchEvaluation 실패: " + e.getMessage());
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
    }

    @Override
    public Map<String, Object> extractDocumentInfo(String documentText) throws Exception {
        try {
            String prompt = """
                    당신은 전문 HR 담당자입니다. 아래 문서에서 면접에 필요한 정보를 추출해주세요.
                    
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
                    
                    **중요 지침:**
                    - 개인 식별 정보 절대 포함 금지 (이름, 회사명, 학교명 등)
                    - 맥락을 고려한 정확한 정보만 추출
                    - 애매한 정보는 포함하지 않음
                    - 기술 스택은 표준 명칭으로 통일
                    """.formatted(documentText);

            Map<String, Object> schema = Map.of(
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
                    )
                ),
                "required", List.of("techStacks", "projects", "careers")
            );

            Map<String, Object> format = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "DocumentExtractionSchema",
                    "schema", schema,
                    "strict", true
                )
            );

            // OpenAI API 직접 호출
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o-mini",
                "messages", List.of(
                    Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.1,
                "max_tokens", 3000,
                "response_format", format
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> apiResponse = restTemplate.postForEntity(
                "https://api.openai.com/v1/chat/completions", entity, String.class);

            JsonNode root = om.readTree(apiResponse.getBody());
            JsonNode choices = root.path("choices");
            
            if (choices.isEmpty()) {
                throw new RuntimeException("OpenAI 응답에 choices가 없습니다");
            }
            
            String content = choices.get(0).path("message").path("content").asText();
            Map<String, Object> parsed = om.readValue(content, Map.class);
            System.out.println("[AI][OpenAI] 문서 추출 완료");
            
            return parsed;

        } catch (Exception e) {
            System.err.println("[AI][OpenAI] extractDocumentInfo 실패: " + e.getMessage());
            throw e; // 상위에서 폴백 처리
        }
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
                    당신은 엄격한 시니어 면접 코치입니다. 직전까지의 대화 맥락은 previous_response_id로 제공됩니다.
                    아래 facts는 서버가 계산/정리한 공식 정보이므로 사실로 간주하고 반드시 반영하세요.
                    
                    점수 기준 (100점 만점):
                    - 0-20점: 매우 부족 (답변 회피, 기본 지식 부족)
                    - 21-40점: 부족 (피상적 이해)
                    - 41-60점: 보통 (기본 수준)
                    - 61-80점: 좋음 (실무 활용 가능)
                    - 81-100점: 우수 (깊이 있는 전문성)
                    
                    엄격한 평가 원칙:
                    1. overallScore와 subscores를 정확히 반영하여 평가
                    2. 낮은 점수(40점 이하)에는 긍정적 표현 금지
                    3. "잘 모르겠습니다" 답변 시 실제 문제점 지적
                    4. 실제 답변 내용(transcriptExcerpt)을 근거로 평가
                    
                    작성 요구사항:
                    - strengths: 실제 높은 점수를 받은 지표만 언급. 낮은 점수(40점 이하)에는 "기본기 부족으로 강점을 찾기 어려움" 표현
                    - areasToImprove: 낮은 점수 지표를 중심으로 구체적 문제점 지적
                    - nextSteps: 점수에 맞는 현실적 개선 방안 (기초부터 or 심화 학습)
                    - 각 항목 2-3문장, 한국어, 존댓말 없이 간결하게
                    - 점수와 모순되는 긍정적 표현 절대 금지
                    
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1" + path,
                entity,
                String.class
            );
            
            if (response.getStatusCode().isError()) {
                System.err.println("[OpenAI][HTTP " + response.getStatusCode() + "] " + response.getBody());
                throw new RuntimeException("OpenAI error: " + response.getBody());
            }
            
            return response.getBody();
        } catch (Exception e) {
            System.err.println("[OpenAI] postJson failed: " + e.getMessage());
            throw new RuntimeException("OpenAI API call failed", e);
        }
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

    private String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("[OpenAI] API key is missing. Check spring.ai.openai.api-key or OPENAI_API_KEY");
        }
        return apiKey.trim();
    }
}
