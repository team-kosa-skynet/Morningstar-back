# ai — AI 뉴스 생성 · AI 리더보드 · 모델 추천

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [global.md](../global.md) · [llm.md](llm.md)
>
> 파일 25개. 성격이 다른 **세 기능이 한 패키지에 섞여 있습니다.**
>
> 1. **AI 업데이트 소식** — RSS 크롤링 → LLM 기사 생성 → DALL·E 이미지 → S3 (`AiUpdatesService`)
> 2. **AI 리더보드** — 벤치마크 데이터로 모델 순위 (`AIModelIntegrated`, `AIAnalysisService`)
> 3. **모델 추천** — 사용자 답변에 가중치를 적용해 모델 추천 (`recommendation/`)
>
> `recommendation/`의 정규화 알고리즘은 **이 프로젝트에서 가장 잘 쓰인 알고리즘 코드**이고,
> `AiUpdatesService`는 **가장 손볼 곳이 많은 파일**입니다.

---

## 파일 지도

```
domain/ai/
├── controller/
│   ├── AiUpdatesController.java       GET /api/ai-updates  (2개)
│   ├── AiNewsController.java          GET /api/ai-news     (3개)
│   ├── AIAnalysisController.java      GET api/analysis     (2개)
│   └── RecommendationController.java  POST api/ai-recommend (1개)
├── dto/
│   ├── AIUpdateNewsResponseDto, AiNewsResponseDto
│   ├── AIModelDataDto, AIModelListResponseDto, AIAnalysisDto
│   └── OpenAiImageRequest
├── entity/
│   ├── AiUpdate.java             LLM이 생성한 AI 소식 기사
│   ├── AiNews.java               네이버 뉴스 기반 AI 뉴스
│   └── AIModelIntegrated.java    ★ 벤치마크 20여 지표를 담은 모델 카탈로그
├── recommendation/               ★ 순수 계산 로직 (스프링 의존 거의 없음)
│   ├── ModelScoreCalculator.java     정규화 + 합성 점수
│   ├── Weights.java                  가중치
│   ├── Answers.java                  사용자 설문 응답
│   └── Model.java                    정규화된 모델 DTO
├── repository/  AiUpdateRepository, AiNewsRepository, AIAnalysisRepository
└── service/
    ├── AiUpdatesService.java     276줄 ★ 문제가 가장 많은 파일
    ├── AiNewsService.java
    ├── AIAnalysisService.java
    └── RecommendationService.java
```

---

# 1. `recommendation` — 잘 쓰인 알고리즘 코드

## 1-1. 무엇을 하나

`AIModelIntegrated` 테이블에는 모델별 벤치마크 점수가 **서로 다른 스케일**로 들어 있습니다.

```java
private Double price1mBlended;                  // 0.15 ~ 75 (달러)
private Double mmluPro;                          // 0 ~ 100 (%)
private Double medianOutputTokensPerSecond;      // 10 ~ 300 (토큰/초)
private Double medianTimeToFirstTokenSeconds;    // 0.2 ~ 5 (초)
private LocalDate releaseDate;                   // 날짜
```

**이 값들을 그대로 더하면 안 됩니다.** 단위와 범위가 다르고,
어떤 것은 **클수록 좋고**(정확도) 어떤 것은 **작을수록 좋습니다**(가격, 응답 지연).

`ModelScoreCalculator`가 이 문제를 정확히 해결합니다.

## 1-2. Min-Max 정규화

```java
ScoreRange costRange = calculateRange(allRawModels, AIModelIntegrated::getPrice1mBlended);
...
double costScore = normalizeInverted(rawModel.getPrice1mBlended(), costRange);
double knowledgeScore = normalize(calculateRawKnowledgeScore(rawModel), knowledgeRange);
```

**Min-Max 정규화**는 모든 값을 `0.0 ~ 1.0` 구간으로 옮깁니다.

```
normalize(x)         = (x - min) / (max - min)        큰 값이 좋을 때
normalizeInverted(x) = (max - x) / (max - min)        작은 값이 좋을 때
```

| 지표 | 방향 | 사용 함수 |
|---|---|---|
| 지식·코드·수학·추론 점수 | 클수록 좋음 | `normalize` |
| 가격 (`price1mBlended`) | **작을수록 좋음** | `normalizeInverted` |
| 첫 토큰 지연 | **작을수록 좋음** | `normalizeInverted` |
| 출시 후 경과일 | **작을수록 좋음**(최신) | `normalizeInverted` |
| 초당 토큰 수 | 클수록 좋음 | `normalize` |

**`normalizeInverted`를 만들어 방향을 뒤집은 것이 핵심입니다.**
이것이 없으면 "비싼 모델이 좋은 모델"이 되어버립니다.

**`ScoreRange`를 미리 계산하는 것도 중요합니다.**

```java
// 전체 모델을 한 번 훑어 min/max를 구한 뒤
ScoreRange costRange = calculateRange(allRawModels, AIModelIntegrated::getPrice1mBlended);

// 각 모델을 정규화할 때 재사용
double costScore = normalizeInverted(rawModel.getPrice1mBlended(), costRange);
```

모델마다 min/max를 다시 구하면 **O(n²)** 이 됩니다. 미리 구하면 **O(n)** 입니다.

## 1-3. 결측치(`null`) 처리

```java
private double calculateRawKnowledgeScore(AIModelIntegrated m) {
    return Stream.of(m.getMmluPro(), m.getGpqa(), m.getHle())
            .filter(Objects::nonNull)          // ★ null 벤치마크 제외
            .mapToDouble(d -> d)
            .average()
            .orElse(0.0);                       // 전부 null이면 0
}
```

**벤치마크 점수는 모델마다 있고 없습니다.** 새 모델은 일부 벤치마크만 측정되어 있습니다.

`filter(Objects::nonNull)` → `average()` 조합은
**"있는 값만 평균"** 을 구하는 정확한 처리입니다.

`Stream.of(...)` → `filter` → `mapToDouble` → `average` → `orElse`는
`OptionalDouble`을 다루는 표준 관용구입니다.

```java
// average()는 OptionalDouble을 반환 — 빈 스트림이면 값이 없다
OptionalDouble avg = DoubleStream.of().average();   // → OptionalDouble.empty
avg.orElse(0.0);                                     // → 0.0
```

> **아쉬운 점**: 전부 `null`이면 `0.0`이 되어 **"측정 안 됨"과 "최하점"이 구분되지 않습니다.**
> 새 모델이 벤치마크 미측정이라는 이유로 최하위가 됩니다.
> `null`을 반환해 정규화 단계에서 **평균값으로 대체(imputation)** 하거나
> 순위에서 제외하는 편이 공정합니다.

