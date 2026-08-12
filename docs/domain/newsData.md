# newsData — IT 뉴스 수집 (네이버 API · 인기글 선별 · 이미지 생성)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [recruitmentNotice.md](recruitmentNotice.md) · [ai.md](ai.md)
>
> 파일 14개. **네이버 뉴스 API로 IT 뉴스를 수집하고, LLM으로 인기글을 선별하고,
> 이미지가 없는 기사에 AI 이미지를 생성**하는 3단 파이프라인입니다.
>
> **`recruitmentNotice`와 같은 수집 패턴인데 여기가 더 성숙합니다.**
> 두 도메인을 나란히 읽으면 "무엇이 개선되었는지"가 보입니다.

---

## 파일 지도

```
domain/newsData/
├── controller/NewsDataController.java     GET /api/news, /api/news/popular-news
├── dto/response/NewsDataResponseDTO.java
├── entity/NewsData.java
├── event/NewsCreatedEvent.java            수집 완료 이벤트
├── exception/
│   ├── ImageApiConfigurationException.java
│   ├── ImageApiException.java
│   └── ImageGenerationException.java
├── repository/NewsDataRepository.java     ★ 벌크 업데이트 메서드가 잘 갖춰져 있음
├── service/
│   ├── NewsDataService.java                       170줄  수집 + 스케줄러
│   ├── PopularNewsDataService.java                318줄  LLM 기반 인기글 선별
│   ├── NewsImageService.java                      229줄  AI 이미지 생성
│   └── ImageGenerationCircuitBreakerService.java  271줄  서킷브레이커 래퍼
└── util/                                  ★ 다른 도메인도 쓰는 공용 유틸
    ├── HtmlUtils.java                     HTML 태그·엔티티 제거
    └── HttpClientUtil.java                HttpURLConnection GET
```

**흐름**

```
[1시간마다]  @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
     │
     ▼ NewsDataService.fetchAndSaveNews()
     │
     ├─ news.active == "false"면 즉시 종료      ← 환경별 on/off
     ├─ ① 네이버 뉴스 API 호출 (query="it", display=100, sort=sim)
     ├─ ② JSON → NewsData 리스트 (항목별 오류는 건너뜀)
     ├─ ③ 응답 내 중복 제거 (link 기준)
     ├─ ④ DB 기존 link 제외 (IN 절 배치 조회)
     ├─ ⑤ saveAll()
     └─ ⑥ eventPublisher.publishEvent(new NewsCreatedEvent(건수))
              │
              ▼ (커밋 후, 비동기)
              ├─ PopularNewsDataService  → LLM으로 중복 기사 묶고 인기글 선별
              │                             → markMultipleAsPopular / markMultipleAsInactive
              └─ NewsImageService        → 이미지 없는 기사에 AI 이미지 생성
                                            → ImageGenerationCircuitBreakerService 경유
```

---

# 1. `NewsData` 엔티티

```java
@Entity
@Table(name = "news")
@Getter
@AllArgsConstructor
@NoArgsConstructor                                   // ⚠️ public
@Builder
public class NewsData extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long newsId;

    @Column(name = "title")         private String title;
    @Column(name = "original_link") private String originalLink;    // ★ 언론사 원문
    @Column(name = "link")          private String link;             // ★ 네이버 뉴스 링크
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "pub_date")      private LocalDateTime pubDate;

    @Column(name = "is_popular", columnDefinition = "TINYINT DEFAULT 0")
    @Builder.Default private Integer isPopular = 0;                  // ⚠️ Integer 플래그

    @Column(name = "is_active", columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default private Integer isActive = 1;                   // ⚠️

    @Column(name = "image_url")     private String imageUrl;

    public void setPubDateFromString(String pubDateStr) { ... }
}
```

## 1-1. 🟢 원문 링크를 함께 저장한다

```java
@Column(name = "original_link") private String originalLink;    // 언론사 사이트
@Column(name = "link")          private String link;             // 네이버 뉴스
```

