# llm — LLM 게이트웨이 (포트-어댑터 패턴)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [community.md](community.md) · [aibot.md](aibot.md)
>
> **파일은 3개인데 코드는 2,680줄** — 이 프로젝트에서 가장 무거운 곳입니다.
> `GeminiAiAdapter` 1,540줄 + `OpenAiAiAdapter` 1,137줄 + 포트 인터페이스 1개.
>
> **아키텍처 의도(포트-어댑터)는 정확하고, 구현이 그 의도를 배신합니다.**
> 그리고 이 프로젝트에서 가장 위험한 두 가지가 여기 있습니다:
> **타임아웃 없는 HTTP 호출**과 **검열의 fail-open 폴백**입니다.

---

## 파일 지도

```
domain/llm/
├── port/
│   └── InterviewerAiGateway.java          인터페이스 (메서드 11개)
└── infrastructure/
    ├── GeminiAiAdapter.java     1,540줄  @Component("geminiInterviewerGateway")
    └── OpenAiAiAdapter.java     1,137줄  @Component("openAiInterviewerGateway")
```

**누가 이 포트를 쓰나**

```
interview 도메인          → generatePlan, nextTurn, finalizeReport,
                            generateQuestionIntentAndGuides, generateBatchEvaluation,
                            extractDocumentInfo, generateGreeting
community 도메인          → moderateContent, moderateImage   (TextModerationService)
aibot 도메인              → generateQuestionAnswer
```

---

# 1. 포트-어댑터 패턴이란

## 1-1. 문제 상황

LLM을 직접 호출하는 코드를 서비스에 그대로 넣으면 이렇게 됩니다.

```java
// ❌ 이렇게 짜면
@Service
public class InterviewService {
    public void nextTurn(...) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;
        Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", ...)));
        String json = restTemplate.postForEntity(url, entity, String.class).getBody();
        JsonNode root = om.readTree(json);
        String text = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        ...
    }
}
```

**문제**

1. 면접 로직과 Gemini API 스펙이 뒤섞입니다
2. OpenAI로 바꾸려면 서비스 코드를 전부 고쳐야 합니다
3. 테스트할 때 실제 API를 호출해야 합니다

## 1-2. 해결 — 포트(인터페이스)로 경계를 만든다

```java
// port/InterviewerAiGateway.java — "무엇을 할 수 있는가"만 선언
public interface InterviewerAiGateway {
    String generateGreeting(String displayName);
    Map<String, Object> generatePlan(String role, String profileSnapshotJson, List<Map<String, Object>> candidates);
    AiTurnFeedbackDto nextTurn(String planJson, int questionIndex, String transcript,
                               String recentSummaryJson, String previousResponseId) throws Exception;
    Map<String, Object> finalizeReport(String sessionJson, String previousResponseId);
    Map<String, Object> generateQuestionIntentAndGuides(String questionType, String questionText, String role) throws Exception;
    Map<String, Object> generateBatchEvaluation(String evaluationData, String role, String previousResponseId) throws Exception;
    Map<String, Object> extractDocumentInfo(String rawText) throws Exception;
    ModerationResult moderateContent(String content) throws Exception;
    ModerationResult moderateImage(String base64Image) throws Exception;
    String generateQuestionAnswer(String title, String content) throws Exception;
    String getProviderName();
}
```

```
       [비즈니스 로직]                          [기술 세부사항]
┌─────────────────────────┐             ┌──────────────────────────┐
│ InterviewService        │             │ GeminiAiAdapter          │
│ TextModerationService   │──▶  포트  ◀──│ OpenAiAiAdapter          │
│ AiBotService            │  (인터페이스) │  (HTTP, JSON, 프롬프트)  │
└─────────────────────────┘             └──────────────────────────┘
   Gemini를 전혀 모른다                    갈아끼울 수 있다
```

**이것이 헥사고날 아키텍처(Ports & Adapters)** 입니다.
"안쪽(도메인)은 바깥(기술)을 모르고, 바깥이 안쪽의 인터페이스를 구현한다"가 핵심입니다.
이를 **의존성 역전 원칙(DIP)** 이라고 합니다.

## 1-3. 🟢 잘 작동하는 부분 — 제공자 교체와 폴백

```java
@Component("geminiInterviewerGateway")
public class GeminiAiAdapter implements InterviewerAiGateway { ... }

@Component("openAiInterviewerGateway")
public class OpenAiAiAdapter implements InterviewerAiGateway { ... }
```

**`@Component("이름")` 으로 빈 이름을 명시**했으므로 주입 시 골라 쓸 수 있습니다.

```java
// TextModerationService — Primary/Fallback 이중화
public TextModerationService(
        @Qualifier("geminiInterviewerGateway") InterviewerAiGateway primaryLlmGateway,
        @Qualifier("openAiInterviewerGateway") InterviewerAiGateway fallbackLlmGateway) { ... }
```