## 1-4. 복합 지표 — 속도

```java
private double calculateCombinedSpeedScore(AIModelIntegrated rawModel,
                                           ScoreRange tpsRange, ScoreRange tttfRange) {
    double tpsScore  = normalize(rawModel.getMedianOutputTokensPerSecond(), tpsRange);
    double tttfScore = normalizeInverted(rawModel.getMedianTimeToFirstTokenSeconds(), tttfRange);
    return (tpsScore * 0.5) + (tttfScore * 0.5);
}
```

**"속도"를 두 축으로 나눈 것이 정확합니다.**

| 지표 | 의미 | 사용자 체감 |
|---|---|---|
| 초당 토큰 수(TPS) | **처리량** | 긴 답변이 빨리 완성되는가 |
| 첫 토큰 지연(TTFT) | **응답성** | 요청 후 반응이 빨리 시작되는가 |

**둘은 서로 다른 특성입니다.** TTFT가 짧고 TPS가 낮은 모델(반응은 빠르지만 느리게 씀)과
그 반대가 있습니다. 두 값을 0.5씩 합성해 하나의 "속도" 점수를 만들었습니다.

> 챗봇에는 **TTFT가 더 중요**하고(사용자가 기다림을 체감), 배치 처리에는 **TPS가 중요**합니다.
> 0.5:0.5보다 **용도별 가중치**를 두는 것이 더 정확할 수 있습니다.

## 1-5. 🟠 `determineOpenSource`의 하드코딩

```java
private boolean determineOpenSource(String creatorName) {
    if (creatorName == null) return false;
    return List.of("Meta", "Mistral AI", "EleutherAI", "Technology Innovation Institute")
            .contains(creatorName);
}
```

**문제 3가지**

1. **제작사 이름 문자열 완전 일치**에 의존합니다.
   `"Meta AI"`, `"meta"`, `"Meta Platforms"`가 오면 `false`입니다.
2. **제작사 단위 판단이 부정확합니다.** Google은 오픈소스 Gemma와 비공개 Gemini를 모두 만듭니다.
   Mistral도 일부 모델만 공개합니다. **모델 단위 속성이어야 합니다.**
3. 새 오픈소스 제작사가 나오면 **코드를 고쳐 재배포**해야 합니다.

```java
// AIModelIntegrated에 컬럼을 추가하는 것이 정답
@Column(name = "is_open_source")
private Boolean isOpenSource;
```

`List.of(...)`를 메서드 안에서 매번 만드는 것도 낭비입니다.
`static final Set<String>`으로 빼면 됩니다(`Set.contains`가 `List.contains`보다 빠름).

## 1-6. 🟡 `Model`의 setter 조립

```java
Model model = new Model();
model.setName(rawModel.getModelName());
model.setCreator(rawModel.getCreatorName());
model.setReleaseDate(rawModel.getReleaseDate() != null ? rawModel.getReleaseDate().toString() : "N/A");

Model.Scores scores = model.getScores();
scores.setCost(costScore);
scores.setSpeed(speedScore);
...
```

**빈 객체를 만든 뒤 setter로 하나씩 채웁니다.**
중간에 하나를 빠뜨리면 `0.0`이 남고, **컴파일러가 알려주지 않습니다.**

이 프로젝트는 다른 곳에서 빌더와 `record`를 잘 쓰고 있으므로 **여기도 통일하면 좋습니다.**

```java
public record Model(String name, String creator, String releaseDate, Scores scores) {
    public record Scores(double cost, double speed, double recency,
                         double knowledge, double code, double math, double reasoning) {}
}

// 사용 — 하나라도 빠뜨리면 컴파일 에러
return new Model(rawModel.getModelName(), rawModel.getCreatorName(), releaseDateStr,
        new Scores(costScore, speedScore, recencyScore,
                   knowledgeScore, codeScore, mathScore, reasoningScore));
```

`"N/A"`를 문자열로 넣는 것도 아쉽습니다. **`null`을 주고 프론트가 표시를 결정**하는 편이 낫습니다.
(`ObjectMapper`가 `NON_NULL`이라 키가 아예 빠집니다.)

## 1-7. 🟢 스프링 의존이 거의 없다

```java
@Component                             // 이 어노테이션 하나뿐
public class ModelScoreCalculator {
    public List<Model> normalizeAndCreateModelList(List<AIModelIntegrated> allRawModels) { ... }
}
```

**리포지토리도, 외부 API도, 트랜잭션도 없는 순수 계산 클래스입니다.**

이런 코드의 이점:

- **테스트가 쉽습니다.** `new ModelScoreCalculator()`로 만들어 리스트를 넣으면 끝입니다.
  DB도 스프링 컨텍스트도 필요 없습니다.
- **동작을 예측할 수 있습니다.** 같은 입력 → 같은 출력(순수 함수).
- **재사용 가능합니다.**

> **이 프로젝트에서 단위 테스트를 처음 쓴다면 여기서 시작하는 것이 가장 쉽습니다.**
>
> ```java
> class ModelScoreCalculatorTest {
>
>     private final ModelScoreCalculator calculator = new ModelScoreCalculator();
>
>     @Test
>     void 가격이_낮은_모델이_비용_점수가_높다() {
>         AIModelIntegrated cheap = model("cheap", 0.15);
>         AIModelIntegrated expensive = model("expensive", 75.0);
>
>         List<Model> result = calculator.normalizeAndCreateModelList(List.of(cheap, expensive));
>
>         assertThat(result.get(0).getScores().getCost()).isEqualTo(1.0);   // 최저가 → 1.0
>         assertThat(result.get(1).getScores().getCost()).isEqualTo(0.0);   // 최고가 → 0.0
>     }
> }
> ```
>
> ⚠️ 단 `ChronoUnit.DAYS.between(releaseDate, LocalDate.now())`가 있어
> **`recency` 점수는 테스트 시각에 따라 달라집니다.**
> `Clock`을 주입받으면 테스트에서 시각을 고정할 수 있습니다.
> ```java
> private final Clock clock;      // 운영: Clock.systemDefaultZone(), 테스트: Clock.fixed(...)
> LocalDate.now(clock)
> ```

---

# 2. `AIModelIntegrated` — 벤치마크 카탈로그