**`ai` 도메인의 `AiUpdate`/`AiNews`는 링크가 아예 없습니다.**
→ [ai.md 4장](ai.md#4-ainews-엔티티)

**뉴스 서비스에서 원문 링크는 필수입니다.**

1. 사용자가 **출처를 확인**할 수 있습니다
2. **중복 판정의 자연키**가 됩니다 (`findExistingLinks`)
3. 저작권 관점에서 **원문으로 유도**하는 것이 안전합니다

네이버 API는 `link`(네이버 뉴스)와 `originallink`(언론사)를 모두 주는데
**둘 다 저장한 것이 정확한 판단입니다.** 네이버 뉴스에 없는 기사는 `link`가 비고,
언론사 링크는 만료될 수 있으니 두 경로가 필요합니다.

## 1-2. 🟢 `pubDate` 파싱 — 타임존을 명시했다

```java
public void setPubDateFromString(String pubDateStr) {
    // "Mon, 07 Jul 2025 11:00:00 +0900" 형식 파싱
    DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
    ZonedDateTime zonedDateTime = ZonedDateTime.parse(pubDateStr, formatter);

    // 한국 시간대로 변환 후 LocalDateTime으로 변환
    this.pubDate = zonedDateTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"))
            .toLocalDateTime();
}
```

**`recruitmentNotice`보다 개선된 부분입니다.**

```java
// recruitmentNotice — 시스템 타임존에 의존 ⚠️
this.pubDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());

// newsData — 타임존을 명시 ✅
this.pubDate = zonedDateTime.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
```

→ [recruitmentNotice.md 1-2](recruitmentNotice.md#-zoneidsystemdefault는-환경에-따라-달라진다)

**`DateTimeFormatter.RFC_1123_DATE_TIME`을 쓴 것도 좋습니다.**
`"Mon, 07 Jul 2025 11:00:00 +0900"`은 RSS/HTTP 표준(RFC 1123) 형식이고,
자바 표준 라이브러리에 파서가 이미 있습니다. **직접 패턴을 쓰면 요일·월 약어 처리에서 틀립니다.**

**`withZoneSameInstant`의 의미**

```java
ZonedDateTime utc = ZonedDateTime.parse("2025-07-07T02:00:00Z");

utc.withZoneSameInstant(ZoneId.of("Asia/Seoul"))   // 2025-07-07T11:00+09:00 — 같은 순간, 다른 표기
utc.withZoneSameLocal(ZoneId.of("Asia/Seoul"))     // 2025-07-07T02:00+09:00 — 같은 표기, 다른 순간 ⚠️
```

**`SameInstant`가 정답입니다.** `SameLocal`을 쓰면 시각이 9시간 틀어집니다.

> ⚠️ 다만 `ZonedDateTime.parse`가 실패하면 `DateTimeParseException`(unchecked)이
> 그대로 던져집니다. `parseNewsResponse`의 항목별 `catch`가 잡아 그 기사만 건너뛰므로
> 실질 피해는 없지만, **`recruitmentNotice`처럼 `try/catch`로 감싸 `null`을 넣지 않는 것**은
> 오히려 더 낫습니다(조회에서 영구 제외되는 유령 데이터가 생기지 않으므로).

## 1-3. 🟠 `Integer`로 boolean을 표현한다

```java
@Column(name = "is_popular", columnDefinition = "TINYINT DEFAULT 0")
@Builder.Default private Integer isPopular = 0;

@Column(name = "is_active", columnDefinition = "TINYINT DEFAULT 1")
@Builder.Default private Integer isActive = 1;
```

**0/1을 `Integer`로 다룹니다.** 쿼리도 그렇습니다.

```java
@Query("SELECT n FROM NewsData n WHERE n.isActive = 1 ORDER BY n.pubDate DESC")
```

**문제**

1. **`2`나 `-1`이 들어갈 수 있습니다.** 타입이 막아주지 않습니다
2. `isActive = 1`이 "활성"인지 코드만 봐서는 확신할 수 없습니다
3. `Integer`(래퍼)이므로 `null`도 가능합니다 — `null`이면 조회에서 빠집니다

**Java의 `boolean`을 쓰면 Hibernate가 MySQL `BIT`/`TINYINT(1)`로 매핑합니다.**

```java
@Column(name = "is_popular", nullable = false)
@Builder.Default private boolean popular = false;

@Column(name = "is_active", nullable = false)
@Builder.Default private boolean active = true;
```

```java
@Query("SELECT n FROM NewsData n WHERE n.active = true ORDER BY n.pubDate DESC")
```

> 이 프로젝트는 boolean 표현이 **세 가지**입니다.
>
> | 방식 | 사용처 |
> |---|---|
> | `String "Y"/"N"` | `Board.deleteYn`, `Comment.deleteYn` |
> | `Integer 0/1` | `NewsData.isPopular`, `NewsData.isActive` |
> | `Boolean` | `Conversation.isActive`, `AiModel.isActive` |
>
> **하나로 통일하는 것이 맞습니다.** `boolean`(원시)이 가장 안전합니다.
> → [community.md 2-5](community.md#2-5--deleteyn을-string으로), [conversation.md 1-1](conversation.md#boolean-isactive-vs-boolean-isactive)

## 1-4. `columnDefinition = "TINYINT DEFAULT 0"`의 한계

`AiUpdate`의 `viewCount`와 같은 문제입니다.
→ [community.md 2-4](community.md#2-4--viewcount의-기본값이-자바와-db에서-다르다)

`DEFAULT 0`은 **SQL로 직접 INSERT할 때만** 적용됩니다.
JPA는 항상 자바 필드 값을 명시해 INSERT하므로 `@Builder.Default`가 실질적인 기본값입니다.
**두 곳에 기본값이 있으면 어느 것이 진짜인지 헷갈립니다.**

그리고 `TINYINT`는 MySQL 문법이라 **방언 독립성이 깨집니다.**

---

# 2. `NewsDataRepository` — 벌크 연산을 잘 갖췄다

```java
@Repository
public interface NewsDataRepository extends JpaRepository<NewsData, Long> {

    // ── 조회 ──
    @Query("SELECT n FROM NewsData n WHERE n.isActive = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findAllActiveNewsOrderByPubDateDesc();

    @Query("SELECT n FROM NewsData n WHERE n.isActive = 1 AND n.isPopular = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findAllActiveNewsAndPopularNewsOrderByPubDateDesc();

    @Query("SELECT n FROM NewsData n WHERE n.pubDate >= :startDate AND n.pubDate < :endDate " +
           "AND n.isPopular = 0 AND n.isActive = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findNewsByDateRangeExcludingPopular(...);

    // ── 벌크 업데이트 ──
    @Modifying @Transactional
    @Query("UPDATE NewsData n SET n.isPopular = 1 WHERE n.newsId IN :newsIds")
    void markMultipleAsPopular(@Param("newsIds") List<Long> newsIds);          // ★ IN 절 벌크

    @Modifying @Transactional
    @Query("UPDATE NewsData n SET n.isActive = 0 WHERE n.newsId IN :newsIds")
    void markMultipleAsInactive(@Param("newsIds") List<Long> newsIds);

    // ── 배치 페이징 ──
    @Query(value = "SELECT * FROM news WHERE (image_url IS NULL OR image_url = '') " +
                   "AND is_active = 1 ORDER BY pub_date DESC LIMIT :batchSize OFFSET :offset",
           nativeQuery = true)
    List<NewsData> findNewsWithoutImagesByBatch(@Param("batchSize") int batchSize,
                                                @Param("offset") int offset);

    @Query("SELECT n.link FROM NewsData n WHERE n.link IN :links")
    List<String> findExistingLinks(@Param("links") List<String> links);        // ★ N+1 제거
}
```

## 2-1. 🟢 `@Modifying` — 벌크 UPDATE

```java
@Modifying
@Query("UPDATE NewsData n SET n.isPopular = 1 WHERE n.newsId IN :newsIds")
void markMultipleAsPopular(@Param("newsIds") List<Long> newsIds);
```

**`@Modifying`은 "이 쿼리는 데이터를 변경한다"를 스프링에 알립니다.**
붙이지 않으면 `InvalidDataAccessApiUsageException`이 발생합니다
(Spring Data는 기본적으로 `@Query`를 SELECT로 취급).

**엔티티를 하나씩 로드해 수정하는 방식과 비교하세요.**

```java
// ① 엔티티 방식 — 100건이면 SELECT 1 + UPDATE 100
List<NewsData> list = repo.findAllById(newsIds);
list.forEach(n -> n.markAsPopular());          // 더티 체킹으로 UPDATE 100번

// ② 벌크 방식 — UPDATE 1번
repo.markMultipleAsPopular(newsIds);
// → UPDATE news SET is_popular = 1 WHERE news_id IN (1, 2, ..., 100)
```

**100배 차이입니다.** 대량 상태 변경에는 벌크가 정답입니다.

### ⚠️ 벌크 연산의 함정 — 영속성 컨텍스트를 건너뛴다

**벌크 UPDATE는 JPA를 우회해 DB에 직접 SQL을 날립니다.**
따라서 **영속성 컨텍스트(1차 캐시)에 있는 엔티티는 갱신되지 않습니다.**

```java
NewsData news = repo.findById(1L).get();
System.out.println(news.getIsPopular());        // 0

repo.markMultipleAsPopular(List.of(1L));         // DB는 1이 되었지만

System.out.println(news.getIsPopular());        // ⚠️ 여전히 0 (메모리의 낡은 값)
```

**해결책**

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE NewsData n SET n.isPopular = 1 WHERE n.newsId IN :newsIds")
void markMultipleAsPopular(@Param("newsIds") List<Long> newsIds);
```

| 옵션 | 의미 |
|---|---|
| `flushAutomatically = true` | 벌크 실행 **전에** 대기 중인 변경을 DB로 flush (순서 보장) |
| `clearAutomatically = true` | 벌크 실행 **후에** 영속성 컨텍스트를 비움 (낡은 엔티티 제거) |

**이 프로젝트는 두 옵션을 쓰지 않습니다.**
현재는 벌크 호출 후 그 엔티티를 다시 읽지 않아 문제가 드러나지 않지만,
**나중에 코드를 추가하면 조용히 틀린 값을 보게 됩니다.**

### ⚠️ `@Transactional`이 리포지토리에 붙어 있다

```java
@Modifying
@Transactional                              // ⚠️ 리포지토리에 트랜잭션
@Query("UPDATE NewsData n SET ...")
void markMultipleAsPopular(...);
```

`@Modifying` 쿼리는 트랜잭션이 필요하므로 붙인 것으로 보입니다. **동작은 합니다.**

**하지만 트랜잭션 경계는 서비스가 결정해야 합니다.**

```
[문제] 서비스에서 "인기글 표시 + 비활성화"를 원자적으로 하려는데
       각 메서드가 자기 트랜잭션을 가지면 원자성이 깨질 수 있습니다.
```

정확히는 `Propagation.REQUIRED`(기본)이므로 **서비스 트랜잭션에 참여**합니다.
서비스가 `@Transactional`이면 문제없고, 없으면 각각 별도 트랜잭션이 됩니다.

**서비스에 `@Transactional`을 붙이고 리포지토리에서는 떼는 것이 정석입니다.**
지금은 "서비스에 트랜잭션이 없어도 동작하게" 하려는 편법으로 보입니다.

## 2-2. 🟢 배치 페이징 — 이미지 없는 기사 처리

```java
@Query(value = "SELECT * FROM news WHERE (image_url IS NULL OR image_url = '') " +
               "AND is_active = 1 ORDER BY pub_date DESC LIMIT :batchSize OFFSET :offset",
       nativeQuery = true)
List<NewsData> findNewsWithoutImagesByBatch(@Param("batchSize") int batchSize,
                                            @Param("offset") int offset);

@Query("SELECT COUNT(n) FROM NewsData n WHERE (n.imageUrl IS NULL OR n.imageUrl = '') AND n.isActive = 1")
Long countNewsWithoutImages();
```

**"전체를 한 번에"가 아니라 "배치 크기만큼 나눠" 처리합니다.**

AI 이미지 생성은 **건당 수 초 + 비용**이 드는 작업입니다.
1,000건을 한 번에 시도하면 메모리와 시간, 비용이 모두 폭발합니다.

`countNewsWithoutImages()`로 **전체 규모를 먼저 파악**하고
배치 단위로 진행하는 구조는 대량 처리의 정석입니다.

**`nativeQuery = true`란**: JPQL이 아니라 **DB SQL을 그대로** 실행합니다.

| | JPQL (`nativeQuery = false`) | 네이티브 SQL (`true`) |
|---|---|---|
| 대상 | 엔티티 (`NewsData n`) | 테이블 (`news`) |
| 방언 독립성 | ✅ Hibernate가 변환 | ❌ MySQL 문법에 묶임 |
| DB 고유 기능 | 제한적 | 전부 사용 가능 |

여기서는 `LIMIT/OFFSET` 때문에 네이티브를 썼습니다.
**하지만 `Pageable`을 쓰면 JPQL로도 가능합니다.**

```java
@Query("SELECT n FROM NewsData n WHERE (n.imageUrl IS NULL OR n.imageUrl = '') " +
       "AND n.isActive = 1 ORDER BY n.pubDate DESC")
List<NewsData> findNewsWithoutImages(Pageable pageable);
// 호출: findNewsWithoutImages(PageRequest.of(page, batchSize))
```

→ [point.md 2-2](point.md#2-2-query를-쓴-이유--jpql의-limit)

> ⚠️ **`OFFSET` 페이징의 함정**: 이미지를 생성해 `image_url`을 채우면
> **다음 배치의 `OFFSET`이 어긋납니다.**
>
> ```
> 배치 1: OFFSET 0,  LIMIT 10 → 10건 처리 → image_url 채워짐 → 조건에서 빠짐
> 배치 2: OFFSET 10, LIMIT 10 → 이제 앞 10건이 없으므로 11~20번째가 아니라 21~30번째를 가져옴!
>                              → 11~20번째는 영구히 건너뜀
> ```
>
> **처리하면서 조건이 바뀌는 경우 `OFFSET`을 쓰면 안 됩니다.**
> **항상 `OFFSET 0`으로 반복하거나** 커서(마지막 처리 ID) 방식을 써야 합니다.
>
> ```java
> // 항상 처음부터 — 처리된 건은 조건에서 빠지므로 자연히 다음 것이 나온다
> while (true) {
>     List<NewsData> batch = repo.findNewsWithoutImages(PageRequest.of(0, 10));
>     if (batch.isEmpty()) break;
>     batch.forEach(this::generateImage);
> }
> ```

---

# 3. `NewsDataService` — `recruitmentNotice`의 개선판

## 3-1. 🟢 스케줄러가 예외를 삼킨다 — 그리고 그 이유가 주석에 있다

```java
@Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")     // 1시간마다
@Transactional
public void fetchAndSaveNews() {
    if (newsActive.equals("false")) return;

    try {
        ...
        newsRepository.saveAll(newsDataList);
        eventPublisher.publishEvent(new NewsCreatedEvent(newsDataList.size()));
    } catch (Exception e) {
        log.error("뉴스 데이터 처리 중 오류", e);
        // RuntimeException을 던지지 않음 - 스케줄러가 계속 동작함
//        throw new RuntimeException("뉴스 데이터 처리 실패", e);
    }
}
```

**`recruitmentNotice`와 정확히 반대입니다.**

```java
// RecruitmentService — 예외를 다시 던진다 ⚠️
} catch (Exception e) {
    log.error("채용정보 데이터 처리 중 오류", e);
    throw new RuntimeException("채용정보 데이터 처리 실패", e);
}
```

**주석 처리된 `throw`가 남아 있고, 왜 지웠는지 이유가 적혀 있습니다.**

> `// RuntimeException을 던지지 않음 - 스케줄러가 계속 동작함`

**실제로 문제를 겪고 고친 흔적입니다.** 스케줄러에서는 이것이 정답입니다.
→ [recruitmentNotice.md 2-3](recruitmentNotice.md#2-3--스케줄러-메서드가-예외를-다시-던진다)

> **주석 처리된 코드는 지우는 것이 좋습니다.** Git이 이력을 갖고 있습니다.
> 다만 **"왜 던지지 않는가"라는 설명 주석은 남길 가치가 있습니다.**

## 3-2. 🟢 `news.active` 스위치 — 환경별 on/off

```java
@Value("${news.active}")
private String newsActive;

public void fetchAndSaveNews() {
    if (newsActive.equals("false")) return;
```

```yaml
# application-dev.yml
news:
  active: false        # 로컬에서는 수집하지 않음

# application-prod.yml
news:
  active: true
```

**로컬 개발 중 네이버 API 호출 한도를 소진하지 않게 하는 장치입니다.**
외부 API를 쓰는 스케줄러에는 이런 스위치가 반드시 필요합니다.

**개선점 2가지**

```java
// ① String 비교 → boolean
@Value("${news.active:false}")
private boolean newsActive;                        // 스프링이 자동 변환

if (!newsActive) return;
```

`newsActive.equals("false")`는 **`newsActive`가 `null`이면 NPE**입니다.
`@Value`에 기본값(`:false`)이 없으므로 설정을 빠뜨리면 기동 시점에 실패합니다.
그리고 `"FALSE"`, `"no"`, `" false"`는 `false`로 판정되지 않습니다.

```java
// ② @ConditionalOnProperty로 빈 자체를 끄기 (더 강력)
@ConditionalOnProperty(name = "news.active", havingValue = "true")
@Component
public class NewsCollectScheduler { ... }
```

`aibot`이 이 방식을 씁니다. → [aibot.md 5-1](aibot.md#5-1--conditionalonproperty--조건부-빈-등록)

## 3-3. 🟢 이벤트로 후속 작업 분리

```java
newsRepository.saveAll(newsDataList);
log.info("뉴스 데이터 {}건 저장 완료", newsDataList.size());

// 이벤트 발행 (트랜잭션 커밋 후 처리됨)
eventPublisher.publishEvent(new NewsCreatedEvent(newsDataList.size()));
log.info("비동기 작업들 시작됨 - 중복 분석 & 이미지 생성");
```

**핵심 작업(뉴스 저장)과 부가 작업(인기글 선별, 이미지 생성)을 분리했습니다.**

```
저장 트랜잭션         (동기, 짧음)  ─── 실패하면 아무것도 안 됨
    ↓ 커밋
인기글 선별 (LLM)     (비동기, 김)  ─── 실패해도 뉴스는 남음
이미지 생성 (AI)      (비동기, 김)  ─── 실패해도 뉴스는 남음
```

`community` 도메인의 검열 이벤트와 같은 패턴입니다.
→ [community.md 1장](community.md#1-전체-흐름--게시글-하나가-등록되면)

**주석에 "트랜잭션 커밋 후 처리됨"이라고 적혀 있습니다.**
`@TransactionalEventListener(AFTER_COMMIT)`을 전제한 표현입니다.

> ⚠️ **리스너가 실제로 `AFTER_COMMIT`인지 확인이 필요합니다.**
> 그냥 `@EventListener`면 **같은 트랜잭션 안에서 동기로 실행**되어
> "비동기 작업들 시작됨" 로그가 거짓이 됩니다.
> 그러면 트랜잭션이 LLM 호출 시간만큼 길어집니다
> ([aibot.md 3장](aibot.md#3--transactional-안에서-llm을-호출한다)과 같은 문제).
>
> `community` 도메인은 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`을
> 명시적으로 씁니다. **같은 방식이어야 합니다.**

**`NewsCreatedEvent(건수)`가 건수만 담는 것**도 짚어둘 만합니다.
리스너가 "무엇이 새로 들어왔는지" 모르므로 **전체를 다시 조회**해야 합니다.
저장된 ID 목록을 담으면 리스너가 정확히 그것만 처리할 수 있습니다.

```java
public record NewsCreatedEvent(List<Long> newsIds) { }
```

## 3-4. 🟠 `@Transactional` 안에서 외부 API를 호출한다

```java
@Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
@Transactional                                      // ★ 트랜잭션 시작
public void fetchAndSaveNews() {
    ...
    String response = getNewsApiResponse();          // ★ 네이버 API 호출 (최대 15초)
    List<NewsData> newsDataList = parseNewsResponse(response);
    ...
    newsRepository.saveAll(newsDataList);
}
```

`recruitmentNotice`와 **같은 문제**입니다.
→ [recruitmentNotice.md 2-3](recruitmentNotice.md#문제--트랜잭션-안에서-외부-api를-호출한다)

`HttpClientUtil`의 타임아웃이 연결 5초 + 읽기 10초이므로 **최대 15초간 커넥션 점유**입니다.

**다행히 `HttpClientUtil`에 타임아웃이 있어** `llm`/`ai` 도메인보다는 안전합니다.
→ [llm.md 3장](llm.md#3--타임아웃-없는-resttemplate), [ai.md 3-3](ai.md#3-3--httpclient를-호출마다-새로-만든다)

```java
// 트랜잭션을 저장 단계에만
public void fetchAndSaveNews() {
    if (!newsActive) return;
    try {
        String response = getNewsApiResponse();                // 트랜잭션 밖 ✅
        List<NewsData> parsed = removeDuplicates(parseNewsResponse(response));
        newsSaveService.saveNew(parsed);                        // 별도 빈의 @Transactional
    } catch (Exception e) {
        log.error("뉴스 수집 실패 - 다음 주기에 재시도", e);
    }
}
```

## 3-5. 🟠 `Dotenv.load()` + 필드 주입 혼용

```java
private static Dotenv dotenv = Dotenv.load();          // ⚠️ static

@Value("${news.active}")                                // ✅ Spring
private String newsActive;

private String clientId = dotenv.get("X_Naver_Client_Id");        // ⚠️ Dotenv
private String clientSecret = dotenv.get("X_Naver_Client_Secret");

@Autowired                                              // ⚠️ 필드 주입
private ApplicationEventPublisher eventPublisher;
```

**세 가지 방식이 한 클래스에 섞여 있습니다.**

| 값 | 방식 | 평가 |
|---|---|---|
| `news.active` | `@Value` | ✅ |
| 네이버 API 키 | `Dotenv.load()` | ⚠️ [global 1-7](../global.md#1-7-공통-문제--dotenvload를-static-필드에서-호출) |
| `eventPublisher` | `@Autowired` 필드 주입 | ⚠️ |

**`@RequiredArgsConstructor`가 이미 붙어 있으므로 `final` 필드로 선언만 하면 생성자 주입됩니다.**

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsDataService {

    private final PopularNewsDataService popularNewsDataService;
    private final NewsImageService newsImageService;
    private final HttpClientUtil httpClient;
    private final NewsDataRepository newsRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;      // ★ final로 바꾸면 자동 주입
}
```

**필드 주입(`@Autowired`)의 문제**
→ [기초개념 4-3](../00-기초개념.md#4-3-의존성-주입di--왜-생성자-주입인가)

`final`을 쓸 수 없고, 누락을 기동 시점에 잡을 수 없고, 테스트에서 리플렉션이 필요합니다.

**API 키는 생성자 파라미터로 받으면 됩니다.**

```java
public NewsDataService(..., @Value("${X_Naver_Client_Id}") String clientId,
                            @Value("${X_Naver_Client_Secret}") String clientSecret) { ... }
```

## 3-6. 🟠 검색 조건이 하드코딩되어 있다

```java
private String getNewsApiResponse() throws Exception {
    String encodedQuery = URLEncoder.encode("it", StandardCharsets.UTF_8);      // ★ "it" 고정
    String apiUrl = buildApiUrl(encodedQuery, DEFAULT_DISPLAY_COUNT, 1, "sim");  // ★ start=1, sort=sim
    ...
}
```

**세 가지가 고정되어 있습니다.**

| 값 | 의미 | 문제 |
|---|---|---|
| `query = "it"` | 검색어 | "AI", "개발자", "클라우드" 등을 추가할 수 없음 |
| `start = 1` | 시작 위치 | 첫 페이지 100건만 수집 |
| `sort = "sim"` | 정렬 | **유사도순** — 최신 뉴스를 놓칠 수 있음 |

**`sort = "sim"`이 특히 문제입니다.**

네이버 뉴스 API의 `sort` 옵션:
- `sim` — 정확도(유사도) 순
- `date` — **날짜 순 (최신)**

**뉴스 수집은 "최신"이 목적이므로 `date`가 맞습니다.**
`sim`은 매 시간 **거의 같은 결과**를 반환할 가능성이 높습니다
(유사도는 시간이 지나도 크게 바뀌지 않으므로).

그러면 `filterExistingNews`가 대부분을 걸러내고 **새 기사가 거의 없는 상태**가 됩니다.

```java
// 설정으로 빼고 date 정렬로
@Value("${news.query:it}")           private String query;
@Value("${news.sort:date}")          private String sort;
@Value("${news.display:100}")        private int display;
```

`URLEncoder.encode(..., StandardCharsets.UTF_8)`은 **잘한 부분입니다.**
한글 검색어를 넣으려면 반드시 필요하고, 인코딩을 명시했습니다.

## 3-7. 🟢 `recruitmentNotice`와 공통된 좋은 패턴

```java
// 항목별 오류 격리
for (JsonNode item : items) {
    try {
        newsDataList.add(createNewsDataFromJson(item));
    } catch (Exception e) {
        log.warn("뉴스 아이템 파싱 중 오류 - 해당 아이템 건너뜀: {}", e.getMessage());
    }
}

// Set.add() 중복 제거
Set<String> seen = new HashSet<>();
return newsDataList.stream()
        .filter(news -> seen.add(news.getLink()))
        .collect(Collectors.toList());

// IN 절 배치 조회 (N+1 제거)
List<String> existingLinks = newsRepository.findExistingLinks(links);
Set<String> existingLinkSet = new HashSet<>(existingLinks);
return newsList.stream().filter(n -> !existingLinkSet.contains(n.getLink())).toList();
```

**세 패턴 모두 `recruitmentNotice`와 동일합니다.**
→ [recruitmentNotice.md 2-4~2-6](recruitmentNotice.md#2-4--잘-만든-것--항목별-오류-격리)

**두 도메인이 같은 코드를 복사해 쓰고 있습니다.**
공용 유틸(`HtmlUtils`, `HttpClientUtil`)이 `newsData`에 있는 것을 보면
**`newsData`가 먼저 만들어지고 `recruitmentNotice`가 그것을 복사**한 것으로 보입니다.

```java
// 공통 수집 골격을 추출할 수 있습니다
public abstract class AbstractLinkBasedCollector<T> {

    protected abstract List<T> fetch() throws Exception;
    protected abstract String linkOf(T item);
    protected abstract List<String> findExistingLinks(List<String> links);
    protected abstract void saveAll(List<T> items);

    public final void collect() {
        try {
            List<T> fetched = fetch();
            List<T> deduped = removeDuplicates(fetched);        // 공통
            List<T> fresh = filterExisting(deduped);            // 공통
            if (!fresh.isEmpty()) saveAll(fresh);
        } catch (Exception e) {
            log.error("[{}] 수집 실패 - 다음 주기에 재시도", getClass().getSimpleName(), e);
        }
    }
}
```

> **다만 지금 규모(수집기 2개)에서는 추상화 비용이 이득보다 클 수 있습니다.**
> 세 번째 수집기가 생기면 그때 추출하는 것이 실용적입니다("삼진 규칙").

## 3-8. 🟠 조회에 페이징이 없다

```java
public List<NewsDataResponseDTO> getNewsData() {
    List<NewsData> newsData = newsRepository.findAllActiveNewsOrderByPubDateDesc();   // ★ 전체
    return newsData.stream().map(NewsDataResponseDTO::fromEntity).collect(Collectors.toList());
}
```

**`isActive = 1`인 모든 뉴스를 한 번에 반환합니다.**

1시간마다 최대 100건이 들어오면 **하루 2,400건, 한 달 72,000건**입니다.
`description`이 `TEXT`이므로 응답 크기가 수십 MB가 될 수 있습니다.

`ai` 도메인의 `getAIUpdatesNewsList`는 `Pageable`을 받는데
**여기는 받지 않습니다.**

```java
@Transactional(readOnly = true)
public Page<NewsDataResponseDTO> getNewsData(Pageable pageable) {
    return newsRepository.findActiveNews(pageable).map(NewsDataResponseDTO::fromEntity);
}
```

**그리고 `@Transactional(readOnly = true)`가 없습니다.**
`NewsData`에 연관관계가 없어 `LazyInitializationException` 위험은 없지만 관례상 붙여야 합니다.

**인덱스도 필요합니다.**

```sql
CREATE INDEX idx_news_active_pubdate ON news (is_active, pub_date DESC);
CREATE INDEX idx_news_link ON news (link(191));       -- findExistingLinks용
```

`link`에 인덱스가 없으면 `WHERE link IN (...)` 100건이 **전체 스캔**입니다.
수집이 1시간마다 돌므로 이 쿼리는 계속 실행됩니다.

## 3-9. 🟠 `link`에 유니크 제약이 없다

`recruitmentNotice`와 같은 문제입니다.
→ [recruitmentNotice.md 1-1](recruitmentNotice.md#1-1--link에-유니크-제약이-없다)

애플리케이션 코드로만 중복을 막고 있어, **스케줄러가 겹치면 중복 저장**이 가능합니다.

`link`가 URL이라 길이 제한(utf8mb4 인덱스 191자) 문제가 있으므로
**해시 컬럼을 두는 방식**이 안전합니다.

---

# 4. 나머지 서비스 3개 — 구조만

읽을 분량이 커서 구조와 역할만 정리합니다. 상세 분석은 코드를 직접 보며 확인하세요.

## 4-1. `PopularNewsDataService` (318줄)

```java
// 리포지토리 메서드로 역할을 추정할 수 있습니다
findNewsByDateRangeExcludingPopular(startDate, endDate)   // 날짜 범위 + 인기글 아닌 것
markMultipleAsPopular(newsIds)                            // 인기글로 표시
markMultipleAsInactive(newsIds)                           // 비활성화 (중복 기사 숨김)
```

**추정 동작**: 같은 사건을 다룬 기사들을 LLM으로 묶고,
**대표 기사 하나를 인기글로 승격**하고 나머지를 **비활성화**합니다.

이것은 **좋은 아이디어입니다.** 네이버 뉴스 검색은 같은 사건에 대해
언론사별로 거의 동일한 기사를 수십 건 반환하므로, 그대로 보여주면 목록이 지저분해집니다.

**확인해볼 지점**

- LLM 호출이 `@Transactional` 안에 있는가?
- 벌크 업데이트 후 `clearAutomatically`를 처리하는가? ([2-1](#2-1--modifying--벌크-update))
- 한 번에 처리하는 기사 수에 상한이 있는가?

## 4-2. `NewsImageService` (229줄) + `ImageGenerationCircuitBreakerService` (271줄)

```java
findNewsWithoutImagesByBatch(batchSize, offset)       // 이미지 없는 기사 배치 조회
countNewsWithoutImages()                              // 전체 규모 파악
updateImageUrl(newsId, imageUrl)                      // 이미지 URL 저장
```

**서킷브레이커를 별도 서비스로 분리한 것이 좋습니다.**

```yaml
resilience4j:
  circuitbreaker.instances.imagen-api:
    failure-rate-threshold: 70
    wait-duration-in-open-state: 60s
    sliding-window-size: 20
    minimum-number-of-calls: 5
    permitted-number-of-calls-in-half-open-state: 3
  retry.instances.imagen-api:
    max-attempts: 2
    wait-duration: 1s
    exponential-backoff-multiplier: 1.5
    retry-exceptions:
      - java.net.SocketTimeoutException
      - java.io.IOException
      - org.springframework.web.client.ResourceAccessException
```

**설정이 검열용(`text-moderation`)과 다르게 조정되어 있습니다.**

| | `text-moderation` | `imagen-api` |
|---|---|---|
| 실패율 임계값 | 60% | **70%** (이미지는 실패가 잦다고 판단) |
| OPEN 유지 | 30s | **60s** |
| 윈도우 크기 | 10 | **20** |
| 재시도 | 3회, 2s 배수 2 | **2회, 1s 배수 1.5** (비용이 크므로 적게) |
| 재시도 예외 | IOException, TimeoutException | + `ResourceAccessException` |

**작업 성격에 맞게 인스턴스별로 조정한 것은 실무 수준입니다.**
→ [community.md 6-4](community.md#6-4-resilience4j-어노테이션-3종의-적용-순서)

**전용 예외 3개를 만든 것도 좋습니다.**

```java
ImageApiConfigurationException     // 설정 오류 (API 키 없음 등) → 재시도 무의미
ImageApiException                   // API 호출 실패 → 재시도 가능
ImageGenerationException            // 생성 실패 → 재시도 가능
```

**"재시도해도 소용없는 실패"와 "재시도할 만한 실패"를 타입으로 구분**한 것이
서킷브레이커·재시도 설정과 잘 맞물립니다. `ErrorCode`에도 대응 항목이 있습니다.

```java
IMAGE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 생성에 실패했습니다."),
IMAGE_API_ERROR(HttpStatus.BAD_GATEWAY, "이미지 생성 API 호출에 실패했습니다."),
IMAGE_API_CONFIGURATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 생성 API 설정이 올바르지 않습니다."),
```

`BAD_GATEWAY(502)`를 쓴 것도 정확합니다 — **외부 서비스 실패**를 뜻하는 상태 코드입니다.

## 4-3. `util/` — 다른 도메인이 쓰는 공용 유틸

```java
newsData/util/HtmlUtils.java         ← recruitmentNotice도 사용
newsData/util/HttpClientUtil.java    ← recruitmentNotice도 사용
```

**도메인 폴더 안에 있는데 다른 도메인이 씁니다.**
→ [recruitmentNotice.md 3장](recruitmentNotice.md#3-공용-유틸--httpclientutil--htmlutils)에서 상세 분석

**`global/util/`로 옮기는 것이 맞습니다.** 그러면 의존 방향이 명확해집니다.

```
[현재]  recruitmentNotice ──▶ newsData/util        (도메인 간 의존)
[개선]  recruitmentNotice ──▶ global/util          (공통 계층 의존)
        newsData          ──▶ global/util
```

`HtmlUtils`의 태그 제거/엔티티 디코딩 순서 문제도 그 문서에 있습니다.

---

# 5. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🟠 1 | `@Transactional` 안에서 네이버 API 호출 (최대 15초) | [3-4](#3-4--transactional-안에서-외부-api를-호출한다) |
| 🟠 2 | 조회에 페이징 없음 → 수만 건을 한 번에 응답 | [3-8](#3-8--조회에-페이징이-없다) |
| 🟠 3 | `link` 유니크 제약·인덱스 없음 | [3-9](#3-9--link에-유니크-제약이-없다), [3-8](#3-8--조회에-페이징이-없다) |
| 🟠 4 | `sort = "sim"` → 최신 뉴스를 놓침. 검색어·정렬 하드코딩 | [3-6](#3-6--검색-조건이-하드코딩되어-있다) |
| 🟠 5 | 벌크 UPDATE에 `clearAutomatically` 미설정 → 낡은 엔티티 위험 | [2-1](#️-벌크-연산의-함정--영속성-컨텍스트를-건너뛴다) |
| 🟠 6 | `OFFSET` 배치 페이징 — 처리하면서 조건이 바뀌어 건너뜀 발생 | [2-2](#2-2--배치-페이징--이미지-없는-기사-처리) |
| 🟠 7 | `Dotenv` + `@Value` + `@Autowired` 필드 주입 혼용 | [3-5](#3-5--dotenvload--필드-주입-혼용) |
| 🟡 8 | `newsActive.equals("false")` — `null`이면 NPE, 대소문자 미대응 | [3-2](#3-2--newsactive-스위치--환경별-onoff) |
| 🟡 9 | `Integer` 0/1로 boolean 표현 | [1-3](#1-3--integer로-boolean을-표현한다) |
| 🟡 10 | 리포지토리에 `@Transactional` (경계는 서비스가 결정해야) | [2-1](#️-transactional이-리포지토리에-붙어-있다) |
| 🟡 11 | `NewsCreatedEvent`가 건수만 담아 리스너가 전체 재조회 | [3-3](#3-3--이벤트로-후속-작업-분리) |
| 🟡 12 | 조회에 `@Transactional(readOnly = true)` 누락 | [3-8](#3-8--조회에-페이징이-없다) |
| 🟡 13 | 공용 유틸이 도메인 폴더 안에 있음 | [4-3](#4-3-util--다른-도메인이-쓰는-공용-유틸) |
| 🟢 14 | `columnDefinition = "TINYINT DEFAULT"` — 방언 종속 + 기본값 이중화 | [1-4](#1-4-columndefinition--tinyint-default-0의-한계) |
| 🟢 15 | `nativeQuery` → `Pageable`로 대체 가능 | [2-2](#2-2--배치-페이징--이미지-없는-기사-처리) |
| 🟢 16 | 주석 처리된 `throw` 코드 잔존 | [3-1](#3-1--스케줄러가-예외를-삼킨다--그리고-그-이유가-주석에-있다) |
| 🟢 17 | `@NoArgsConstructor`가 `public` | [1장](#1-newsdata-엔티티) |
| 🟢 18 | 미사용 `@Slf4j` (컨트롤러) + "test를 위한 주석 수정" 잔존 | [3장](#3-newsdataservice--recruitmentnotice의-개선판) |

## 확인이 필요한 것 (이 문서에서 다루지 않은 3개 서비스)

| 항목 | 확인 방법 |
|---|---|
| `NewsCreatedEvent` 리스너가 `@TransactionalEventListener(AFTER_COMMIT)`인가 | 리스너 클래스의 어노테이션 확인 |
| `PopularNewsDataService`의 LLM 호출이 트랜잭션 안인가 | `@Transactional` 위치 확인 |
| 인기글 선별 대상 기사 수에 상한이 있는가 | `findNewsByDateRangeExcludingPopular` 호출부 |
| 이미지 생성 배치 루프가 `OFFSET`을 증가시키는가 | `NewsImageService`의 루프 |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **스케줄러가 예외를 삼킴 + 이유를 주석에 기록** | `recruitmentNotice`의 문제를 여기서는 해결. 실제 경험의 흔적 |
| **`news.active` 환경별 스위치** | 로컬에서 외부 API 한도를 소진하지 않음 |
| **이벤트로 핵심/부가 작업 분리** | 뉴스 저장은 동기, LLM·이미지는 비동기 |
| **`ZoneId.of("Asia/Seoul")` 명시** | `recruitmentNotice`의 `systemDefault()`보다 개선 |
| `DateTimeFormatter.RFC_1123_DATE_TIME` | 표준 파서 사용. 직접 패턴을 쓰면 요일·월 약어에서 틀림 |
| `withZoneSameInstant` | `SameLocal`과 혼동하지 않음 (9시간 오차 방지) |
| **`originalLink` + `link` 둘 다 저장** | 출처 추적 + 중복 판정 자연키 |
| **`@Modifying` 벌크 UPDATE** | 100건 UPDATE를 1쿼리로 |
| **배치 페이징 + 전체 개수 조회** | 대량 AI 이미지 생성을 나눠 처리 |
| **서킷브레이커를 별도 서비스로 분리** | 관심사 분리. 설정도 작업 성격별로 조정 |
| **이미지 예외 3종 분리** | "재시도 무의미"와 "재시도 가능"을 타입으로 구분 |
| `BAD_GATEWAY(502)` 사용 | 외부 서비스 실패에 맞는 상태 코드 |
| 항목별 오류 격리 | 1건이 깨져도 나머지는 저장 |
| `Set.add()` 중복 제거 + `IN` 절 배치 조회 | `recruitmentNotice`와 공통된 좋은 패턴 |
| `URLEncoder.encode(..., UTF_8)` | 인코딩 명시. 한글 검색어 대비 |
| 인기글 선별로 중복 기사 정리 | 네이버 검색의 실제 문제(같은 사건 수십 건)를 해결 |

**핵심 평가**: **`recruitmentNotice`의 개선판이고, 실제로 여러 지점이 나아졌습니다.**

- 스케줄러 예외 처리 ✅ (recruitmentNotice는 재던짐)
- 타임존 명시 ✅ (recruitmentNotice는 systemDefault)
- 환경별 on/off 스위치 ✅ (recruitmentNotice에는 없음)
- 이벤트로 후속 작업 분리 ✅
- 벌크 업데이트·배치 페이징 ✅
- 서킷브레이커 + 예외 타입 분리 ✅

**같은 문제도 공유합니다** — 트랜잭션 안의 외부 API 호출, `link` 유니크 제약 없음,
페이징 없음, `Dotenv` static.

**두 도메인을 나란히 읽는 것이 이 프로젝트를 이해하는 좋은 방법입니다.**
같은 문제를 두 번 풀면서 무엇을 배웠는지가 코드에 남아 있습니다.

---

## 다음 문서

- [recruitmentNotice.md](recruitmentNotice.md) — 같은 수집 패턴의 초기 버전. 공용 유틸 상세 분석
- [ai.md](ai.md) — 세 번째 뉴스 엔티티(`AiUpdate`, `AiNews`)가 있는 도메인
- [community.md](community.md) — 이벤트 기반 분리와 서킷브레이커의 다른 사례
