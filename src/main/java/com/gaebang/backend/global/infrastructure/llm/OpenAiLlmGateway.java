package com.gaebang.backend.global.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.community.dto.ModerationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI LLM Gateway 구현체
 * - OpenAI GPT API를 통한 범용 LLM 기능 제공
 * - 텍스트/이미지 검열, 문서 정보 추출 등 지원
 * - DDD Infrastructure Layer에서 여러 도메인이 공통 사용
 */
@Slf4j
@Component("openAiLlmGateway")
public class OpenAiLlmGateway implements LlmGateway {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final ObjectMapper om;

    public OpenAiLlmGateway(
            @Value("${openai.api.key:}") String apiKey,
            @Value("${openai.api.model:gpt-4o-mini}") String model,
            @Value("${openai.api.base-url:https://api.openai.com/v1}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = new RestTemplate();
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.om = objectMapper;
        log.info("[LLM] OpenAiLlmGateway 초기화 완료 - 모델: {}", model);
    }

    @Override
    public ModerationResult moderateContent(String content) {
        try {
            String prompt = """
                    당신은 한국어 커뮤니티 컨텐츠 검열 전문가입니다.
                    아래 텍스트가 부적절한 내용을 포함하는지 엄격하게 판단해주세요.
                    
                    **검열 기준**:
                    1. 욕설, 비방, 혐오 표현
                    2. 성적, 폭력적 내용
                    3. 개인정보 노출 (실명, 전화번호, 주소 등)
                    4. 스팸, 광고성 내용
                    5. 불법 활동 조장
                    6. 가짜 정보 유포
                    7. 정치적 편향성 극심한 내용
                    
                    **검열 대상 텍스트**:
                    %s
                    
                    **응답 형식** (JSON):
                    {
                        "inappropriate": true/false,
                        "reason": "구체적인 검열 사유 (부적절하지 않으면 null)"
                    }
                    """.formatted(content);

            Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                    "inappropriate", Map.of("type", "boolean"),
                    "reason", Map.of("type", "string")
                ),
                "required", List.of("inappropriate", "reason")
            );

            Map<String, Object> format = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "ModerationSchema",
                    "schema", schema,
                    "strict", true
                )
            );

            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", 1000,
                "temperature", 0.1,
                "response_format", format
            );

            String url = baseUrl + "/chat/completions";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            
            JsonNode root = om.readTree(responseBody);
            JsonNode choices = root.path("choices");
            
            if (choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).path("message").path("content");
                String responseText = messageNode.asText();
                
                try {
                    Map<String, Object> moderationResult = om.readValue(responseText, Map.class);
                    boolean inappropriate = (Boolean) moderationResult.get("inappropriate");
                    String reason = (String) moderationResult.get("reason");
                    
                    log.debug("[LLM][OpenAI] 텍스트 검열 완료 - 부적절함: {}, 사유: {}", inappropriate, reason);
                    return new ModerationResult(inappropriate, reason);
                    
                } catch (Exception e) {
                    log.error("[LLM][OpenAI] 검열 응답 파싱 실패 - 응답: {}", responseText);
                    // 파싱 실패 시 안전하게 승인 처리
                    return new ModerationResult(false, null);
                }
            } else {
                log.warn("[LLM][OpenAI] API 응답에서 choices가 없습니다.");
                return new ModerationResult(false, null);
            }
            
        } catch (Exception e) {
            log.error("[LLM][OpenAI] 텍스트 검열 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 텍스트 검열 실패", e);
        }
    }

    @Override
    public ModerationResult moderateImage(String base64Image) {
        try {
            String prompt = """
                    당신은 이미지 검열 전문가입니다. 
                    제공된 이미지가 커뮤니티 가이드라인에 위반되는 부적절한 내용을 포함하는지 판단해주세요.
                    
                    **이미지 검열 기준:**
                    1. 성인/음란 콘텐츠 (노출, 성적 행위 등)
                    2. 폭력적이거나 잔혹한 이미지
                    3. 혐오 표현이나 상징 (나치, 인종차별 등)
                    4. 개인정보 노출 (신분증, 카드번호, 주소 등)
                    5. 스팸성 광고 이미지
                    6. 불법적이거나 범죄를 조장하는 이미지
                    7. 자해나 자살을 조장하는 이미지
                    
                    응답 형식 (JSON):
                    {
                        "inappropriate": true/false,
                        "reason": "구체적인 검열 사유 (부적절하지 않으면 null)"
                    }
                    """;

            Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                    "inappropriate", Map.of("type", "boolean"),
                    "reason", Map.of("type", "string")
                ),
                "required", List.of("inappropriate", "reason")
            );

            Map<String, Object> format = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "ImageModerationSchema",
                    "schema", schema,
                    "strict", true
                )
            );

            Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o-mini", // 이미지 처리 지원 모델
                "messages", List.of(
                    Map.of(
                        "role", "user", 
                        "content", List.of(
                            Map.of("type", "text", "text", prompt),
                            Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + base64Image))
                        )
                    )
                ),
                "max_tokens", 1000,
                "temperature", 0.1,
                "response_format", format
            );

            String url = baseUrl + "/chat/completions";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            
            JsonNode root = om.readTree(responseBody);
            JsonNode choices = root.path("choices");
            
            if (choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).path("message").path("content");
                String responseText = messageNode.asText();
                
                try {
                    Map<String, Object> moderationResult = om.readValue(responseText, Map.class);
                    boolean inappropriate = (Boolean) moderationResult.get("inappropriate");
                    String reason = (String) moderationResult.get("reason");
                    
                    log.debug("[LLM][OpenAI] 이미지 검열 완료 - 부적절함: {}, 사유: {}", inappropriate, reason);
                    return new ModerationResult(inappropriate, reason);
                    
                } catch (Exception e) {
                    log.error("[LLM][OpenAI] 이미지 검열 응답 파싱 실패 - 응답: {}", responseText);
                    // 파싱 실패 시 안전하게 승인 처리
                    return new ModerationResult(false, null);
                }
            } else {
                log.warn("[LLM][OpenAI] 이미지 검열 API 응답에서 choices가 없습니다.");
                return new ModerationResult(false, null);
            }
            
        } catch (Exception e) {
            log.error("[LLM][OpenAI] 이미지 검열 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 이미지 검열 실패", e);
        }
    }

    @Override
    public Map<String, Object> extractDocumentInfo(String rawText) {
        try {
            String prompt = """
                    당신은 이력서/포트폴리오 문서 분석 전문가입니다. 다음 텍스트에서 구조화된 정보를 추출해주세요.
                    
                    **개인정보 보호 원칙:**
                    - 이름, 전화번호, 이메일, 주소, 생년월일 등 개인 식별 정보는 완전히 무시
                    - 회사명, 학교명 등도 추출하지 말고 개인정보로 간주
                    
                    **추출할 정보 (개인정보 제외):**
                    1. techStacks: 기술 스택 ["Java", "Spring", "React"]
                    2. projects: 프로젝트 [{"duration":"6개월", "role":"백엔드"}]
                    3. careers: 경력 [{"duration":"3년", "role":"개발자"}] 
                    4. education: 학력 [{"degree":"학사", "major":"컴퓨터공학"}]
                    5. certifications: 자격증 ["정보처리기사"]
                    6. achievements: 수상/성과 ["해커톤 1위"]
                    7. portfolio: 포트폴리오 {"github":"활발", "blog":"있음"}
                    8. languages: 언어능력 ["한국어(원어민)", "영어(중급)"]
                    9. specialties: 전문분야 ["백엔드 개발"]
                    10. preferences: 선호도구 ["IntelliJ", "Agile"]
                    
                    **분석 대상 텍스트:**
                    %s
                    """.formatted(rawText);

            Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                    "techStacks", Map.of("type", "array", "items", Map.of("type", "string")),
                    "projects", Map.of("type", "array", "items", Map.of("type", "object")),
                    "careers", Map.of("type", "array", "items", Map.of("type", "object")),
                    "education", Map.of("type", "array", "items", Map.of("type", "object")),
                    "certifications", Map.of("type", "array", "items", Map.of("type", "string")),
                    "achievements", Map.of("type", "array", "items", Map.of("type", "string")),
                    "portfolio", Map.of("type", "object"),
                    "languages", Map.of("type", "array", "items", Map.of("type", "string")),
                    "specialties", Map.of("type", "array", "items", Map.of("type", "string")),
                    "preferences", Map.of("type", "array", "items", Map.of("type", "string"))
                )
            );

            Map<String, Object> format = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "DocumentInfoSchema",
                    "schema", schema,
                    "strict", true
                )
            );

            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", 2000,
                "temperature", 0.2,
                "response_format", format
            );

            String url = baseUrl + "/chat/completions";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            
            JsonNode root = om.readTree(responseBody);
            JsonNode choices = root.path("choices");
            
            if (choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).path("message").path("content");
                String responseText = messageNode.asText();
                
                try {
                    Map<String, Object> documentInfo = om.readValue(responseText, Map.class);
                    log.debug("[LLM][OpenAI] 문서 정보 추출 완료 - 항목 수: {}", documentInfo.size());
                    return documentInfo;
                    
                } catch (Exception e) {
                    log.error("[LLM][OpenAI] 문서 정보 추출 응답 파싱 실패 - 응답: {}", responseText);
                    return Map.of(); // 빈 맵 반환
                }
            } else {
                log.warn("[LLM][OpenAI] 문서 정보 추출 API 응답에서 choices가 없습니다.");
                return Map.of();
            }
            
        } catch (Exception e) {
            log.error("[LLM][OpenAI] 문서 정보 추출 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 문서 정보 추출 실패", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}