```java
@Entity
@Getter
@Setter                                          // ⚠️
@NoArgsConstructor                                // ⚠️ public
public class AIModelIntegrated {                  // ⚠️ BaseTimeEntity 상속 안 함

    @Id
    @Column(name = "model_id")
    private String modelId;                        // ★ String 기본키

    @Column(name = "model_name", nullable = false) private String modelName;
    @Column(name = "model_slug")   private String modelSlug;
    @Column(name = "release_date") private LocalDate releaseDate;

    @Column(name = "creator_id")   private String creatorId;
    @Column(name = "creator_name") private String creatorName;
    @Column(name = "creator_slug") private String creatorSlug;

    // 가격 (100만 토큰 기준)
    private Double price1mBlended;  // ... 외 가격 지표들

    // 성능 지표 — 각 컬럼에 무엇을 재는지 주석이 달려 있다
    @Column(name = "mmlu_pro")      private Double mmluPro;        // 전문 지식
    @Column(name = "gpqa")          private Double gpqa;            // 대학원 수준 추론
    @Column(name = "hle")           private Double hle;
    @Column(name = "livecodebench") private Double livecodebench;   // 실시간 코딩
    @Column(name = "scicode")       private Double scicode;         // 과학 코드
    @Column(name = "math_500")      private Double math500;
    @Column(name = "aime")          private Double aime;            // 수학 경시대회
    @Column(name = "aime_25")       private Double aime25;
    @Column(name = "ifbench")       private Double ifbench;         // 지시 수행
    @Column(name = "lcr")           private Double lcr;             // 장문 추론

    @Column(name = "median_output_tokens_per_second")   private Double medianOutputTokensPerSecond;
    @Column(name = "median_time_to_first_token_seconds") private Double medianTimeToFirstTokenSeconds;
}
```

## 2-1. 🟢 `String` 기본키를 쓴 이유

```java
@Id
@Column(name = "model_id")
private String modelId;        // 예: "openai/gpt-4o-mini"
```

**외부 데이터 소스(Artificial Analysis 등)의 모델 식별자를 그대로 PK로 씁니다.**
이것을 **자연키(natural key)** 라고 합니다.

| | 자연키 (`String modelId`) | 대리키 (`Long id` AUTO_INCREMENT) |
|---|---|---|
| 중복 삽입 방지 | **자동** (PK 제약이 곧 유니크) | 별도 유니크 제약 필요 |
| 외부 데이터 동기화 | **간단** (같은 ID면 UPDATE) | 매핑 테이블/조회 필요 |
| 인덱스 크기 | 큼 (문자열) | 작음 |
| ID가 바뀌면 | **참조가 다 깨짐** | 영향 없음 |