**포트 덕분에 "Gemini 실패 시 OpenAI로 폴백"이 서비스 코드 몇 줄로 구현됩니다.**
→ [community.md 6-5](community.md#6-5--폴백-전략--primary--fallback--보수적-차단)

이 패턴의 이점이 실제로 발휘된 좋은 예입니다.

**`@Qualifier`란**: 같은 타입의 빈이 여러 개일 때 이름으로 지정합니다.
없으면 `NoUniqueBeanDefinitionException`이 발생합니다.

| 방법 | 설명 |
|---|---|
| `@Qualifier("빈이름")` | 이름으로 명시 (이 프로젝트) |
| `@Primary` | "기본값" 지정. `@Qualifier` 없으면 이걸 씀 |
| 필드명 매칭 | 필드 이름이 빈 이름과 같으면 자동 선택 |

---

# 2. 🔴 포트 설계의 문제 — 인터페이스가 오염되었다

## 2-1. 이름과 내용이 다르다

```java
/**
 * 면접 AI 전용 인터페이스 (Port)
 * - Interview Domain에서 AI 기능을 사용하기 위한 Port
 */
public interface InterviewerAiGateway {
```

**"면접 AI 전용"이라고 적혀 있는데 실제 내용은 이렇습니다.**

| 메서드 | 실제 소비자 | 면접과 관계 |
|---|---|---|
| `generateGreeting` | interview | ✅ |
| `generatePlan` | interview | ✅ |
| `nextTurn` | interview | ✅ |
| `finalizeReport` | interview | ✅ |
| `generateQuestionIntentAndGuides` | interview | ✅ |
| `generateBatchEvaluation` | interview | ✅ |
| `extractDocumentInfo` | interview | ✅ |
| `moderateContent` | **community** | ❌ 게시글 검열 |
| `moderateImage` | **community** | ❌ 이미지 검열 |
| `generateQuestionAnswer` | **aibot** | ❌ 커뮤니티 AI 답변 |
| `getProviderName` | 공통 | — |

**11개 중 3개가 면접과 무관합니다.**

## 2-2. 왜 문제인가 — 인터페이스 분리 원칙(ISP)

> **"클라이언트는 자신이 사용하지 않는 메서드에 의존해서는 안 된다"**

`TextModerationService`는 검열만 필요한데, `InterviewerAiGateway`를 주입받으면
**면접 관련 메서드 7개까지 함께 딸려옵니다.**

실질적 피해:

1. **검열용 어댑터를 새로 만들 수 없습니다.**
   "검열만 하는 저렴한 모델 어댑터"를 추가하려면
   `nextTurn`, `finalizeReport` 등 7개를 전부 구현해야 합니다(빈 껍데기로라도).
2. **테스트 목(mock)을 만들기 어렵습니다.** 11개 메서드를 다 구현해야 합니다.
3. **변경 파급이 큽니다.** 면접 요구사항이 바뀌어 `nextTurn` 시그니처를 고치면
   `community`·`aibot`도 재컴파일 대상이 됩니다.

## 2-3. 더 심각한 문제 — 포트가 다른 도메인 타입에 의존한다

```java
package com.gaebang.backend.domain.llm.port;

import com.gaebang.backend.domain.community.dto.ModerationResult;          // 🔴
import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto; // 🔴
```

**포트-어댑터 패턴의 핵심은 "안쪽이 바깥을 모른다"인데, 방향이 거꾸로입니다.**

```
[의도한 방향]                        [실제 방향]
community ──▶ llm(port)             community ──▶ llm(port)
interview ──▶ llm(port)                  ▲            │
                                         └────────────┘
                                      llm이 community를 import!
```

`llm`은 `community`, `interview`에 의존하고, 그 둘도 `llm`에 의존합니다.
**순환 의존(circular dependency)** 입니다.

컴파일은 됩니다(같은 모듈이므로). 하지만:

- `llm`을 별도 모듈/라이브러리로 분리할 수 없습니다
- `community` 도메인을 지우면 `llm`이 컴파일되지 않습니다
- "지원 서브도메인"이라는 주석의 의도가 성립하지 않습니다

**포트는 자기 타입을 정의해야 합니다.**

```java
// llm/port/dto/ContentModerationVerdict.java — llm이 소유하는 타입
public record ContentModerationVerdict(boolean inappropriate, String reason) { }

// llm/port/ContentModerationGateway.java — 검열 전용 포트
public interface ContentModerationGateway {
    ContentModerationVerdict moderateText(String content);
    ContentModerationVerdict moderateImage(String base64Image);
    String getProviderName();
}
```

그리고 `community`가 자기 타입으로 변환합니다.

```java
// community/service/TextModerationService
ContentModerationVerdict verdict = gateway.moderateText(content);
return new ModerationResult(verdict.inappropriate(), verdict.reason());
```

## 2-4. 권장 — 포트를 3개로 쪼갠다

```java
llm/port/
├── InterviewerAiGateway.java        면접 7개 메서드 (interview 전용)
├── ContentModerationGateway.java    검열 2개 메서드 (community 전용)
└── QuestionAnsweringGateway.java    답변 1개 메서드 (aibot 전용)
```

어댑터 하나가 **세 인터페이스를 모두 구현**해도 됩니다.

```java
@Component("geminiInterviewerGateway")
public class GeminiAiAdapter
        implements InterviewerAiGateway, ContentModerationGateway, QuestionAnsweringGateway {
    ...
}
```

**소비자는 자기가 필요한 인터페이스만 주입받습니다.**

```java
// TextModerationService — 검열 2개 메서드만 보인다
public TextModerationService(
        @Qualifier("geminiInterviewerGateway") ContentModerationGateway primary,
        @Qualifier("openAiInterviewerGateway") ContentModerationGateway fallback) { ... }
```

**클래스는 그대로 두고 인터페이스만 쪼개는 것**이므로 리팩터링 비용이 작습니다.

---

# 3. 🔴 타임아웃 없는 `RestTemplate`

## 3-1. 문제

```java
public GeminiAiAdapter(
        @Value("${gemini.api.key}") String apiKey,
        ...
        PlanParser planParser,
        ObjectMapper objectMapper) {
    this.restTemplate = new RestTemplate();          // 🔴 직접 생성 + 설정 없음
    ...
}
```

**`new RestTemplate()`의 기본 타임아웃은 "무한"입니다.**

`SimpleClientHttpRequestFactory`의 `connectTimeout`과 `readTimeout` 기본값이 `0`이고,
`0`은 "제한 없음"을 뜻합니다.

```
LLM API가 응답을 주지 않으면?
  → 스레드가 영원히 대기
  → moderationExecutor(Core 5/Max 10) 스레드가 하나씩 소진
  → 10개가 다 묶이면 검열이 완전히 멈춤
  → 큐(100)가 차면 게시글 검열이 조용히 버려짐
```

## 3-2. 이 프로젝트에는 이미 설정된 빈이 있다

```java
// global/config/HttpClientConfig.java
@Bean
public RestTemplate restTemplate() {          // Payment 도메인에서 사용
    return new RestTemplate();                // ⚠️ 이것도 설정이 없다
}

@Bean
public RestClient restClient() {              // Question 도메인에서 사용
    // 커넥션 풀 100 + 타임아웃 설정 있음
}
```

**`restTemplate` 빈도 타임아웃이 없습니다.** 그리고 어댑터는 그 빈조차 쓰지 않고
`new RestTemplate()`으로 또 만듭니다. **커넥션 풀 없이 매 요청마다 TCP 연결을 새로 맺습니다.**

## 3-3. 고치는 방법

```java
// global/config/HttpClientConfig.java
@Bean("llmRestTemplate")
public RestTemplate llmRestTemplate() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));    // 연결 10초
    factory.setReadTimeout(Duration.ofSeconds(60));       // 응답 60초
    return new RestTemplate(factory);
}
```

```java
// GeminiAiAdapter
public GeminiAiAdapter(@Qualifier("llmRestTemplate") RestTemplate restTemplate, ...) {
    this.restTemplate = restTemplate;                     // 주입받아 재사용
}
```

**빌더를 쓰면 더 간결합니다.**

```java
@Bean("llmRestTemplate")
public RestTemplate llmRestTemplate(RestTemplateBuilder builder) {
    return builder
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(60))
            .build();
}
```

> **왜 이게 [community.md 6-3](community.md#6-3--timelimiter가-동작하지-않는다)과 연결되는가**
>
> `TextModerationService`에 `@TimeLimiter(timeout-duration: 10s)`가 붙어 있지만
> 동기 호출을 `completedFuture`로 감싸서 **동작하지 않습니다.**
> 그리고 여기 HTTP 타임아웃도 무한입니다.
>
> **결과: LLM 호출에 시간 제한이 하나도 없습니다.**
> 두 곳 중 어디든 하나는 반드시 고쳐야 합니다.

---

# 4. 🔴 검열 폴백이 fail-open이다

## 4-1. 문제 코드

```java
@Override
public ModerationResult moderateContent(String content) throws Exception {
    ...
    String responseText = parts.get(0).path("text").asText();

    try {
        Map<String, Object> parsedResponse = om.readValue(responseText, Map.class);
        boolean inappropriate = (Boolean) parsedResponse.getOrDefault("inappropriate", false);
        String reason = (String) parsedResponse.get("reason");
        return new ModerationResult(inappropriate, reason);

    } catch (Exception e) {
        System.err.println("[Gemini] moderateContent JSON 파싱 실패. 응답 텍스트: " + responseText);
        return new ModerationResult(false, null);        // 🔴 "부적절하지 않음"으로 통과시킨다
    }
}
```

`moderateImage`도 동일합니다.

## 4-2. 왜 위험한가

**JSON 파싱에 실패하면 "문제 없는 콘텐츠"로 판정됩니다.**

이는 상위 계층의 정책과 **정면으로 모순**됩니다.

```java
// TextModerationService.fallbackModeration — fail-closed (보수적 차단)
catch (Exception e) {
    // 모든 LLM Gateway 실패 시 보수적으로 차단 (보안 우선)
    return CompletableFuture.completedFuture(
            new ModerationResult(true, "LLM 검열 시스템 전체 장애 - 관리자 검토 필요"));
}
```

**상위는 "못 하면 막는다", 하위는 "못 하면 통과시킨다".**
그리고 **하위가 예외를 던지지 않으므로 상위의 폴백이 발동하지 않습니다.**

```
파싱 실패 → 어댑터가 (false, null) 반환 → 정상 응답으로 간주
→ 서킷브레이커도 실패로 기록하지 않음 → 폴백 없음 → 게시글 통과 ✅
```

## 4-3. 공격 가능성

Gemini는 `responseSchema`로 구조화 출력을 강제하고 있어 파싱 실패가 드물지만,
**게시글 본문이 프롬프트에 그대로 삽입됩니다.**

```java
String prompt = """
        당신은 컨텐츠 검열 전문가입니다. ...
        분석 대상 텍스트:
        %s
        ...
        """.formatted(content);        // ★ 사용자 입력이 프롬프트에 직접 들어간다
```

**프롬프트 인젝션**으로 모델이 스키마를 벗어난 출력을 하도록 유도하면 검열을 우회할 수 있습니다.
안전 필터에 걸려 `candidates`가 비면 `RuntimeException`이 나서 폴백이 작동하지만,
파싱 단계에서 실패하면 그대로 통과합니다.

## 4-4. 고치는 방법

```java
} catch (Exception e) {
    log.error("[Gemini] 검열 응답 파싱 실패 - 응답: {}", responseText, e);
    throw new IllegalStateException("검열 응답을 해석할 수 없습니다", e);
    // → 예외를 던져 상위의 fail-closed 폴백이 작동하게 한다
}
```

**원칙: 보안 판정에서 "모르겠다"는 "안전하다"가 아닙니다.**
판정할 수 없으면 상위에 알려 정책 결정을 맡겨야 합니다.

> 프롬프트 인젝션 자체를 줄이려면 **사용자 입력을 프롬프트 본문이 아니라
> 별도 파트로 분리**하는 것이 좋습니다. Gemini는 `contents`에 여러 `parts`를 넣을 수 있습니다.
> 완전한 방어는 아니지만 지시문과 데이터의 경계가 명확해집니다.

---

# 5. 🟠 `conversationHistory` — 메모리 누수

```java
@Component("geminiInterviewerGateway")
public class GeminiAiAdapter implements InterviewerAiGateway {
    ...
    // 컨텍스트 관리를 위한 대화 기록 저장
    private final Map<String, List<Map<String, Object>>> conversationHistory = new ConcurrentHashMap<>();
```

**싱글턴 빈의 가변 상태**입니다. → [email.md 1장](email.md#1-스프링-빈은-싱글턴이다--이-도메인의-모든-문제의-출발점)

**`ConcurrentHashMap`을 쓴 것은 잘했습니다** (`EmailService`는 `HashMap`을 씁니다).
스레드 안전성 문제는 없습니다.

**하지만 제거 정책이 없습니다.**

```
면접 세션마다 대화 기록이 쌓임 → 세션이 끝나도 지워지지 않음
→ 세션 1,000개면 1,000개 엔트리 × (질문+답변 텍스트)
→ 재기동할 때까지 해제되지 않음 → 힙 증가
```

그리고 **`EmailService`와 같은 확장 문제**가 있습니다:
서버를 2대로 늘리면 세션 컨텍스트가 인스턴스에 갇혀 **면접 중간에 컨텍스트가 사라집니다.**

**해결 방향**

```java
// ① 세션 종료 시 명시적 제거 (최소 조치)
public void clearHistory(String sessionId) { conversationHistory.remove(sessionId); }

// ② TTL이 있는 캐시 (Caffeine 등)
Cache<String, List<Map<String, Object>>> history = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterAccess(Duration.ofHours(2))
        .build();

// ③ Redis에 저장 (권장 — 이미 인프라가 있다)
```

> 이 프로젝트는 면접 세션 데이터를 `InterviewSession`/`InterviewAnswer` **엔티티로 DB에 저장**합니다.
> 그렇다면 어댑터의 메모리 맵은 **중복이거나 불필요**할 가능성이 큽니다.
> → [interview.md](interview.md)

---

# 6. 🟠 로깅 — `System.out.println`과 `printStackTrace`

## 6-1. 현황

```java
@PostConstruct
void log() {                                                       // ⚠️ 메서드 이름이 log
    System.out.println("[AI] Using GeminiAiAdapter with Dynamic Model Selection");
    System.out.println("[AI] API Key status: " + (apiKey != null && !apiKey.isBlank()
            ? "OK (length: " + apiKey.length() + ")" : "MISSING"));
    System.out.println("[AI] Realtime Model: " + realtimeModel);
    ...
}
```

```java
} catch (Exception e) {
    System.err.println("[AI][Gemini] 질문 생성 실패, 기본 질문 사용");
    System.err.println("  - 역할: " + role);
    System.err.println("  - 에러: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    e.printStackTrace();                                            // ⚠️
    ...
}
```

**`@Slf4j`가 클래스에 붙어 있는데 `log`를 쓰지 않습니다.**
프로젝트 전체 `System.out`/`printStackTrace` 27건 중 **17건이 이 두 어댑터**에 있습니다.

## 6-2. 왜 문제인가

| `System.out.println` | `log.info` |
|---|---|
| 레벨 개념 없음 → 끌 수 없음 | `logging.level`로 환경별 제어 |
| 타임스탬프·스레드명·클래스명 없음 | 자동 포함 |
| 구조화 로깅 불가 (MDC, JSON) | 가능 |
| 파일 출력 설정과 무관 | `logging.file`로 관리 |
| 동기 블로킹 (성능) | 비동기 appender 가능 |

`application-prod.yml`에 이런 설정이 있는데:

```yaml
logging:
  file:
    path: ./logs
    name: app.log
  level:
    root: INFO
```

**`System.out`은 이 설정을 무시하고 표준출력으로만 나갑니다.**
Docker 로그 → Loki로는 흘러가지만, **레벨·클래스 필터링이 불가능**합니다.

## 6-3. `e.printStackTrace()`가 특히 나쁜 이유

```java
e.printStackTrace();          // 표준 에러로만 출력. 로그 프레임워크를 우회.
log.error("메시지", e);        // 마지막 인자가 Throwable이면 스택트레이스를 로거가 출력
```

`printStackTrace()`는 **어느 요청에서 났는지, 어느 클래스에서 났는지** 정보가 없어
운영 중 원인 추적이 사실상 불가능합니다.

## 6-4. `@PostConstruct void log()` 메서드명 문제

```java
@Slf4j                      // → private static final Logger log; 필드 생성
public class GeminiAiAdapter {
    @PostConstruct
    void log() { ... }      // → log() 메서드
```

**필드 `log`와 메서드 `log()`가 공존합니다.** 자바는 이름공간이 달라 컴파일되지만,
`log.info(...)`를 쓰려 할 때 혼란을 줍니다. `logConfiguration()` 같은 이름이 맞습니다.

**API 키 길이를 로그에 남기는 것**도 재검토가 필요합니다.
길이만으로 키를 알 수는 없지만, 굳이 남길 정보는 아닙니다.

```java
@PostConstruct
void logConfiguration() {
    log.info("[LLM] Gemini adapter 초기화 - realtime: {}, analysis: {}, keyPresent: {}",
             realtimeModel, analysisModel, apiKey != null && !apiKey.isBlank());
}
```

---

# 7. 🟠 두 어댑터의 중복 — 2,680줄

## 7-1. 무엇이 중복인가

| 중복 항목 | 규모 |
|---|---|
| 프롬프트 문자열 (역할별·질문유형별 가이드) | 각 400줄 이상 |
| JSON 응답 파싱 (`candidates → content → parts → text`) | 메서드마다 반복 |
| 폴백 데이터 (`getFallbackQuestions`, `getFallbackIntentAndGuides`) | 각 100줄 |
| 재시도·오류 처리 | 메서드마다 반복 |

`GeminiAiAdapter`의 메서드 구조를 보면 패턴이 보입니다.

```
generateQuestionsWithGemini()  →  buildSystemPrompt()  →  HTTP  →  parseGeminiResponse()
generateQuestionIntentAndGuides() → getRoleSpecificGuidePrompt() → HTTP → 파싱
generateBatchEvaluation()      →  프롬프트 조립          →  HTTP  →  파싱
moderateContent()              →  프롬프트              →  HTTP  →  파싱
generateQuestionAnswer()       →  프롬프트              →  HTTP  →  파싱
```

**"프롬프트 조립 → HTTP 호출 → JSON 추출"이 11번 반복됩니다.**

## 7-2. 개선 방향 ① 프롬프트를 리소스로 분리

```
resources/prompts/
├── interview-plan.txt
├── interview-next-turn.txt
├── moderation-text.txt
├── moderation-image.txt
└── question-answer.txt
```

```java
@Component
public class PromptTemplateLoader {

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    public String render(String name, Map<String, String> vars) {
        String template = templates.computeIfAbsent(name, this::load);
        String result = template;
        for (var e : vars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return result;
    }

    private String load(String name) {
        try (InputStream is = new ClassPathResource("prompts/" + name + ".txt").getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿 로드 실패: " + name, e);
        }
    }
}
```

**이점**

- **프롬프트를 고칠 때 자바 코드를 건드리지 않습니다** (재컴파일 불필요)
- 두 어댑터가 **같은 프롬프트를 공유**합니다
- 프롬프트 변경 이력이 Git diff로 깔끔하게 보입니다
- 비개발자(기획·운영)도 수정할 수 있습니다

## 7-3. 개선 방향 ② 공통 골격을 추상 클래스로

```java
public abstract class AbstractLlmAdapter implements InterviewerAiGateway {

    protected final RestTemplate restTemplate;
    protected final ObjectMapper om;

    /** 각 제공자가 자기 API 스펙에 맞게 구현 */
    protected abstract String callApi(String model, String prompt, Map<String, Object> options);

    /** 공통: JSON 추출 → 파싱 → 폴백 */
    protected <T> T callAndParse(String model, String prompt, Class<T> type, Supplier<T> fallback) {
        try {
            String raw = callApi(model, prompt, Map.of());
            return om.readValue(extractJson(raw), type);
        } catch (Exception e) {
            log.error("LLM 호출/파싱 실패 - provider: {}, model: {}", getProviderName(), model, e);
            return fallback.get();
        }
    }

    protected String extractJson(String response) { ... }    // 지금 두 곳에 중복된 코드
}
```

**Gemini/OpenAI 어댑터는 `callApi`만 구현하면 됩니다.**
2,680줄이 절반 이하로 줄어듭니다.

> **주의**: 상속은 결합을 만듭니다. 제공자별 차이가 크면
> `LlmHttpClient`를 별도 컴포넌트로 두고 **합성(composition)** 하는 편이 낫습니다.
> "상속보다 합성"이 일반적인 권장입니다.

---

# 8. 세부 코드 읽기

## 8-1. 🟢 텍스트 블록(`"""`)과 `.formatted()`

```java
String prompt = """
        당신은 컨텐츠 검열 전문가입니다. 다음 내용이 부적절한지 판단해주세요.

        검열 기준 및 표준 사유 문구 (부적절한 경우 아래 정확한 문구 중 하나를 사용):
        1. "욕설 및 비속어" - 욕설, 비속어, 모욕적 언어
        ...
        분석 대상 텍스트:
        %s
        """.formatted(content);
```

**텍스트 블록**은 Java 15에서 정식 도입된 여러 줄 문자열입니다.

```java
// 옛 방식 — 읽기 어렵고 오타가 잘 남
String prompt = "당신은 검열 전문가입니다.\n\n" +
                "검열 기준:\n" +
                "1. \"욕설\" - ...\n";     // 큰따옴표를 매번 이스케이프

// 텍스트 블록 — 그대로 쓴다
String prompt = """
        당신은 검열 전문가입니다.

        검열 기준:
        1. "욕설" - ...
        """;
```

**들여쓰기 규칙**: 모든 줄의 공통 최소 들여쓰기(incidental whitespace)가 자동 제거됩니다.
`String.formatted(x)`는 `String.format(this, x)`와 같고, 텍스트 블록과 함께 쓰기 좋습니다.

## 8-2. 🟢 Gemini 구조화 출력(`responseSchema`)

```java
"generationConfig", Map.of(
    "temperature", 0.1,                          // 낮게 → 일관된 판정
    "maxOutputTokens", 1000,
    "responseMimeType", "application/json",      // ★ JSON만 출력하도록 강제
    "responseSchema", Map.of(                     // ★ 스키마까지 지정
        "type", "object",
        "properties", Map.of(
            "inappropriate", Map.of("type", "boolean"),
            "reason", Map.of("type", "string")
        ),
        "required", List.of("inappropriate")
    )
)
```

**Gemini의 구조화 출력 기능을 제대로 쓰고 있습니다.**

- `responseMimeType: application/json` → 마크다운 코드블록 없이 순수 JSON만 반환
- `responseSchema` → 필드 이름·타입까지 모델이 지킵니다
- `temperature: 0.1` → 검열처럼 **일관성이 중요한 작업에 맞는 낮은 값**

**이것이 있으면 `extractJsonFromMarkdown` 같은 후처리가 불필요합니다.**

> **그런데 다른 메서드에는 `responseSchema`가 없습니다.**
> 그래서 `extractJsonFromMarkdown`이 필요해졌고, 파싱 실패 가능성이 남았습니다.
> **모든 JSON 응답 메서드에 스키마를 적용하면 파싱 코드를 지울 수 있습니다.**

## 8-3. `extractJsonFromMarkdown` 읽기

```java
private String extractJsonFromMarkdown(String response) {
    if (response == null || response.trim().isEmpty()) return "{}";

    String cleaned = response.trim();

    // ```json ... ``` 제거
    if (cleaned.startsWith("```json") && cleaned.endsWith("```")) {
        cleaned = cleaned.substring(7, cleaned.length() - 3).trim();     // 7 = "```json".length()
    } else if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
        cleaned = cleaned.substring(3, cleaned.length() - 3).trim();
    }

    // 첫 { 부터 마지막 } 까지만 추출
    int firstBrace = cleaned.indexOf('{');
    int lastBrace = cleaned.lastIndexOf('}');
    if (firstBrace != -1 && lastBrace != -1 && firstBrace <= lastBrace) {
        cleaned = cleaned.substring(firstBrace, lastBrace + 1);
    }
    return cleaned;
}
```

**LLM이 JSON 앞뒤에 설명을 붙이는 습성**에 대응하는 실용적인 코드입니다.

```
LLM 응답: "네, 분석했습니다.\n```json\n{\"a\":1}\n```\n도움이 되셨나요?"
       ↓
