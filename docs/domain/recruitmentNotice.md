# recruitmentNotice — 채용공고 (사람인 OpenAPI 연동)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [global.md](../global.md)
>
> 파일 5개. **외부 OpenAPI를 주기적으로 긁어 DB에 쌓는 가장 단순한 수집 파이프라인**입니다.
> 코드가 짧아 **"스케줄러 + 외부 API + 중복 제거"의 기본 패턴을 배우기에 가장 좋습니다.**
> 반면 API 키 관리와 트랜잭션 처리에 문제가 있습니다.

---

## 파일 지도

```
domain/recruitmentNotice/
├── controller/RecruitmentController.java     GET /api/recruitment 하나
├── dto/response/RecruitmentResponseDto.java
├── entity/Recruitment.java
├── repository/RecruitmentRepository.java
└── service/RecruitmentService.java           ★ 스케줄러 + 수집 로직

의존하는 공용 유틸 (newsData 도메인에 있음)
├── newsData/util/HttpClientUtil.java         HttpURLConnection 기반 GET
└── newsData/util/HtmlUtils.java              HTML 태그·엔티티 제거
```

**흐름**

```
[10분마다]  @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
     │
     ▼ fetchAndSaveRecruitment()
     │
     ├─ ① 사람인 API 호출
     │     GET https://oapi.saramin.co.kr/job-search
     │         ?access-key=...&job_mid_cd=87&start=0&count=100
     │
     ├─ ② JSON → Recruitment 엔티티 리스트 변환 (항목별 오류는 건너뜀)
     ├─ ③ 응답 내 중복 제거 (link 기준, HashSet)
     ├─ ④ DB에 이미 있는 link 제외 (IN 절 배치 조회)
     └─ ⑤ saveAll()

[조회]  GET /api/recruitment  (무인증)
     └─ 만료 전 + 최근 2개월 공고를 pubDate 내림차순으로 전체 반환
```

---

# 1. `Recruitment` 엔티티

```java
@Entity
@Table(name = "recruitment")
@Getter
@AllArgsConstructor
@NoArgsConstructor              // ⚠️ public
@Builder
public class Recruitment extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recruitmentId;

    @Column(name = "link")                                    // ⚠️ 유니크 제약 없음
    private String link;

    @Column(name = "company_name")   private String companyName;
    @Column(name = "title")          private String title;

    @Column(name = "technology_stack", columnDefinition = "TEXT")
    private String technologyStack;

    @Column(name = "work_location")  private String workLocation;
    @Column(name = "career_level")   private String careerLevel;
    @Column(name = "work_type")      private String workType;
    @Column(name = "education_level") private String educationLevel;

    @Column(name = "pub_date")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")              // ⚠️ 엔티티에 JSON 어노테이션
    private LocalDateTime pubDate;

    @Column(name = "expiration_date")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expirationDate;

    public void setPubDateFromTimestamp(String timestampStr) { ... }
    public void setExpirationDateFromTimestamp(String timestampStr) { ... }
}
```

## 1-1. 🔴 `link`에 유니크 제약이 없다

`link`(공고 URL)가 **사실상 이 테이블의 자연키**입니다.
중복 제거를 전부 애플리케이션 코드로 하고 있습니다.

```java
recruitmentList = removeDuplicates(recruitmentList);          // 응답 내 중복
recruitmentList = filterExistingRecruitment(recruitmentList); // DB 중복
```

**하지만 "조회 후 삽입"은 동시 실행에 취약합니다.**

```
스케줄러 실행이 겹치거나(다중 인스턴스, cron 중복) 하면
→ 두 실행이 모두 "이 link는 DB에 없다"고 판정
→ 같은 공고가 2건 저장 → 목록에 중복 노출
```

```java
// 고치기 — DB가 마지막 방어선
@Table(name = "recruitment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_recruitment_link", columnNames = {"link"})
})
```

> ⚠️ `link`는 URL이라 길 수 있습니다. MySQL의 인덱스 키 길이 제한(InnoDB, utf8mb4는 191자)
> 때문에 `@Column(length = 500)`인 컬럼에 그대로 유니크를 걸면 실패할 수 있습니다.
> **해시 컬럼을 두거나 `@Column(length = 191)`로 제한**하는 방법이 있습니다.
>
> ```java
> @Column(name = "link", nullable = false, length = 500)
> private String link;
>
> @Column(name = "link_hash", nullable = false, length = 64)     // SHA-256 hex
> private String linkHash;      // 유니크 제약은 이쪽에
> ```

## 1-2. 🟠 `setXxxFromTimestamp` — setter인가 도메인 메서드인가