**주기적으로 외부 데이터를 갱신하는 테이블에는 자연키가 유리합니다.**
`saveAll()`이 같은 `modelId`면 UPDATE, 새 ID면 INSERT를 자동으로 합니다
([기초개념 6-4](../00-기초개념.md#save의-실제-구현)의 `isNew()` 판정).

## 2-2. 🟢 컬럼마다 무엇을 재는지 주석이 있다

```java
@Column(name = "gpqa")
private Double gpqa;  // 대학원 수준의 전문 지식과 추론 능력을 평가하는 벤치마크 점수

@Column(name = "median_time_to_first_token_seconds")
private Double medianTimeToFirstTokenSeconds;  // 요청 후 첫 번째 토큰이 생성되기까지 걸리는 시간의 중앙값 (응답성)
```

**벤치마크 이름은 그 자체로 의미를 알 수 없습니다.**
`gpqa`, `lcr`, `ifbench`가 무엇인지 모르면 `ModelScoreCalculator`의 그룹화
(지식/코드/수학/추론)가 왜 그렇게 나뉘었는지 이해할 수 없습니다.

**"코드만 봐서는 알 수 없는 정보"를 적은 좋은 주석입니다.**
→ [conversation.md 4-3](conversation.md#4-3--주석-품질)의 기준과 같습니다.

## 2-3. 🟠 `@Setter`가 붙어 있다

```java
@Getter
@Setter          // ⚠️ 모든 필드에 setter 생성
public class AIModelIntegrated { ... }
```

**이 프로젝트 엔티티 대부분은 `@Setter`가 없습니다.**
→ [기초개념 3-3](../00-기초개념.md#3-3-getter--그리고-setter가-없는-이유)

`AiNews`에도 `@Setter`가 있습니다. **두 엔티티만 예외입니다.**

외부 데이터를 채우기 위해 setter가 필요했을 것으로 보이지만,
**빌더나 정적 팩토리로 대체할 수 있습니다.**

```java
// 외부 데이터 → 엔티티 변환을 정적 팩토리로
public static AIModelIntegrated from(ExternalModelDto dto) {
    return AIModelIntegrated.builder()
            .modelId(dto.id())
            .modelName(dto.name())
            ...
            .build();
}
```

## 2-4. 🟡 `BaseTimeEntity`를 상속하지 않는다

```java
public class AIModelIntegrated {          // extends BaseTimeEntity 없음
```

**프로젝트에서 유일하게 `createdAt`/`updatedAt`이 없는 엔티티입니다.**

벤치마크 데이터는 주기적으로 갱신되므로 **"언제 갱신되었나"가 중요한 정보**입니다.
리더보드에 "2026-08-11 기준" 같은 표시를 하려면 이 값이 필요합니다.

```java
public class AIModelIntegrated extends BaseTimeEntity { ... }
```

한 줄 추가로 `updatedAt`이 자동 관리됩니다. → [global 2장](../global.md#2-entity--basetimeentity)

## 2-5. 🟠 이 테이블도 채우는 코드가 없다

`point_tier`, `ai_models`와 같은 문제입니다.
→ [pointTier.md 2-4](pointTier.md#2-4-이-테이블은-누가-채우나--아무도-채우지-않는다),
[question.md 5-3](question.md#5-3--ai_models-테이블을-채우는-코드가-없다)

**벤치마크 데이터를 넣는 서비스·스케줄러·초기화 컴포넌트가 없습니다.**
수동 SQL로 관리하고 있고, 커밋 이력이 그것을 보여줍니다.

```
chore(job-signal): 시각모델 단가표에 모델 4종 추가 (다른 세션 작업분)
```

**리더보드가 "최신"이려면 갱신 파이프라인이 필요합니다.**
`recruitmentNotice`의 사람인 수집처럼 스케줄러를 만들면 됩니다.

> **정리하면 이 프로젝트에는 "데이터가 있어야 동작하는데 넣는 코드가 없는" 테이블이 3개입니다.**
>
> | 테이블 | 없으면 | 넣는 코드 |
> |---|---|---|
> | `point_tier` | **회원가입 실패** | ❌ |
> | `ai_models` | 모델 선택 목록 빈칸 | ❌ |
> | `ai_model_integrated` | 리더보드·추천 빈칸 | ❌ |
>
> `AiMemberInitializer`(AI 봇 계정)처럼 `@EventListener(ApplicationReadyEvent.class)` 초기화
> 컴포넌트를 만들면 로컬 환경 세팅과 신규 배포가 훨씬 쉬워집니다.

---

# 3. `AiUpdatesService` — 손볼 곳이 가장 많은 파일

## 3-1. 무엇을 하나 — 파이프라인 자체는 흥미롭다

```java
public void getLatestAiUpdates() throws Exception {
    List<NewsItem> allNews = new ArrayList<>();

    fetchAndAddNews(allNews, "https://openai.com/blog/rss.xml");    // ① RSS 수집
    fetchAndAddNews(allNews, "https://blog.google/rss/");

    if (allNews.isEmpty()) throw new AINewsIsNotGeneratedException();

    for (NewsItem item : allNews) {
        item.description = fetchFullContent(item.link);               // ② 본문 크롤링
    }

    String article     = generateNewsArticle(allNews);                // ③ LLM 기사 작성
    String title       = generateNewsTitle(article);                  // ④ LLM 제목 생성
    String imagePrompt = generateImagePromptFromArticle(article);      // ⑤ LLM 이미지 프롬프트
    String imageUrl    = generateImageWithDalle3(imagePrompt);         // ⑥ DALL·E 3 → S3

    repository.save(AiUpdate.builder().title(title).content(article).imageUrl(imageUrl).build());
}
```

**"공식 블로그 RSS → 본문 크롤링 → 한국어 기사 생성 → 삽화 생성"** 파이프라인입니다.
LLM을 4번(기사·제목·이미지 프롬프트 + DALL·E) 연쇄로 쓰는 흥미로운 구성입니다.

**특히 ⑤가 영리합니다** — 기사 본문을 LLM에게 주고
**"이 기사를 대표할 영어 이미지 프롬프트"** 를 만들게 한 뒤 DALL·E에 넘깁니다.
한국어 기사를 이미지 모델에 직접 주면 결과가 나쁜데, 그 문제를 우회했습니다.

## 3-2. 🔴 JSON을 문자열로 직접 만든다 — 개행에서 깨진다

```java
private String callGemini(String prompt) throws Exception {
    String requestBody = """
    {
      "contents": [{"parts": [{"text": "%s"}]}]
    }
    """.formatted(prompt.replace("\"", "\\\""));      // ★ 큰따옴표만 이스케이프
```

**JSON 문자열 안에서 이스케이프해야 하는 문자는 큰따옴표만이 아닙니다.**

| 문자 | JSON 표현 | 이 코드 처리 |
|---|---|---|
| `"` | `\"` | ✅ |
| `\` | `\\` | ❌ |
| 개행 (`\n`) | `\n` (2글자) | ❌ **원본 그대로 삽입** |
| 캐리지리턴 (`\r`) | `\r` | ❌ |
| 탭 (`\t`) | `\t` | ❌ |
| 제어문자 | `\uXXXX` | ❌ |

**그리고 이 프롬프트에는 개행이 반드시 들어갑니다.**

```java
StringBuilder sb = new StringBuilder("다음은 최근 AI 기술 업데이트 소식입니다:\n");
for (NewsItem item : items) {
    sb.append("- 제목: ").append(item.title).append("\n");        // ★ 개행
    sb.append("  날짜: ").append(item.pubDate).append("\n");
    sb.append("  내용: ").append(item.description).append("\n\n");
}
String prompt = "다음 AI 관련 최신 업데이트 내용을 ... :\n\n" + sb;
```

**JSON 스펙(RFC 8259)에서 문자열 리터럴 안에 raw 개행은 허용되지 않습니다.**
크롤링한 본문에 `\`나 제어문자가 있으면 더 확실히 깨집니다.

**해결 — `ObjectMapper`가 이스케이프를 정확히 처리합니다**

```java
private String callGemini(String prompt) throws Exception {
    Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
    );
    String requestBody = objectMapper.writeValueAsString(body);     // ★ 안전한 직렬화
    ...
}
```

**`GeminiAiAdapter`는 이미 이 방식을 씁니다.**

```java
// llm/infrastructure/GeminiAiAdapter — 올바른 방식
Map<String, Object> requestBody = Map.of(
    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
    "generationConfig", Map.of(...)
);
HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
```

**같은 프로젝트에 올바른 예가 있는데 여기서는 문자열로 조립했습니다.**

> **원칙: JSON·SQL·HTML·URL을 문자열 연결로 만들지 마세요.**
> 전부 이스케이프 규칙이 있고, 손으로 처리하면 반드시 빠뜨립니다.
> (SQL이면 SQL 인젝션, HTML이면 XSS가 됩니다.)

## 3-3. 🔴 `HttpClient`를 호출마다 새로 만든다

```java
HttpClient client = HttpClient.newHttpClient();
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

**이 두 줄이 5개 메서드에 반복됩니다** (`callGemini`, `generateImageWithStability`,
`generateImageWithDeepAI`, `generateImageWithDalle3`).

**문제 2가지**

### ① `HttpClient` 생성 비용이 큽니다

`java.net.http.HttpClient`는 내부에 **셀렉터 스레드와 스레드풀**을 만듭니다.
매번 새로 만들면 그만큼 스레드가 생기고, `HttpClient`는 **명시적 close가 없어**
GC가 수거할 때까지 리소스를 잡고 있습니다.

한 번의 `getLatestAiUpdates()` 실행에서 `callGemini`가 3회 + DALL·E 1회 = **4개**가 생성됩니다.

### ② 타임아웃이 없습니다

```java
HttpClient.newHttpClient()      // connectTimeout 미설정 → 무한 대기
HttpRequest.newBuilder()...     // .timeout() 미설정 → 무한 대기
```

**응답이 오지 않으면 스케줄러 스레드가 영구히 묶입니다.**
스프링 기본 스케줄러는 단일 스레드이므로 **다른 모든 `@Scheduled` 작업이 멈춥니다**
(뉴스 수집, 채용공고 수집, 결제 타임아웃 처리 등 5개).

```java
// 빈으로 한 번 만들어 재사용 + 타임아웃
@Bean
public HttpClient externalHttpClient() {
    return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}

// 요청별 타임아웃
HttpRequest request = HttpRequest.newBuilder()
        .uri(...)
        .timeout(Duration.ofSeconds(120))       // DALL·E는 오래 걸리므로 넉넉히
        .header("Content-Type", "application/json")
        .POST(...)
        .build();
```

> **이 프로젝트의 HTTP 클라이언트가 이제 6종류입니다.**
> `RestTemplate`(빈), `RestClient`(빈), `new RestTemplate()`(llm), `WebClient`(interview),
> `HttpURLConnection`(newsData), `HttpClient`(ai).
> → [interview.md 4장](interview.md#4-webclientconfig--응답-타임아웃이-없다)

## 3-4. 🟠 쓰이지 않는 이미지 생성 메서드 2개 — 그리고 없는 API 키

```java
private static final Dotenv dotenv = Dotenv.load();
private static final String GEMINI_API_KEY    = dotenv.get("GEMINI_API_KEY");     // ✅ .env에 있음
private static final String STABILITY_API_KEY = dotenv.get("STABILITY_API_KEY");   // ❌ .env에 없음
private static final String OPENAI_API_KEY    = dotenv.get("OPENAI_API_KEY");      // ✅
private static final String DEEPAI_API_KEY    = dotenv.get("DEEPAI_API_KEY");       // ❌ .env에 없음
```

`.env`의 키 목록에 `STABILITY_API_KEY`와 `DEEPAI_API_KEY`가 **없습니다.**
따라서 두 상수는 `null`입니다.

**다행히 그 키를 쓰는 메서드도 호출되지 않습니다.**

| 메서드 | 호출됨 | 키 존재 |
|---|---|---|
| `generateImageWithDalle3` | ✅ (사용 중) | ✅ |
| `generateImageWithStability` | ❌ | ❌ |
| `generateImageWithDeepAI` | ❌ | ❌ |

**이미지 생성 서비스를 3개 시도해보고 DALL·E로 정착한 흔적입니다.**
`private`이고 호출처가 없으므로 **IDE가 "사용되지 않음" 경고를 표시**할 것입니다.

**삭제 대상입니다.** 남겨두면 "폴백이 있다"고 오해하게 됩니다.

> `generateImageWithStability`의 **multipart 본문을 문자열로 직접 조립**하는 부분도
> [3-2](#3-2--json을-문자열로-직접-만든다--개행에서-깨진다)와 같은 문제를 갖고 있습니다.
> ```java
> String multipartBody = "--" + boundary + "\r\n" +
>         "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n" +
>         promptText + "\r\n" + ...
> ```
> `promptText`에 boundary 문자열이 우연히 포함되면 본문이 깨집니다.

## 3-5. 🟠 중복 기사 방지가 없다

```java
private List<NewsItem> fetchFromRss(String rssUrl) throws Exception {
    ...
    for (int i = 0; i < Math.min(3, items.size()); i++) {     // 피드당 최신 3개
        ...
    }
}
```

**RSS의 최신 3개를 가져오는데, 이전 실행과 겹치는지 확인하지 않습니다.**

```
1일차 09:00  OpenAI 블로그 최신 3개 (A, B, C) → 기사 생성 → 저장
3일차 09:00  OpenAI 블로그 최신 3개 (A, B, C) → 새 글이 없었다면 같은 소스로 또 기사 생성!
```

**같은 내용의 기사가 반복 생성되고, LLM 호출 4회 + DALL·E 비용이 매번 발생합니다.**

**`AiUpdate` 엔티티에 원본 링크가 없어서 중복 판정도 불가능합니다.**

```java
@Entity
public class AiUpdate extends BaseTimeEntity {
    @Lob private String title;
    @Lob private String content;
    @Lob private String imageUrl;
    // ⚠️ 원본 링크, 소스 기사 목록이 없다
}
```

**개선 — 처리한 링크를 기록하고 새 항목만 대상으로 삼습니다**

```java
@Entity
public class AiUpdate extends BaseTimeEntity {
    ...
    @Column(length = 1000)
    private String sourceLinks;        // 이 기사가 참조한 원본 링크들 (쉼표 구분 또는 JSON)
}
```

```java
// 새 링크만 골라내기
List<String> links = allNews.stream().map(n -> n.link).toList();
Set<String> processed = aiUpdateRepository.findProcessedLinks(links);
List<NewsItem> fresh = allNews.stream().filter(n -> !processed.contains(n.link)).toList();

if (fresh.isEmpty()) {
    log.info("[AI업데이트] 새 소식이 없어 건너뜁니다.");
    return;                          // ★ LLM 비용 절약
}
```

`recruitmentNotice`가 이미 `findExistingLinks`로 이 패턴을 씁니다.
→ [recruitmentNotice.md 2-6](recruitmentNotice.md#2-6--잘-만든-것--in-절-배치-조회로-n1-제거)

## 3-6. 🟠 `@Scheduled(cron = "0 0 9 */2 * *")` — "2일마다"가 아니다

```java
@Scheduled(cron = "0 0 9 */2 * *", zone = "Asia/Seoul")
public void scheduleDailyAiUpdates() {
    System.out.println("2일에 한번 아침 9시 AI 뉴스 업데이트 작업을 시작합니다...");
```

**cron 6자리 = `초 분 시 일 월 요일`** 입니다.

```
0    0    9    */2   *    *
초   분   시    일    월   요일
```

`*/2`가 **일(day-of-month)** 필드에 있으므로 **1, 3, 5, 7, ..., 31일**에 실행됩니다.

**월 경계에서 간격이 깨집니다.**

```
1월 29일 → 1월 31일 → 2월 1일 ← 하루 간격!
             (2일)      (1일)
```

또 **31일이 없는 달**에서는 마지막 실행이 29일이 됩니다.

**"정확히 2일마다"를 원하면 cron으로는 표현할 수 없습니다.**

```java
// ① fixedDelay — 이전 실행 종료 후 48시간
@Scheduled(fixedDelay = 48 * 60 * 60 * 1000L, initialDelay = 60_000L)

// ② 매일 실행하고 코드로 판단 (권장)
@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")     // 매일 09:00
public void scheduleAiUpdates() {
    LocalDateTime lastRun = aiUpdateRepository.findLatestCreatedAt().orElse(LocalDateTime.MIN);
    if (lastRun.isAfter(LocalDateTime.now().minusDays(2))) {
        log.debug("[AI업데이트] 최근 2일 내 실행됨 - 건너뜀");
        return;
    }
    ...
}
```

**②는 서버 재시작에도 안전합니다.** `fixedDelay`는 재시작하면 카운터가 초기화됩니다.

## 3-7. 🟠 목록 조회에 정렬이 없다

```java
public ResponseDTO<Page<AIUpdateNewsResponseDto>> getAIUpdatesNewsList(Pageable pageable) {
    Page<AiUpdate> aiUpdatePage = repository.findAll(pageable);      // ⚠️ 정렬 없음
    Page<AIUpdateNewsResponseDto> dtoPage = aiUpdatePage.map(AIUpdateNewsResponseDto::fromEntity);
    return ResponseDTO.okWithData(dtoPage);
}
```

**`ORDER BY` 없이 `LIMIT/OFFSET`을 쓰면 결과 순서가 보장되지 않습니다.**

```
SELECT * FROM ai_update LIMIT 10 OFFSET 0    ← 1페이지
SELECT * FROM ai_update LIMIT 10 OFFSET 10   ← 2페이지
```

DB는 두 쿼리에서 **같은 순서를 보장하지 않습니다.**
InnoDB는 보통 PK 순서로 반환하지만 **스펙상 보장이 아닙니다.**
그래서 페이지를 넘길 때 **같은 기사가 두 번 보이거나 어떤 기사가 건너뛰어질 수 있습니다.**

**뉴스는 최신순이어야 합니다.**

```java
public Page<AIUpdateNewsResponseDto> getAIUpdatesNewsList(Pageable pageable) {
    return repository.findAllByOrderByCreatedAtDesc(pageable)
            .map(AIUpdateNewsResponseDto::fromEntity);
}
```

> 클라이언트가 `?sort=createdAt,desc`를 보내면 `Pageable`에 반영되지만,
> **기본 정렬은 서버가 보장해야 합니다.**
> `@PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)`도 방법입니다.

**그리고 서비스가 `ResponseDTO`를 반환합니다** — 계층 위반입니다.
→ [member.md 3-8](member.md#3-8-서비스가-responsedto를-반환하는-문제)

## 3-8. 🟠 `MockMultipartFile`을 운영 코드에서 쓴다

```java
private MultipartFile convertInputStreamToMultipartFile(InputStream inputStream, String fileUrl)
        throws IOException {
    String contentType = "image/png";
    String filename = UUID.randomUUID().toString() + ".png";
    byte[] content = inputStream.readAllBytes();

    return new MockMultipartFile("file", filename, contentType, content);   // ⚠️ 테스트용 클래스
}
```

`build.gradle`에도 그 흔적이 있습니다.

```gradle
//MockMultipartFile
implementation 'org.springframework:spring-test'
```

**`spring-test`를 `implementation`(운영 의존성)으로 넣었습니다.**

`MockMultipartFile`은 `org.springframework.mock.web` 패키지에 있는 **테스트 지원 클래스**입니다.

**문제**

1. **테스트 라이브러리가 운영 JAR에 포함됩니다** — 배포 산출물이 커지고, 의도하지 않은 클래스가 노출됩니다
2. **`Mock`이라는 이름이 코드를 오해하게 만듭니다** — 읽는 사람이 "여기 테스트 코드가 왜 있지?"라고 생각합니다
3. `spring-test`의 API는 **테스트 편의를 위한 것이라 호환성 보장이 약합니다**

**이 프로젝트에는 이미 정확한 대체물이 있습니다.**

```java
// global/util/S3/Base64DecodedMultipartFile.java — 직접 만든 MultipartFile 구현체
public class Base64DecodedMultipartFile implements MultipartFile { ... }
```

→ [global 8-3](../global.md#8-3-base64decodedmultipartfile--인터페이스-직접-구현-예제)

**`byte[]`를 받는 생성자를 추가하면 `MockMultipartFile`이 불필요합니다.**

```java
public class ByteArrayMultipartFile implements MultipartFile {
    private final byte[] content;
    private final String originalFilename;
    private final String contentType;

    public ByteArrayMultipartFile(byte[] content, String originalFilename, String contentType) { ... }
    // getName, getSize, getBytes, getInputStream, transferTo 구현
}
```

**또는 `S3ImageService`에 `byte[]`를 받는 메서드를 추가하는 것이 더 단순합니다.**

```java
public String upload(byte[] content, String filename, String contentType) { ... }
```

> `generateImageWithDalle3`의 `url.openStream()`에도 **타임아웃이 없습니다.**
> DALL·E가 준 이미지 URL에서 다운로드가 멈추면 스케줄러가 묶입니다.
> ```java
> URLConnection conn = new URL(imageUrl).openConnection();
> conn.setConnectTimeout(10_000);
> conn.setReadTimeout(30_000);
> try (InputStream is = conn.getInputStream()) { ... }
> ```

## 3-9. 🟠 로깅과 예외

```java
@Scheduled(cron = "0 0 9 */2 * *", zone = "Asia/Seoul")
public void scheduleDailyAiUpdates() {
    System.out.println("2일에 한번 아침 9시 AI 뉴스 업데이트 작업을 시작합니다...");
    try {
        getLatestAiUpdates();
        System.out.println("AI 뉴스 업데이트 작업이 성공적으로 완료되었습니다.");
    } catch (Exception e) {
        System.err.println("AI 뉴스 업데이트 작업 중 오류가 발생했습니다: " + e.getMessage());
        e.printStackTrace();
    }
}
```

**`@Slf4j`도 없고 `System.out`/`printStackTrace`를 씁니다.**
프로젝트 전체 27건 중 3건이 이 파일입니다.
→ [llm.md 6장](llm.md#6--로깅--systemoutprintln과-printstacktrace)

**예외를 삼키는 것 자체는 스케줄러에서 올바릅니다**
([recruitmentNotice.md 2-3](recruitmentNotice.md#2-3--스케줄러-메서드가-예외를-다시-던진다)와 반대).
하지만 `printStackTrace()`는 로그 프레임워크를 우회해 **어느 요청/클래스에서 났는지 정보가 없습니다.**

```java
@Slf4j
@Service
public class AiUpdatesService {

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduleAiUpdates() {
        log.info("[AI업데이트] 작업 시작");
        try {
            getLatestAiUpdates();
            log.info("[AI업데이트] 작업 완료");
        } catch (Exception e) {
            log.error("[AI업데이트] 작업 실패 - 다음 주기에 재시도합니다", e);
        }
    }
}
```

## 3-10. 🟡 크롤링 실패 문구가 LLM 프롬프트에 들어간다

```java
private String fetchFullContent(String url) {
    try {
        Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0 ...").get();
        Element mainContent = doc.body().selectFirst("article, .content, .post-content, .post");
        return (mainContent != null) ? mainContent.text() : doc.body().text();
    } catch (Exception e) {
        return "본문을 불러올 수 없습니다.";           // ⚠️ 이 문자열이 프롬프트로 간다
    }
}
```

3개 항목 중 2개가 크롤링에 실패하면 프롬프트가 이렇게 됩니다.

```
- 제목: OpenAI announces ...
  내용: 본문을 불러올 수 없습니다.

- 제목: Google introduces ...
  내용: 본문을 불러올 수 없습니다.
```

**LLM은 제목만 보고 기사를 "지어냅니다"(환각).**
그리고 그 기사가 **DB에 저장되어 사용자에게 뉴스로 제공됩니다.**

```java
// 실패한 항목은 제외하는 편이 안전
private Optional<String> fetchFullContent(String url) {
    try {
        ...
        return Optional.of(text);
    } catch (Exception e) {
        log.warn("[AI업데이트] 본문 크롤링 실패 - url: {}", url, e);
        return Optional.empty();
    }
}

// 본문을 얻은 항목만 사용
List<NewsItem> usable = allNews.stream()
        .filter(item -> fetchFullContent(item.link).map(c -> { item.description = c; return true; })
                                                   .orElse(false))
        .toList();
if (usable.isEmpty()) {
    log.warn("[AI업데이트] 사용 가능한 본문이 없어 건너뜁니다.");
    return;
}
```

**`aibot`의 폴백 사과문**([aibot.md 2-3](aibot.md#2-3--폴백-답변이-댓글로-게시된다))과
**`interview`의 가짜 점수**([interview.md 3-5](interview.md#3-5--ai-평가-실패-시-가짜-점수를-반환한다))와
**같은 종류의 문제** — **실패를 그럴듯한 결과로 위장합니다.**

## 3-11. 🟡 `Jsoup` 셀렉터와 NPE 위험

```java
Elements items = doc.select("item");
for (int i = 0; i < Math.min(3, items.size()); i++) {
    Element item = items.get(i);
    String title       = item.select("title").first().text();        // ⚠️ NPE
    String link        = item.select("link").first().text();         // ⚠️ NPE
    String description = item.select("description").first().text();  // ⚠️ NPE
    String pubDate     = item.select("pubDate").first().text();      // ⚠️ NPE
}
```

`select()`가 결과를 못 찾으면 `first()`는 `null`을 반환하고, `.text()`에서 **NPE**입니다.

**RSS 표준에서 `description`과 `pubDate`는 선택 항목입니다.**
피드 하나에 그 태그가 없으면 `fetchAndAddNews`의 `catch (Exception e)`가 잡아
**그 피드 전체를 버립니다.**

`Jsoup`에는 안전한 대체 메서드가 있습니다.

```java
String title       = item.selectFirst("title") != null ? item.selectFirst("title").text() : "";
// 또는 Optional로
String description = Optional.ofNullable(item.selectFirst("description"))
                             .map(Element::text).orElse("");
```

**`Math.min(3, items.size())`로 개수를 제한한 것은 잘한 부분입니다.**
`items.get(3)`으로 `IndexOutOfBoundsException`이 나는 것을 막습니다.

## 3-12. 🟡 `new ObjectMapper()` — 빈을 쓰지 않는다

```java
private final ObjectMapper objectMapper = new ObjectMapper();
```

`AppConfig`에 `ObjectMapper` 빈이 있습니다(`NON_NULL` + `JavaTimeModule` 설정 포함).
→ [global 1-1](../global.md#1-1-appconfig--내가-만들지-않은-클래스를-빈으로-만들기)

`@RequiredArgsConstructor`가 이미 붙어 있으므로 **`final` 필드로 선언만 하면 주입됩니다.**

```java
@Service
@RequiredArgsConstructor
public class AiUpdatesService {
    private final ObjectMapper objectMapper;      // 주입
    private final S3ImageService s3ImageService;
    private final AiUpdateRepository repository;
}
```

프로젝트 전체에서 `new ObjectMapper()`를 쓰는 곳:
`AiUpdatesService`, `CustomResponseUtil`, `JwtAuthenticationFilter`(2회), `MessageResponseDto`.

## 3-13. 🟡 `NewsItem` 내부 클래스

```java
private static class NewsItem {
    String title;        // package-private 가변 필드
    String link;
    String description;
    String pubDate;

    public NewsItem(String title, String link, String description, String pubDate) { ... }
}
```

```java
for (NewsItem item : allNews) {
    item.description = fetchFullContent(item.link);      // ★ 필드 직접 수정
}
```

**동작하지만 `record`가 더 어울립니다.**

```java
private record NewsItem(String title, String link, String description, String pubDate) {
    NewsItem withDescription(String newDescription) {
        return new NewsItem(title, link, newDescription, pubDate);      // 불변 갱신
    }
}
```

`static`을 붙인 것은 **올바릅니다.** `static`이 없는 내부 클래스는
외부 인스턴스 참조를 암묵적으로 갖고 있어 메모리 누수의 원인이 됩니다.

---

# 4. `AiNews` 엔티티

```java
@Entity
@Getter
@Setter                       // ⚠️
@NoArgsConstructor             // ⚠️ public
@AllArgsConstructor
@Table(name = "ai_news")
@Builder
public class AiNews extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)                    // 제목은 필수
    private String title;

    @Column(columnDefinition = "TEXT")            // 기사 요약(네이버 제공)
    private String content;

    @Column(columnDefinition = "TEXT")            // AI 요약 (선택)
    private String summary;
}
```

**`AiUpdate`와 `AiNews`가 무엇이 다른지 코드만으로는 알기 어렵습니다.**

| | `AiUpdate` | `AiNews` |
|---|---|---|
| 출처 | OpenAI·Google 공식 블로그 RSS | 네이버 뉴스 (주석 기준) |
| 생성 | LLM이 기사 작성 | 원문 요약 + AI 요약 |
| 이미지 | DALL·E 생성 → S3 | 없음 |
| 엔드포인트 | `/api/ai-updates` | `/api/ai-news` |
| 링크 보관 | ❌ | ❌ |

**둘 다 원본 링크가 없습니다.** 뉴스 서비스에서 원문 링크가 없으면
사용자가 출처를 확인할 수 없고, **저작권 관점에서도 문제**가 됩니다.

`newsData` 도메인에도 뉴스가 있어 **뉴스 관련 엔티티가 3개**입니다
(`AiUpdate`, `AiNews`, `NewsData`). → [newsData.md](newsData.md)

**`@Setter` + `@Builder` + `@AllArgsConstructor` + `@NoArgsConstructor`가 전부 붙어 있어
객체를 만드는 경로가 4가지**입니다. 어느 것이 의도된 경로인지 알 수 없습니다.

```java
// 정리한 형태 — 생성 경로를 빌더로 단일화
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ai_news")
public class AiNews extends BaseTimeEntity {
    ...
    @Builder
    private AiNews(String title, String content, String summary) { ... }

    public void updateSummary(String summary) { this.summary = summary; }   // 필요한 변경만 노출
}
```

`AiUpdate`는 이렇게 **잘 되어 있습니다.**

```java
@Entity
@Getter
@NoArgsConstructor                      // (access는 아쉽지만)
public class AiUpdate extends BaseTimeEntity {
    ...
    @Builder
    private AiUpdate(String title, String content, String imageUrl) { ... }   // ★ private 생성자
}
```

**같은 패키지의 두 엔티티가 다른 스타일입니다.**

---

# 5. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | JSON을 문자열로 조립 → 개행·백슬래시에서 깨짐 | [3-2](#3-2--json을-문자열로-직접-만든다--개행에서-깨진다) |
| 🔴 2 | `HttpClient`를 호출마다 생성 + 타임아웃 없음 → 스케줄러 전체 정지 위험 | [3-3](#3-3--httpclient를-호출마다-새로-만든다) |
| 🟠 3 | 중복 기사 방지 없음 → 같은 소스로 반복 생성 + LLM 비용 | [3-5](#3-5--중복-기사-방지가-없다) |
| 🟠 4 | 크롤링 실패 문구가 프롬프트로 들어가 LLM이 기사를 지어냄 | [3-10](#3-10--크롤링-실패-문구가-llm-프롬프트에-들어간다) |
| 🟠 5 | 목록 조회에 `ORDER BY` 없음 → 페이징 결과 불안정 | [3-7](#3-7--목록-조회에-정렬이-없다) |
| 🟠 6 | `MockMultipartFile`(테스트 라이브러리)을 운영 코드에서 사용 | [3-8](#3-8--mockmultipartfile을-운영-코드에서-쓴다) |
| 🟠 7 | `Dotenv.load()` static + 존재하지 않는 API 키 2개 | [3-4](#3-4--쓰이지-않는-이미지-생성-메서드-2개--그리고-없는-api-키) |
| 🟠 8 | `ai_model_integrated` 데이터를 채우는 코드 없음 | [2-5](#2-5--이-테이블도-채우는-코드가-없다) |
| 🟠 9 | `determineOpenSource` 제작사 하드코딩 → 모델 단위 속성이어야 함 | [1-5](#1-5--determineopensource의-하드코딩) |
| 🟡 10 | cron `*/2` — 월 경계에서 간격이 깨짐 | [3-6](#3-6--scheduledcron--0-0-9-2----2일마다가-아니다) |
| 🟡 11 | 미사용 이미지 생성 메서드 2개 (multipart 문자열 조립 포함) | [3-4](#3-4--쓰이지-않는-이미지-생성-메서드-2개--그리고-없는-api-키) |
| 🟡 12 | `System.out`/`printStackTrace` + `@Slf4j` 미사용 | [3-9](#3-9--로깅과-예외) |
| 🟡 13 | `Jsoup` `first().text()` NPE 위험 | [3-11](#3-11--jsoup-셀렉터와-npe-위험) |
| 🟡 14 | `new ObjectMapper()` — 빈 미사용 | [3-12](#3-12--new-objectmapper--빈을-쓰지-않는다) |
| 🟡 15 | 서비스가 `ResponseDTO` 반환 | [3-7](#3-7--목록-조회에-정렬이-없다) |
| 🟡 16 | 원본 링크를 저장하지 않음 (`AiUpdate`, `AiNews` 모두) | [4장](#4-ainews-엔티티) |
| 🟡 17 | 결측 벤치마크가 `0.0`이 되어 신규 모델이 불리 | [1-3](#1-3-결측치null-처리) |
| 🟢 18 | `@Setter` 사용 (`AiNews`, `AIModelIntegrated`) | [2-3](#2-3--setter가-붙어-있다) |
| 🟢 19 | `AIModelIntegrated`가 `BaseTimeEntity` 미상속 → 갱신 시각 추적 불가 | [2-4](#2-4--basetimeentity를-상속하지-않는다) |
| 🟢 20 | `Model`을 setter로 조립 → `record`/빌더 | [1-6](#1-6--model의-setter-조립) |
| 🟢 21 | `AiNews`/`AiUpdate` 엔티티 스타일 불일치 (생성 경로 4가지) | [4장](#4-ainews-엔티티) |
| 🟢 22 | `NewsItem` 가변 내부 클래스 → `record` | [3-13](#3-13--newsitem-내부-클래스) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **Min-Max 정규화 + `normalizeInverted`** | 스케일과 방향이 다른 지표를 공정하게 합성. 알고리즘이 정확 |
| **`ScoreRange` 사전 계산** | O(n²) → O(n) |
| **결측치 `filter(Objects::nonNull).average()`** | 벤치마크가 모델마다 다른 현실에 맞는 처리 |
| **속도를 TPS + TTFT로 분리** | 처리량과 응답성이 다른 특성임을 이해 |
| `ModelScoreCalculator`가 순수 계산 클래스 | 스프링 의존 없음 → 테스트 시작점으로 최적 |
| `AIModelIntegrated` 자연키(`String modelId`) | 외부 데이터 동기화에 유리. `saveAll`이 upsert처럼 동작 |
| 벤치마크 컬럼마다 의미 주석 | 코드만으로 알 수 없는 정보를 기록 |
| **기사 → LLM → 영어 이미지 프롬프트 → DALL·E 연쇄** | 한국어를 이미지 모델에 직접 주는 문제를 우회 |
| `Math.min(3, items.size())` | `IndexOutOfBoundsException` 방어 |
| `static` 내부 클래스 | 외부 인스턴스 암묵 참조를 피함 |
| `Jsoup` `userAgent` 설정 | 크롤링 차단 회피 |
| RSS 실패를 피드별로 격리 (`fetchAndAddNews`) | 한 피드가 죽어도 다른 피드는 수집 |
| `AiUpdate`의 `private` 생성자 + `@Builder` | 생성 경로 단일화 (같은 패키지 `AiNews`는 반대) |

**핵심 평가**: **한 패키지에 수준이 크게 다른 두 코드가 있습니다.**

`recommendation/`의 정규화 알고리즘은 **정확하고 잘 구조화된 코드**입니다.
스케일·방향·결측치를 모두 의식했고, 순수 함수라 테스트도 쉽습니다.

`AiUpdatesService`는 **파이프라인 아이디어는 좋은데 구현이 위험합니다.**
문자열 JSON 조립, 타임아웃 없는 `HttpClient` 4개, 중복 방지 없음, 실패 위장.

가장 먼저 할 일 두 가지:

1. **`callGemini`의 JSON을 `ObjectMapper`로 교체** — 지금은 개행이 들어가면 요청이 깨집니다
2. **`HttpClient`를 빈으로 빼고 타임아웃 설정** — 이 하나가 멈추면 스케줄러 5개가 함께 멈춥니다

---

## 다음 문서

- [newsData.md](newsData.md) — 세 번째 뉴스 엔티티가 있는 도메인
- [llm.md](llm.md) — 올바른 Gemini 호출 방식이 이미 구현된 곳
- [question.md](question.md) — `ai_models` 카탈로그 (이 도메인의 `ai_model_integrated`와 별개)
- [../global.md](../global.md) — `ObjectMapper` 빈, `S3ImageService`, `Base64DecodedMultipartFile`