첫 { ~ 마지막 }  →  {"a":1}
```

**`{}` 반환도 좋은 판단입니다** — `null`을 반환하면 호출자에서 NPE가 나는데,
빈 JSON은 파싱은 되고 값이 없으니 기본값 처리로 넘어갑니다.

> 다만 `substring(7, ...)`처럼 **매직 넘버**를 쓰고 있습니다.
> ```java
> private static final String JSON_FENCE = "```json";
> cleaned = cleaned.substring(JSON_FENCE.length(), cleaned.length() - 3).trim();
> ```
>
> 그리고 JSON 문자열 값 안에 `}`가 있으면(`{"a": "}"}`) 마지막 `}`를 잘못 잡을 수 있습니다.
> 완벽하게는 `ObjectMapper`로 스트리밍 파싱해야 하지만, **실용적으로는 충분합니다.**

## 8-4. 🟠 `generateQuestionAnswer`의 검증이 프롬프트와 싸운다

```java
String prompt = """
        질문: %s
        내용: %s

        답변 스타일:
        - 핵심만 간결하게 2-3문장으로 설명
        - ...
        - 전체 답변 150자 이내 권장          ← 짧게 쓰라고 지시
        """.formatted(title, content);
```

```java
// 답변 완성도 검증
if (aiResponse.length() < 50) {
    throw new RuntimeException("AI 답변이 너무 짧습니다: " + aiResponse.length() + "자");   // ⚠️
}
```

**"150자 이내로 짧게 써라"라고 지시하면서 "50자 미만이면 실패"로 처리합니다.**
허용 구간이 50~150자로 좁고, 모델이 40자로 정확히 답하면 **실패로 간주**됩니다.

그러면 `AiBotService`가 잡아서 **폴백 사과문을 댓글로 게시**합니다.
→ [aibot.md 2-3](aibot.md#2-3--폴백-답변이-댓글로-게시된다)

**"짧고 좋은 답변"이 "AI 시스템에 문제가 발생했습니다"로 바뀝니다.**

```java
// 프롬프트와 검증을 일치시키기
if (aiResponse.isBlank()) {
    throw new IllegalStateException("AI 답변이 비어 있습니다");
}
// 길이 검증은 제거하거나 최소값을 크게 낮춘다 (예: 10자)
```

## 8-5. 🟡 마크다운 헤더 후처리

```java
if (aiResponse.contains("##")) {
    System.err.println("[AI] 마크다운 헤더 감지됨 - ...");
    aiResponse = aiResponse.replaceAll("##\\s*[^\\n]+\\n?", "").trim();     // 헤더 줄 전체를 삭제
}
```

프롬프트에 "마크다운 헤더(##) 사용 금지"라고 지시했는데도 나올 경우를 대비한 방어입니다.
**LLM은 지시를 완벽히 지키지 않으므로 이런 후처리는 실무적으로 필요합니다.**

다만 `##`를 **헤더가 아닌 문맥에서도 지웁니다.**