```java
public void setPubDateFromTimestamp(String timestampStr) {
    if (timestampStr != null && !timestampStr.isEmpty()) {
        try {
            long timestamp = Long.parseLong(timestampStr);
            this.pubDate = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamp),
                    ZoneId.systemDefault());                  // ⚠️ 시스템 타임존 의존
        } catch (NumberFormatException e) {
            // 로그 처리 또는 기본값 설정
            this.pubDate = null;                              // ⚠️ 조용히 null
        }
    }
}
```

**좋은 점**: `String` 타임스탬프 → `LocalDateTime` 변환 책임을 엔티티가 갖습니다.
서비스가 파싱 코드를 들고 있지 않아 깔끔합니다.

**문제 3가지**

### ① 빌더로 만든 뒤에 값을 채운다 — 불완전한 객체가 생긴다

```java
// RecruitmentService.createRecruitmentFromJson
Recruitment recruitment = Recruitment.builder()
        .link(...).companyName(...).title(...)
        .build();                                   // ← pubDate가 null인 상태로 존재

recruitment.setPubDateFromTimestamp(pubDateStr);     // 그 다음에 채운다
recruitment.setExpirationDateFromTimestamp(expirationDate);
```

**빌더의 목적은 "완전한 객체를 한 번에 만드는 것"** 인데,
두 필드를 나중에 채우니 **중간에 불완전한 상태**가 존재합니다.

```java
// 개선 — 변환을 정적 팩토리로 옮겨 한 번에 완성
public static Recruitment from(JsonNode job) {
    return Recruitment.builder()
            .link(job.get("url").asText())
            .companyName(HtmlUtils.cleanText(job.get("company").get("detail").get("name").asText()))
            ...
            .pubDate(parseEpochSecond(job.get("posting-timestamp").asText()))
            .expirationDate(parseEpochSecond(job.get("expiration-timestamp").asText()))
            .build();
}

private static LocalDateTime parseEpochSecond(String s) {
    if (s == null || s.isBlank()) return null;
    try {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(s)), KST);
    } catch (NumberFormatException e) {
        return null;
    }
}
```

### ② `ZoneId.systemDefault()`는 환경에 따라 달라진다

`Instant`(UTC 절대 시각)를 `LocalDateTime`(타임존 없는 시각)으로 바꿀 때
**어느 타임존 기준으로 볼지**를 정해야 합니다.

```java
ZoneId.systemDefault()      // JVM 기본 타임존 — 환경에 따라 UTC일 수도, KST일 수도
ZoneId.of("Asia/Seoul")     // 명시 — 어디서 돌려도 같은 결과
```

`docker-compose-prod.yml`에 `TZ=Asia/Seoul`이 있어 지금은 맞습니다.
**그 설정이 빠지면 모든 공고 날짜가 9시간 어긋납니다.**
그러면 "만료일이 오늘 이후" 조회가 틀려 **마감된 공고가 노출되거나 살아있는 공고가 숨습니다.**

