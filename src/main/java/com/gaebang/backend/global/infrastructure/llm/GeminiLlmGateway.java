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
 * Gemini LLM Gateway 구현체
 * - Google Gemini API를 통한 범용 LLM 기능 제공
 * - 텍스트/이미지 검열, 문서 정보 추출 등 지원
 * - DDD Infrastructure Layer에서 여러 도메인이 공통 사용
 */
@Slf4j
@Component("geminiLlmGateway")
public class GeminiLlmGateway implements LlmGateway {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String realtimeModel;
    private final String analysisModel;
    private final String baseUrl;
    private final ObjectMapper om;

    public GeminiLlmGateway(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.models.realtime:gemini-1.5-flash}") String realtimeModel,
            @Value("${gemini.api.models.analysis:gemini-2.5-flash}") String analysisModel,
            @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = new RestTemplate();
        this.apiKey = apiKey;
        this.realtimeModel = realtimeModel;
        this.analysisModel = analysisModel;
        this.baseUrl = baseUrl;
        this.om = objectMapper;
        log.info("[LLM] GeminiLlmGateway 초기화 완료 - 실시간 모델: {}, 분석 모델: {}", realtimeModel, analysisModel);
    }

    @Override
    public ModerationResult moderateContent(String content) {
        try {
            String prompt = """
                    당신은 커뮤니티 컨텐츠 검열 전문가입니다. 다음 텍스트가 부적절한 내용을 포함하고 있는지 판단해주세요.
                    
                    **검열 기준:**
                    1. 욕설, 비속어, 모욕적 표현
                    2. 성적인 내용이나 음란물
                    3. 폭력적이거나 위협적인 내용
                    4. 혐오 발언 (인종, 성별, 종교 등)
                    5. 스팸성 광고나 홍보 내용
                    6. 불법적이거나 범죄를 조장하는 내용
                    
                    **검열 대상 텍스트:**
                    %s
                    
                    **응답 요구사항:**
                    - inappropriate: true/false (부적절한 내용 포함 여부)
                    - reason: 부적절한 경우 구체적인 사유, 적절한 경우 null
                    
                    응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                    {
                      "inappropriate": true,
                      "reason": "구체적인 검열 사유"
                    }
                    또는
                    {
                      "inappropriate": false,
                      "reason": null
                    }
                    """.formatted(content);

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "parts", List.of(
                            Map.of("text", prompt)
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "maxOutputTokens", 1000,
                    "temperature", 0.1  // 일관성 있는 검열을 위해 낮은 temperature
                )
            );

            String url = baseUrl + "/models/" + analysisModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            
            JsonNode root = om.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode candidateTextNode = candidates.get(0).path("content").path("parts").get(0).path("text");
                String responseText = candidateTextNode.asText();
                
                // 마크다운 코드블록 제거
                String cleanedResponse = extractJsonFromMarkdown(responseText);
                
                try {
                    Map<String, Object> moderationResult = om.readValue(cleanedResponse, Map.class);
                    boolean inappropriate = (Boolean) moderationResult.get("inappropriate");
                    String reason = (String) moderationResult.get("reason");
                    
                    log.debug("[LLM][Gemini] 텍스트 검열 완료 - 부적절함: {}, 사유: {}", inappropriate, reason);
                    return new ModerationResult(inappropriate, reason);
                    
                } catch (Exception e) {
                    log.error("[LLM][Gemini] 검열 응답 파싱 실패 - 원본: {}, 정리 후: {}", responseText, cleanedResponse);
                    // 파싱 실패 시 안전하게 승인 처리
                    return new ModerationResult(false, null);
                }
            } else {
                log.warn("[LLM][Gemini] API 응답에서 candidates가 없습니다.");
                return new ModerationResult(false, null);
            }
            
        } catch (Exception e) {
            log.error("[LLM][Gemini] 텍스트 검열 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini 텍스트 검열 실패", e);
        }
    }

    @Override
    public ModerationResult moderateImage(String base64Image) {
        try {
            String prompt = """
                    당신은 이미지 검열 전문가입니다. 다음 이미지가 부적절한 내용을 포함하고 있는지 판단해주세요.
                    
                    **이미지 검열 기준:**
                    1. 성인/음란 콘텐츠 (노출, 성적 행위 등)
                    2. 폭력적이거나 잔혹한 이미지
                    3. 혐오 표현이나 상징 (나치, 인종차별 등)
                    4. 개인정보 노출 (신분증, 카드번호, 주소 등)
                    5. 스팸성 광고 이미지
                    6. 불법적이거나 범죄를 조장하는 이미지
                    7. 자해나 자살을 조장하는 이미지
                    
                    **응답 요구사항:**
                    - inappropriate: true/false (부적절한 내용 포함 여부)
                    - reason: 부적절한 경우 구체적인 사유, 적절한 경우 null
                    
                    응답은 반드시 다음 JSON 형식으로만 작성해주세요:
                    {
                      "inappropriate": true,
                      "reason": "구체적인 검열 사유"
                    }
                    또는
                    {
                      "inappropriate": false,
                      "reason": null
                    }
                    """;

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "parts", List.of(
                            Map.of("text", prompt),
                            Map.of("inline_data", Map.of(
                                "mime_type", "image/jpeg", // 기본값, 실제로는 동적으로 설정 가능
                                "data", base64Image
                            ))
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "maxOutputTokens", 1000,
                    "temperature", 0.1  // 일관성 있는 검열을 위해 낮은 temperature
                )
            );

            String url = baseUrl + "/models/" + realtimeModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            
            JsonNode root = om.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode candidateTextNode = candidates.get(0).path("content").path("parts").get(0).path("text");
                String responseText = candidateTextNode.asText();
                
                // 마크다운 코드블록 제거
                String cleanedResponse = extractJsonFromMarkdown(responseText);
                
                try {
                    Map<String, Object> moderationResult = om.readValue(cleanedResponse, Map.class);
                    boolean inappropriate = (Boolean) moderationResult.get("inappropriate");
                    String reason = (String) moderationResult.get("reason");
                    
                    log.debug("[LLM][Gemini] 이미지 검열 완료 - 부적절함: {}, 사유: {}", inappropriate, reason);
                    return new ModerationResult(inappropriate, reason);
                    
                } catch (Exception e) {
                    log.error("[LLM][Gemini] 이미지 검열 응답 파싱 실패 - 원본: {}, 정리 후: {}", responseText, cleanedResponse);
                    // 파싱 실패 시 안전하게 승인 처리
                    return new ModerationResult(false, null);
                }
            } else {
                log.warn("[LLM][Gemini] 이미지 검열 API 응답에서 candidates가 없습니다.");
                return new ModerationResult(false, null);
            }
            
        } catch (Exception e) {
            log.error("[LLM][Gemini] 이미지 검열 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini 이미지 검열 실패", e);
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
                    
                    응답은 반드시 다음 JSON 형식으로 작성해주세요:
                    {
                      "techStacks": ["Java", "Spring"],
                      "projects": [{"duration":"6개월", "role":"백엔드"}],
                      "careers": [{"duration":"3년", "role":"개발자"}],
                      "education": [{"degree":"학사", "major":"컴퓨터공학"}],
                      "certifications": ["정보처리기사"],
                      "achievements": ["해커톤 1위"],
                      "portfolio": {"github":"있음", "blog":"없음"},
                      "languages": ["한국어(원어민)"],
                      "specialties": ["백엔드 개발"],
                      "preferences": ["IntelliJ IDEA"]
                    }
                    """.formatted(rawText);

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "parts", List.of(
                            Map.of("text", prompt)
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "maxOutputTokens", 2000,
                    "temperature", 0.2
                )
            );

            String url = baseUrl + "/models/" + analysisModel + ":generateContent?key=" + apiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            
            JsonNode root = om.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode candidateTextNode = candidates.get(0).path("content").path("parts").get(0).path("text");
                String responseText = candidateTextNode.asText();
                
                // 마크다운 코드블록 제거
                String cleanedResponse = extractJsonFromMarkdown(responseText);
                
                try {
                    Map<String, Object> documentInfo = om.readValue(cleanedResponse, Map.class);
                    log.debug("[LLM][Gemini] 문서 정보 추출 완료 - 항목 수: {}", documentInfo.size());
                    return documentInfo;
                    
                } catch (Exception e) {
                    log.error("[LLM][Gemini] 문서 정보 추출 응답 파싱 실패 - 원본: {}", responseText);
                    return Map.of(); // 빈 맵 반환
                }
            } else {
                log.warn("[LLM][Gemini] 문서 정보 추출 API 응답에서 candidates가 없습니다.");
                return Map.of();
            }
            
        } catch (Exception e) {
            log.error("[LLM][Gemini] 문서 정보 추출 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini 문서 정보 추출 실패", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    /**
     * Gemini API 응답에서 마크다운 코드블록을 제거하고 순수 JSON을 추출
     * 
     * @param response Gemini API 응답 텍스트
     * @return 정리된 JSON 문자열
     */
    private String extractJsonFromMarkdown(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "{}";
        }
        
        // 마크다운 코드블록 패턴 제거
        String cleaned = response.trim();
        
        // ```json ... ``` 패턴 제거
        if (cleaned.startsWith("```json") && cleaned.endsWith("```")) {
            cleaned = cleaned.substring(7, cleaned.length() - 3).trim();
        }
        // ``` ... ``` 패턴 제거  
        else if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            cleaned = cleaned.substring(3, cleaned.length() - 3).trim();
        }
        
        // 첫 번째 { 부터 마지막 } 까지만 추출 (추가 텍스트 제거)
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        
        if (firstBrace != -1 && lastBrace != -1 && firstBrace <= lastBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
        }
        
        return cleaned;
    }
}