```
"C에서 ## 연산자는 토큰을 결합합니다"
   → replaceAll 후: "C에서" (뒷부분이 사라짐!)
```

`##`는 C 전처리기의 토큰 결합 연산자이고, 개발자 커뮤니티에서 충분히 등장할 수 있습니다.

```java
// 줄 시작의 ## 만 헤더로 취급
aiResponse = aiResponse.replaceAll("(?m)^#{1,6}\\s*", "");     // (?m) = MULTILINE
```

`(?m)`은 멀티라인 모드로, `^`가 각 줄의 시작에 매칭됩니다.

## 8-6. 🟡 프롬프트 다양화를 위한 시드

```java
long currentTime = System.currentTimeMillis();
int randomSeed = new java.util.Random().nextInt(10000);
int roleHash = role.hashCode();
int seed = Math.abs((int) (currentTime + randomSeed + roleHash)) % 100000;
```

**같은 역할로 면접을 반복할 때 같은 질문이 나오지 않게** 하려는 장치입니다.
프롬프트에 무작위 시드를 넣어 모델 출력을 흔드는 실용적인 기법입니다.

**두 가지 문제**

1. **`new java.util.Random()`을 매번 생성합니다.**
   `ThreadLocalRandom.current()`가 더 빠르고 스레드 안전합니다.
   → [global 4-3](../global.md#4-3-nicknamegenerator--랜덤-한글-닉네임)
2. **`Math.abs(Integer.MIN_VALUE)`는 여전히 음수입니다.**
   `Math.abs()`가 `Integer.MIN_VALUE`에 대해 자기 자신을 반환하는 것은 자바의 유명한 함정입니다.
   `% 100000`이 뒤에 있어 결과가 음수가 될 수 있습니다(치명적이지는 않지만 의도와 다름).

```java
// 더 안전하게
int seed = ThreadLocalRandom.current().nextInt(100_000);
```

## 8-7. 🟡 모델 선택 로직이 반만 쓰인다

```java
private String getOptimalModel(String methodName) {
    return switch (methodName) {
        case "nextTurn" -> realtimeModel;        // gemini-1.5-flash (빠름)
        case "generatePlan" -> analysisModel;    // gemini-2.5-flash (정확)
        default -> analysisModel;
    };
}
```

**아이디어가 좋습니다** — 실시간 응답이 중요한 작업은 빠른 모델, 분석은 정확한 모델.

**하지만 다른 메서드들은 모델을 직접 지정합니다.**

```java
// moderateContent
String url = baseUrl + "/models/" + realtimeModel + ":generateContent?key=" + apiKey;

// generateQuestionAnswer
String url = baseUrl + "/models/" + analysisModel + ":generateContent?key=" + apiKey;
```

**`getOptimalModel`을 우회합니다.** 그리고 `switch`가 **메서드 이름 문자열**을 받으므로
메서드명을 바꾸면 조용히 `default`로 빠집니다(컴파일러가 못 잡음).

```java
// enum으로 만들면 컴파일 타임에 안전해진다
public enum LlmTask {
    NEXT_TURN(ModelTier.REALTIME),
    GENERATE_PLAN(ModelTier.ANALYSIS),
    MODERATION(ModelTier.REALTIME),
    QUESTION_ANSWER(ModelTier.ANALYSIS);

    private final ModelTier tier;
    ...
}
```

## 8-8. 🟡 URL에 API 키가 들어간다

```java
String url = baseUrl + "/models/" + realtimeModel + ":generateContent?key=" + apiKey;
```

**Gemini API 규격이 그래서 어쩔 수 없습니다.** 하지만 위험을 알아야 합니다.

`RestTemplate`이 던지는 `HttpClientErrorException`의 메시지에 **URL이 포함**되고,
그것이 `printStackTrace()`로 표준에러에 찍혀 Docker 로그 → Loki로 갑니다.

**즉 API 키가 로그 저장소에 남을 수 있습니다.**

```java
// Gemini는 헤더 방식도 지원합니다
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("x-goog-api-key", apiKey);          // URL에서 키를 제거
String url = baseUrl + "/models/" + model + ":generateContent";
```

**OpenAI는 이미 헤더 방식(`Authorization: Bearer`)이므로 이 문제가 없습니다.**

## 8-9. 🟡 `om.readValue(responseText, Map.class)` — 원시 타입

```java
Map<String, Object> parsedResponse = om.readValue(responseText, Map.class);
boolean inappropriate = (Boolean) parsedResponse.getOrDefault("inappropriate", false);
String reason = (String) parsedResponse.get("reason");
```

`Map.class`는 **원시 타입(raw type)** 이라 `Map<String, Object>`로의 대입에서
컴파일러 경고가 발생합니다. 그리고 `(Boolean)`, `(String)` 캐스팅이 필요합니다.

**DTO를 정의하는 것이 훨씬 안전합니다.**

```java
// llm/port/dto/ModerationApiResponse.java
public record ModerationApiResponse(boolean inappropriate, String reason) { }

// 사용
ModerationApiResponse parsed = om.readValue(responseText, ModerationApiResponse.class);
return new ModerationResult(parsed.inappropriate(), parsed.reason());
```

캐스팅도 `getOrDefault`도 필요 없고, **필드 이름 오타를 컴파일 타임에 잡을 수 있습니다.**

> 이 프로젝트가 `Map<String, Object>`를 포트 반환 타입으로 쓰는 것 자체가
> 같은 문제의 확대판입니다. `generatePlan`, `finalizeReport`, `extractDocumentInfo`가
> 전부 `Map<String, Object>`를 반환하므로 **호출자가 키 이름을 문자열로 추측**해야 합니다.
> → [interview.md](interview.md)에서 그 결과를 다룹니다.

---

# 9. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | `new RestTemplate()` — 타임아웃 무한 → 스레드 영구 점유 | [3장](#3--타임아웃-없는-resttemplate) |
| 🔴 2 | 검열 파싱 실패 시 fail-open (통과시킴) — 상위 정책과 모순 | [4장](#4--검열-폴백이-fail-open이다) |
| 🔴 3 | 포트가 `community`·`interview` 타입을 import → 순환 의존 | [2-3](#2-3-더-심각한-문제--포트가-다른-도메인-타입에-의존한다) |
| 🟠 4 | 인터페이스에 3개 도메인 관심사 혼재 (ISP 위반) | [2-1](#2-1-이름과-내용이-다르다), [2-4](#2-4-권장--포트를-3개로-쪼갠다) |
| 🟠 5 | `conversationHistory` 메모리 누수 + 다중 인스턴스 불가 | [5장](#5--conversationhistory--메모리-누수) |
| 🟠 6 | `System.out`/`printStackTrace` 17건 → 로그 프레임워크 우회 | [6장](#6--로깅--systemoutprintln과-printstacktrace) |
| 🟠 7 | `generateQuestionAnswer`의 50자 검증이 프롬프트와 모순 | [8-4](#8-4--generatequestionanswer의-검증이-프롬프트와-싸운다) |
| 🟠 8 | 프롬프트 + 파싱 로직 2,680줄 중복 | [7장](#7--두-어댑터의-중복--2680줄) |
| 🟡 9 | API 키가 URL에 포함 → 예외 메시지·로그 노출 | [8-8](#8-8--url에-api-키가-들어간다) |
| 🟡 10 | `responseSchema`가 검열 메서드에만 적용 | [8-2](#8-2--gemini-구조화-출력responseschema) |
| 🟡 11 | 마크다운 후처리가 본문의 `##`도 삭제 | [8-5](#8-5--마크다운-헤더-후처리) |
| 🟡 12 | `getOptimalModel`이 문자열 기반 + 반만 사용 | [8-7](#8-7--모델-선택-로직이-반만-쓰인다) |
| 🟡 13 | `Map.class` 원시 타입 파싱 + 캐스팅 | [8-9](#8-9--omreadvalueresponsetext-mapclass--원시-타입) |
| 🟢 14 | `@PostConstruct void log()` — `@Slf4j` 필드와 이름 충돌 + 키 길이 로깅 | [6-4](#6-4-postconstruct-void-log-메서드명-문제) |
| 🟢 15 | `new Random()` 매번 생성 + `Math.abs(MIN_VALUE)` 함정 | [8-6](#8-6--프롬프트-다양화를-위한-시드) |
| 🟢 16 | `HttpClientConfig`의 `restTemplate` 빈을 쓰지 않고 직접 생성 | [3-2](#3-2-이-프로젝트에는-이미-설정된-빈이-있다) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **포트-어댑터 패턴 도입** | 학생 프로젝트에서 DIP를 시도한 것 자체가 드묾. 폴백 이중화가 여기서 나옴 |
| `@Component("이름")` + `@Qualifier` | 제공자 교체·폴백을 설정 수준에서 처리 |
| Gemini `responseSchema` 구조화 출력 | JSON 형식·필드·타입을 모델이 지키게 강제 |
| `temperature: 0.1` (검열) / `0.5` (답변) | 작업 성격에 맞게 창의성 조절 |
| 모델 티어 분리 아이디어 (realtime/analysis) | 응답 속도와 정확도의 트레이드오프를 인식 |
| `extractJsonFromMarkdown` | LLM이 JSON 앞뒤에 설명을 붙이는 실제 습성에 대응 |
| `{}` 반환 (빈 JSON) | `null`을 반환해 NPE를 유발하지 않음 |
| 텍스트 블록 + `.formatted()` | 프롬프트 가독성 확보. Java 15+ 기능 활용 |
| 마크다운 후처리 방어 | "LLM은 지시를 완벽히 지키지 않는다"를 인식 |
| `ConcurrentHashMap` 사용 | 싱글턴 빈의 가변 상태에 올바른 자료구조 선택 |
| 프롬프트 시드 다양화 | 반복 면접에서 같은 질문이 나오지 않게 |
| 검열 사유를 7개 표준 문구로 제한 | 자유 텍스트가 아니라 분류값을 받아 후처리 가능 |
| 폴백 질문 세트 준비 | LLM 실패 시에도 면접이 진행됨 |

**핵심 평가**: **아키텍처 감각과 LLM 실무 감각이 모두 보입니다.**
포트-어댑터, 구조화 출력, 모델 티어링, 프롬프트 시드 — 실무에서 쓰는 기법들입니다.

문제는 **두 가지 계층에 있습니다.**

1. **경계 관리** — 포트가 도메인 타입을 끌어와 순환 의존이 생기고, 인터페이스가 비대해졌습니다.
2. **인프라 기본기** — 타임아웃, 로깅, 자원 관리처럼 "동작에는 문제없지만 운영에서 터지는" 부분.

가장 먼저 할 일은 **`RestTemplate`에 타임아웃을 주는 것**입니다.
한 줄짜리 변경인데, 지금은 LLM 하나가 응답하지 않으면 검열 스레드풀 전체가 멈출 수 있습니다.

---

## 다음 문서

- [interview.md](interview.md) — 이 포트의 최대 소비자. `Map<String, Object>` 반환의 결과
- [community.md](community.md) — `moderateContent`를 쓰는 검열 서비스와 서킷브레이커
- [aibot.md](aibot.md) — `generateQuestionAnswer`를 쓰는 AI 봇
- [question.md](question.md) — 포트를 쓰지 않고 직접 LLM을 호출하는 다른 경로