[attendance.md 1-2](attendance.md#1-2-localdate-vs-localdatetime)와 같은 문제입니다.

### ③ 파싱 실패를 `null`로 삼킨다

```java
} catch (NumberFormatException e) {
    // 로그 처리 또는 기본값 설정        ← 주석만 있고 아무것도 안 함
    this.pubDate = null;
}
```

`pubDate`가 `null`이면 조회 쿼리 `r.pubDate > :twoMonthsAgo`에서
**`NULL > 값`은 `NULL`(=false 취급)** 이 되어 **그 공고는 영원히 조회되지 않습니다.**

DB에는 쌓이는데 화면에는 안 나오는 유령 데이터가 됩니다. **최소한 로그는 남겨야 합니다.**

## 1-3. 🟡 엔티티에 `@JsonFormat`이 붙어 있다

```java
@Column(name = "pub_date")
@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")      // ← Jackson(JSON) 어노테이션
private LocalDateTime pubDate;
```

`@JsonFormat`은 **HTTP 응답 직렬화용**입니다. 엔티티에 붙는다는 것은
**"이 엔티티가 JSON으로 나갈 수 있다"** 는 전제를 담고 있습니다.

이 프로젝트는 `RecruitmentResponseDto`로 변환해서 반환하므로 **엔티티의 `@JsonFormat`은 쓰이지 않습니다**
(DTO에도 같은 어노테이션이 붙어 있고, 그쪽이 실제로 동작합니다).

**엔티티는 영속성만, DTO는 표현만** 담당하는 것이 계층 분리의 원칙입니다. 엔티티 쪽은 지워야 합니다.

---

# 2. `RecruitmentService` — 수집 파이프라인

## 2-1. 🔴 API 키를 `Dotenv`로 직접 읽는다

```java
@Slf4j @Service @RequiredArgsConstructor
public class RecruitmentService {

    private static Dotenv dotenv = Dotenv.load();        // 🔴 static 초기화
    private String authKey = dotenv.get("SARAMIN_KEY");   // 🔴 인스턴스 필드인데 final 아님

    private static final String SARAMIN_API_URL = "https://oapi.saramin.co.kr/job-search";
    private static final int DEFAULT_DISPLAY_COUNT = 100;
```

**문제**

1. **`.env`가 없으면 클래스 로딩 시점에 죽습니다.**
   `Dotenv.load()`가 예외를 던지고, static 초기화 중 예외는 `ExceptionInInitializerError`가 되어
   **이 서비스를 쓰는 모든 빈이 생성 실패**합니다. → 애플리케이션 기동 실패.
2. **Spring 설정 체계를 우회합니다.** `application.yml`에
   `spring.config.import: optional:file:.env[.properties]`가 이미 있으므로
   `@Value("${SARAMIN_KEY}")`로 읽을 수 있습니다.
3. **테스트에서 값을 갈아끼울 수 없습니다.**

같은 문제가 5곳에 있습니다(`JwtKey`, `S3Config`, `EmailConfig`, `S3ImageService`, 여기).
→ [global 1-7](../global.md#1-7-공통-문제--dotenvload를-static-필드에서-호출)

```java
// 고치기
@Service
@Slf4j
public class RecruitmentService {

    private final HttpClientUtil httpClient;
    private final RecruitmentRepository recruitmentRepository;
    private final ObjectMapper objectMapper;
    private final String authKey;                         // final로 불변 보장

    public RecruitmentService(HttpClientUtil httpClient,
                              RecruitmentRepository recruitmentRepository,
                              ObjectMapper objectMapper,
                              @Value("${SARAMIN_KEY}") String authKey) {
        this.httpClient = httpClient;
        this.recruitmentRepository = recruitmentRepository;
        this.objectMapper = objectMapper;
        this.authKey = authKey;
    }
```

## 2-2. 🔴 API 키가 URL 쿼리스트링에 들어간다

```java
private String buildApiUrl(int jobCode, int start, int count) {
    return String.format("%s?access-key=%s&job_mid_cd=%d&start=%d&count=%d",
            SARAMIN_API_URL, authKey, jobCode, start, count);
}
```

**사람인 OpenAPI 규격이 그렇기 때문에 어쩔 수 없는 부분**이지만, 위험을 알아야 합니다.

- URL은 **로그·모니터링·프록시·에러 리포트에 그대로 기록**됩니다
- 예외 메시지에도 포함됩니다:
  ```java
  throw new RuntimeException("API URL이 잘못되었습니다: " + apiUrl, e);    // HttpClientUtil
  ```
  **API 키가 스택트레이스에 찍혀 Loki로 전송됩니다.**

```java
// 최소한 로그에서는 마스킹
private String maskKey(String url) {
    return url.replaceAll("access-key=[^&]*", "access-key=***");
}
log.error("채용정보 API 호출 실패 - URL: {}", maskKey(apiUrl), e);
```

## 2-3. 🟠 스케줄러 메서드가 예외를 다시 던진다

```java
@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
@Transactional
public void fetchAndSaveRecruitment() {
    try {
        String response = getRecruitmentApiResponse();       // ★ 외부 API 호출 (트랜잭션 안!)
        List<Recruitment> recruitmentList = parseRecruitmentResponse(response);
        recruitmentList = removeDuplicates(recruitmentList);
        recruitmentList = filterExistingRecruitment(recruitmentList);

        if (!recruitmentList.isEmpty()) {
            recruitmentRepository.saveAll(recruitmentList);
            log.info("채용정보 데이터 {}건 저장 완료", recruitmentList.size());
        }
    } catch (Exception e) {
        log.error("채용정보 데이터 처리 중 오류", e);
        throw new RuntimeException("채용정보 데이터 처리 실패", e);       // ⚠️ 다시 던진다
    }
}
```

### 문제 ① 던져도 받는 사람이 없다

**`@Scheduled` 메서드의 예외는 아무도 처리하지 않습니다.**
스프링의 스케줄러가 잡아서 로그를 남기는 것이 끝입니다(그것도 이미 위에서 남겼습니다).

즉 `throw`는 **로그를 두 번 남기는 효과**만 있습니다.
그리고 `RuntimeException`을 던지므로 **트랜잭션이 롤백**됩니다.

```
공고 100건 중 파싱은 다 됐는데 saveAll 도중 1건이 실패
→ 예외 → 롤백 → 100건 전부 저장 안 됨
→ 10분 뒤 다시 시도 → 같은 데이터로 또 실패 → 무한 반복
```

**한 건의 불량 데이터가 수집을 영구히 막을 수 있습니다.**

```java
// 스케줄러는 예외를 삼키고 다음 주기를 기다리는 것이 정석
@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
public void fetchAndSaveRecruitment() {
    try {
        collectAndSave();
    } catch (Exception e) {
        log.error("채용정보 수집 실패 - 다음 주기에 재시도합니다", e);
        // 던지지 않는다
    }
}
```

### 문제 ② 트랜잭션 안에서 외부 API를 호출한다

`getRecruitmentApiResponse()`가 `@Transactional` 안에 있습니다.
`HttpClientUtil`의 타임아웃은 연결 5초 + 읽기 10초이므로 **최대 15초간 DB 커넥션을 붙잡습니다.**

[aibot.md 3장](aibot.md#3--transactional-안에서-llm을-호출한다)과 같은 문제입니다.

```java
// 트랜잭션을 저장 단계에만 걸기
public void fetchAndSaveRecruitment() {
    try {
        String response = getRecruitmentApiResponse();               // 트랜잭션 밖 ✅
        List<Recruitment> parsed = parseRecruitmentResponse(response);
        List<Recruitment> deduped = removeDuplicates(parsed);
        saveNewRecruitments(deduped);                                 // 여기만 @Transactional
    } catch (Exception e) {
        log.error("채용정보 수집 실패", e);
    }
}

@Transactional
public void saveNewRecruitments(List<Recruitment> candidates) {       // 별도 빈으로 분리해야 프록시 적용
    List<Recruitment> fresh = filterExistingRecruitment(candidates);
    if (!fresh.isEmpty()) recruitmentRepository.saveAll(fresh);
}
```

> ⚠️ **같은 클래스 안에서 호출하면 `@Transactional`이 무시됩니다**(self-invocation).
> 별도 빈으로 분리해야 합니다. → [기초개념 4-4](../00-기초개념.md#4-4-프록시--spring-마법의-정체)

## 2-4. 🟢 잘 만든 것 — 항목별 오류 격리

```java
private List<Recruitment> parseRecruitmentResponse(String response) throws Exception {
    JsonNode jsonNode = objectMapper.readTree(response);
    List<Recruitment> recruitmentList = new ArrayList<>();
    JsonNode jobs = jsonNode.get("jobs").get("job");

    if (jobs != null && jobs.isArray()) {
        for (JsonNode job : jobs) {
            try {
                recruitmentList.add(createRecruitmentFromJson(job));
            } catch (Exception e) {
                log.warn("채용정보 아이템 파싱 중 오류 - 해당 아이템 건너뜀: {}", e.getMessage());
            }
        }
    }
    return recruitmentList;
}
```

**한 건이 깨져도 나머지 99건은 저장됩니다.** 외부 데이터를 다룰 때의 정석입니다.

외부 API의 응답 구조는 **예고 없이 바뀝니다.** 특정 필드가 빠지거나 `null`이면
`job.get("company").get("detail")`이 `NullPointerException`을 냅니다.
그걸 항목 단위로 격리한 것이 좋은 판단입니다.

> 아쉬운 점: `log.warn`에 **어느 공고가 실패했는지**가 없습니다.
> `e.getMessage()`만으로는 원인을 찾기 어렵습니다.
> ```java
> log.warn("채용정보 파싱 실패 - url: {}, 사유: {}",
>          job.path("url").asText("(없음)"), e.getMessage());
> ```
>
> 그리고 `jsonNode.get("jobs").get("job")`은 **`jobs`가 없으면 NPE**입니다.
> Jackson의 `path()`는 없으면 `MissingNode`를 반환하므로 안전합니다:
> ```java
> JsonNode jobs = jsonNode.path("jobs").path("job");     // NPE 없음
> ```

## 2-5. 🟢 잘 만든 것 — `Set.add()` 중복 제거 관용구

```java
private List<Recruitment> removeDuplicates(List<Recruitment> recruitmentList) {
    Set<String> seen = new HashSet<>();
    return recruitmentList.stream()
            .filter(recruitment -> seen.add(recruitment.getLink()))   // add()는 중복시 false 리턴
            .collect(Collectors.toList());
}
```

**`Set.add()`는 새로 넣었을 때만 `true`를 반환합니다.**
이 성질을 `filter`에 쓰면 **순서를 유지하면서 중복을 제거**할 수 있습니다.

```java
// 대안 — Collectors.toMap (마지막 값 유지, 순서 보장 안 됨)
.collect(Collectors.toMap(Recruitment::getLink, r -> r, (a, b) -> a))
        .values().stream().toList();
```

`Set.add()` 방식이 더 짧고 순서도 유지되므로 **더 좋은 선택**입니다.
주석에 이유까지 적어둔 것도 좋습니다.
→ [기초개념 1-8](../00-기초개념.md#1-8-람다와-스트림)

> 다만 스트림 안에서 외부 상태(`seen`)를 바꾸는 것은
> **병렬 스트림(`parallelStream`)에서는 위험합니다**(`HashSet`은 스레드 안전하지 않음).
> 순차 스트림에서만 쓰세요.

## 2-6. 🟢 잘 만든 것 — `IN` 절 배치 조회로 N+1 제거

```java
// 리포지토리에 주석으로 흔적이 남아 있다
// 중복 체크용 (서비스에서 사용)
// boolean existsByLink(String link);                     ← 옛 방식 (건당 쿼리)
@Query(value = "SELECT COUNT(*) FROM recruitment WHERE link = ?1", nativeQuery = true)
Long countExistingByLink(String link);                    ← 이것도 미사용

// 배치로 기존 링크들 확인 (N+1 문제 해결)
@Query("SELECT r.link FROM Recruitment r WHERE r.link IN :links")
List<String> findExistingLinks(@Param("links") List<String> links);      ← 실제 사용
```

```java
private List<Recruitment> filterExistingRecruitment(List<Recruitment> recruitmentList) {
    if (recruitmentList.isEmpty()) return recruitmentList;

    List<String> links = recruitmentList.stream().map(Recruitment::getLink).toList();

    List<String> existingLinks = recruitmentRepository.findExistingLinks(links);   // 쿼리 1회
    Set<String> existingLinkSet = new HashSet<>(existingLinks);                     // O(1) 조회

    return recruitmentList.stream()
            .filter(r -> !existingLinkSet.contains(r.getLink()))
            .collect(Collectors.toList());
}
```

**100건에 대해 쿼리 100회 → 1회.** 주석에 "N+1 문제 해결"이라고 명시했습니다.

**`List`를 `HashSet`으로 바꾼 것도 중요합니다.**
`List.contains()`는 O(n), `HashSet.contains()`는 O(1)입니다.
100 × 100 = 10,000회 비교가 100회로 줄어듭니다.

> ⚠️ **`IN` 절의 크기 제한**: 지금은 최대 100건이라 안전합니다.
> 하지만 `count`를 1,000으로 올리면 `IN (?, ?, ... ?)` 파라미터가 1,000개가 되어
> DB나 JDBC 드라이버 한도(Oracle은 1,000개)에 걸릴 수 있습니다.
> **500개 단위로 나눠 호출하는 것이 안전합니다.**
>
> ```java
> // Guava 없이 자바만으로 청크 나누기
> for (int i = 0; i < links.size(); i += 500) {
>     existing.addAll(recruitmentRepository.findExistingLinks(
>             links.subList(i, Math.min(i + 500, links.size()))));
> }
> ```

## 2-7. 🟠 하드코딩된 수집 조건

```java
private String getRecruitmentApiResponse() throws Exception {
    String apiUrl = buildApiUrl(87, 0, DEFAULT_DISPLAY_COUNT);       // ⚠️ 87이 무엇인가?
    ...
}
```

`87`은 사람인의 `job_mid_cd`(직무 중분류 코드)로 **"IT·인터넷"** 계열을 뜻합니다.
하지만 **코드에 아무 설명이 없습니다.** 6개월 뒤에 보면 무슨 숫자인지 알 수 없습니다.

```java
/** 사람인 직무 중분류 코드 — 84~92가 IT·인터넷 계열, 87은 웹·개발 */
private static final int JOB_MID_CODE_IT = 87;
```

**더 나아가면 설정으로 빼는 것이 맞습니다.**

```yaml
saramin:
  job-mid-codes: [84, 87, 92]     # 여러 직무를 수집하고 싶을 때
  count: 100
```

### `start=0` 고정 — 첫 페이지 100건만 가져온다

```java
buildApiUrl(87, 0, DEFAULT_DISPLAY_COUNT)
//              ↑ start가 항상 0
```

사람인 API는 `start`로 페이지를 넘깁니다. 항상 0이므로 **최신 100건만** 수집합니다.

10분마다 도니 놓치는 공고는 적겠지만, **10분 사이에 101건 이상 등록되면 유실**됩니다.
그리고 **초기 구축 시 과거 공고를 가져올 방법이 없습니다.**

```java
// 페이지를 넘기며 수집 (새 공고가 없으면 중단)
int start = 0;
while (start < MAX_PAGES * COUNT) {
    List<Recruitment> page = fetchPage(start, COUNT);
    List<Recruitment> fresh = filterExisting(page);
    if (fresh.isEmpty()) break;        // 이미 다 있는 페이지에 도달 → 중단
    save(fresh);
    start += COUNT;
}
```

## 2-8. 🟡 조회에 트랜잭션·페이징이 없다

```java
public List<RecruitmentResponseDto> getRecruitmentData() {       // @Transactional 없음
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime twoMonthsAgo = now.minusMonths(2);

    List<Recruitment> recruitments = recruitmentRepository
            .findByExpirationDateAfterAndPubDateAfterOrderByPubDateDesc(now, twoMonthsAgo);

    return recruitments.stream()
            .map(recruitment -> RecruitmentResponseDto.fromEntity(recruitment))
            .collect(Collectors.toList());
}
```

- **`@Transactional(readOnly = true)`가 없습니다.** 연관관계가 없는 엔티티라
  `LazyInitializationException` 위험은 없지만, 관례상 붙여야 합니다.
- **페이징이 없습니다.** 2개월 내 만료 전 공고가 5,000건이면
  **5,000개 엔티티 + 5,000개 DTO를 한 번에 메모리에 올려 JSON으로 내보냅니다.**
  10분마다 100건씩 쌓이면 하루 14,400건, 2개월이면 수십만 건이 될 수 있습니다.

```java
@Transactional(readOnly = true)
public Page<RecruitmentResponseDto> getRecruitmentData(Pageable pageable) {
    LocalDateTime now = LocalDateTime.now(KST);
    return recruitmentRepository
            .findActiveRecruitments(now, now.minusMonths(2), pageable)
            .map(RecruitmentResponseDto::fromEntity);
}
```

**인덱스도 필요합니다.**

```sql
CREATE INDEX idx_recruitment_active ON recruitment (expiration_date, pub_date DESC);
```

## 2-9. 🟡 남아 있는 흔적들

```java
// 이것도 시간 조정?
// 채용정보 데이터를 조회하고 DB에 저장
@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul") // 10분마다 실행
```

`// 이것도 시간 조정?` — 스스로에게 남긴 메모입니다.
**10분마다는 과합니다.** 채용공고는 실시간성이 필요하지 않고,
사람인 API에는 호출 한도가 있습니다.

```
10분 주기 = 하루 144회 × 100건 = 14,400건 요청
1시간 주기 = 하루 24회로 충분
```

```java
// HtmlUtils.cleanText -> 내가 설정한 util 코드 -> json 으로 받아온 것을 깔끔한 문자열로만 나타낼 수 있게
```

이 주석은 **자기 기록**이지 코드 설명이 아닙니다.
`HtmlUtils`가 무슨 일을 하는지는 그 클래스에 적으면 됩니다.

```java
// HttpClientUtil.java 맨 끝
// 추가하고 싶은 기능은 노션에 있으니 참고바람.
```

**외부 문서 링크 없이 "노션에 있음"이라고만 적혀 있습니다.**
6개월 뒤 다른 사람이 읽으면 어느 노션 페이지인지 알 수 없습니다.
할 일은 이슈 트래커에 두고, 코드에는 `// TODO(이름): 내용` 형태로 남기는 편이 낫습니다.

---

# 3. 공용 유틸 — `HttpClientUtil` / `HtmlUtils`

## 3-1. `HttpClientUtil` — `HttpURLConnection` 기반

```java
@Component
public class HttpClientUtil {

    public String get(String apiUrl, Map<String, String> requestHeaders) {
        HttpURLConnection con = connect(apiUrl);
        try {
            con.setRequestMethod("GET");
            setRequestHeaders(con, requestHeaders);

            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return readBody(con.getInputStream());
            } else {
                String errorResponse = readBody(con.getErrorStream());
                throw new RuntimeException("API 요청 실패. 응답 코드: " + responseCode + ", 응답: " + errorResponse);
            }
        } catch (IOException e) {
            throw new RuntimeException("API 요청과 응답 실패", e);
        } finally {
            con.disconnect();                                    // 🟢 반드시 정리
        }
    }

    private HttpURLConnection connect(String apiUrl) {
        ...
        connection.setConnectTimeout(5000);   // 🟢 타임아웃 설정
        connection.setReadTimeout(10000);
        ...
    }

    private String readBody(InputStream inputStream) {
        if (inputStream == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {   // 🟢 try-with-resources
            ...
        }
    }
}
```

**잘한 점**

| 항목 | 이유 |
|---|---|
| 타임아웃 명시 | 기본값은 **무한 대기**입니다. 설정하지 않으면 스레드가 영구히 묶일 수 있습니다 |
| `finally { con.disconnect(); }` | 커넥션 정리 보장 |
| try-with-resources로 스트림 관리 | 자동 close. → [email.md 5-1](email.md#5-1--inputstream을-닫지-않는다--자원-누수) |
| `StandardCharsets.UTF_8` 명시 | 한글 깨짐 방지. 플랫폼 기본 인코딩에 의존하지 않음 |
| 실패 시 `errorStream`도 읽음 | 오류 응답 본문을 로그에 남길 수 있음 |

**아쉬운 점**

1. **`HttpURLConnection`은 구식입니다.** 커넥션 풀링이 없어 매 요청마다 TCP 연결을 새로 맺습니다.
   이 프로젝트에는 이미 `RestClient`(커넥션 풀 + 타임아웃)와 `RestTemplate`이 빈으로 있습니다.
   → [global 1-4](../global.md#1-4-httpclientconfig--외부-api를-부르는-두-가지-도구)
   **세 가지 HTTP 클라이언트가 공존합니다.**

   ```java
   // RestClient로 대체 (Spring 6.1+)
   String response = restClient.get()
           .uri(apiUrl)
           .accept(MediaType.APPLICATION_JSON)
           .retrieve()
           .body(String.class);
   ```

2. **오류 응답 본문을 예외 메시지에 그대로 넣습니다.**
   응답에 민감 정보가 있으면 로그로 새어 나갑니다.

3. **`RuntimeException`을 직접 던집니다.** 이 프로젝트의 `ApplicationException` 규약 위반.

4. **`newsData` 도메인 안에 있는데 `recruitmentNotice`가 씁니다.**
   도메인 간 의존이 생겼습니다. **`global/util`로 옮기는 것이 맞습니다.**

## 3-2. `HtmlUtils` — 정규식 기반 HTML 정리

```java
public class HtmlUtils {

    public static String removeHtmlTags(String text) {
        if (text == null) return null;
        return text.replaceAll("<[^>]*>", "");                    // ⚠️ 정규식으로 HTML 파싱
    }

    public static String decodeHtmlEntities(String text) {
        if (text == null) return null;
        return text.replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#39;", "'").replace("&apos;", "'");
    }

    public static String cleanText(String text) {
        if (text == null) return null;
        String cleaned = removeHtmlTags(text);
        cleaned = decodeHtmlEntities(cleaned);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();          // 🟢 공백 정리
        return cleaned;
    }
}
```

**잘한 점**: `null` 처리가 모든 메서드에 있고, 3단계를 `cleanText` 하나로 묶었습니다.
`\\s+ → " "`로 개선 공백·탭·개행을 정리하는 것도 실용적입니다.

**문제: 정규식으로 HTML을 다루는 것은 불완전합니다.**

```java
// 태그 제거 순서 때문에 생기는 문제
"&lt;script&gt;alert(1)&lt;/script&gt;"
   → removeHtmlTags: 변화 없음 (아직 &lt;이므로 태그가 아님)
   → decodeHtmlEntities: "<script>alert(1)</script>"     ⚠️ 태그가 복원됨!
```

**엔티티를 디코딩한 뒤에 태그가 나타나므로, 순서가 거꾸로입니다.**
지금은 이 값이 그대로 JSON으로 나가고 프론트엔드(React)가 이스케이프하므로
**즉시 위험하지는 않습니다.** 하지만 `dangerouslySetInnerHTML`을 쓰는 순간 XSS가 됩니다.

**그리고 엔티티 목록이 7개뿐입니다.** `&hellip;`, `&mdash;`, `&#8217;`, `&#x27;` 등은 처리되지 않아
화면에 `&hellip;`가 그대로 보입니다.

```java
// 이 프로젝트에는 jsoup이 이미 있습니다 (build.gradle: org.jsoup:jsoup:1.17.2)
public static String cleanText(String text) {
    if (text == null) return null;
    // Jsoup.parse가 엔티티 디코딩 + 태그 제거를 정확한 순서로 처리
    return Jsoup.clean(text, Safelist.none()).replaceAll("\\s+", " ").trim();
}
```

**`jsoup`이 의존성에 있는데 여기서 쓰지 않습니다.** `newsData` 도메인은 쓰고 있습니다.

---

# 4. `RecruitmentController` — 무인증 조회

```java
@RestController
@RequestMapping("/api/recruitment")
@RequiredArgsConstructor
@Slf4j // 추가
public class RecruitmentController {

    @GetMapping("")
    public ResponseEntity<ResponseDTO<List<RecruitmentResponseDto>>> getRecruitmentData() {
        List<RecruitmentResponseDto> recruitments = recruitmentService.getRecruitmentData();
        ResponseDTO<List<RecruitmentResponseDto>> response = ResponseDTO.okWithData(recruitments);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
```

`/api/recruitment/**`는 `permitAll`입니다.
**채용공고는 공개 정보이고 `principalDetails`를 쓰지 않으므로 안전합니다.**
이 프로젝트에서 `permitAll`이 **올바르게 적용된 몇 안 되는 경우**입니다.
(`/api/news/**`, `/api/ai-news/**`도 같습니다.)

**사소한 점**

- `@Slf4j // 추가`인데 로그를 쓰지 않습니다. 불필요한 어노테이션입니다.
- `@GetMapping("")`은 `@GetMapping`으로 충분합니다.
- **페이징 파라미터가 없습니다.** [2-8](#2-8--조회에-트랜잭션페이징이-없다) 참조.

---

# 5. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | `Dotenv.load()` static 호출 → `.env` 없으면 기동 실패 | [2-1](#2-1--api-키를-dotenv로-직접-읽는다) |
| 🔴 2 | `link` 유니크 제약 없음 → 동시 실행 시 중복 저장 | [1-1](#1-1--link에-유니크-제약이-없다) |
| 🟠 3 | 스케줄러가 예외를 재던짐 → 불량 데이터 1건이 전체 수집을 영구 차단 | [2-3](#2-3--스케줄러-메서드가-예외를-다시-던진다) |
| 🟠 4 | `@Transactional` 안에서 외부 API 호출 (최대 15초 커넥션 점유) | [2-3](#문제--트랜잭션-안에서-외부-api를-호출한다) |
| 🟠 5 | API 키가 URL·예외 메시지·로그에 노출 | [2-2](#2-2--api-키가-url-쿼리스트링에-들어간다) |
| 🟠 6 | 조회에 페이징 없음 → 수만 건을 한 번에 응답 | [2-8](#2-8--조회에-트랜잭션페이징이-없다) |
| 🟠 7 | `ZoneId.systemDefault()` 의존 → TZ 설정 빠지면 날짜 9시간 어긋남 | [1-2 ②](#-zoneidsystemdefault는-환경에-따라-달라진다) |
| 🟡 8 | 타임스탬프 파싱 실패를 `null`로 삼킴 → 조회에서 영구 제외 | [1-2 ③](#-파싱-실패를-null로-삼킨다) |
| 🟡 9 | `job_mid_cd=87` 매직 넘버 + `start=0` 고정 | [2-7](#2-7--하드코딩된-수집-조건) |
| 🟡 10 | `HtmlUtils`의 태그 제거/엔티티 디코딩 순서가 거꾸로 | [3-2](#3-2-htmlutils--정규식-기반-html-정리) |
| 🟡 11 | `HttpClientUtil`이 구식(`HttpURLConnection`) + 도메인 위치 부적절 | [3-1](#3-1-httpclientutil--httpurlconnection-기반) |
| 🟡 12 | 10분 주기가 과함 (하루 144회) | [2-9](#2-9--남아-있는-흔적들) |
| 🟡 13 | 조회에 `@Transactional(readOnly = true)` 누락 + 인덱스 없음 | [2-8](#2-8--조회에-트랜잭션페이징이-없다) |
| 🟢 14 | 엔티티에 `@JsonFormat` (계층 혼합) | [1-3](#1-3--엔티티에-jsonformat이-붙어-있다) |
| 🟢 15 | 빌더로 만든 뒤 setter로 두 필드를 채움 | [1-2 ①](#-빌더로-만든-뒤에-값을-채운다--불완전한-객체가-생긴다) |
| 🟢 16 | 미사용 `countExistingByLink` + 주석 처리된 `existsByLink` | [2-6](#2-6--잘-만든-것--in-절-배치-조회로-n1-제거) |
| 🟢 17 | `@NoArgsConstructor`가 `public` | [1장](#1-recruitment-엔티티) |
| 🟢 18 | 미사용 `@Slf4j`, 자기 메모 주석("노션에 있으니 참고바람") | [4장](#4-recruitmentcontroller--무인증-조회) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **항목별 오류 격리** | 1건이 깨져도 99건은 저장. 외부 데이터 처리의 정석 |
| **`IN` 절 배치 조회** | 100회 → 1회. 주석에 "N+1 문제 해결"까지 명시 |
| **`List` → `HashSet` 변환** | `contains()` O(n) → O(1). 10,000회 비교를 100회로 |
| `Set.add()` 중복 제거 관용구 | 순서 유지 + 간결. 주석에 원리까지 설명 |
| `HttpClientUtil`의 타임아웃 명시 | 기본값 무한 대기를 막음 |
| `finally { disconnect() }` + try-with-resources | 자원 누수 방지 |
| `StandardCharsets.UTF_8` 명시 | 한글 깨짐 방지 |
| 만료일·등록일 조건 조회 | 마감된 공고와 오래된 공고를 자동 배제 |
| `zone = "Asia/Seoul"` 지정 | 스케줄 시각을 서버 타임존과 분리 |
| `permitAll`이 올바르게 적용됨 | 공개 정보 + `principalDetails` 미사용 |
| 타임스탬프 변환을 엔티티가 담당 | 서비스에서 파싱 코드를 제거 |

**핵심 평가**: 데이터 수집 파이프라인의 **성능 관련 판단(배치 조회, HashSet, 오류 격리)은
정확합니다.** 문제는 **경계 관리** — 트랜잭션 경계(외부 I/O 포함), 설정 경계(`Dotenv`),
계층 경계(`@JsonFormat`, `HttpClientUtil` 위치)입니다.

가장 먼저 할 일은 **스케줄러의 `throw`를 없애는 것**입니다.
불량 데이터 한 건에 수집 전체가 멈추는 것이 가장 큰 실질적 위험입니다.

---

## 다음 문서

- [newsData.md](newsData.md) — 같은 패턴의 뉴스 수집 (`HttpClientUtil`·`HtmlUtils`의 원래 주인)
- [ai.md](ai.md) — AI 뉴스 수집 스케줄러
- [../global.md](../global.md) — `Dotenv` 문제와 HTTP 클라이언트 설정
