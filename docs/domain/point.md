# point — 포인트 (트랜잭션·동시성 교과서)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) (특히 [4-4 프록시](../00-기초개념.md#4-4-프록시--spring-마법의-정체), [6장 JPA](../00-기초개념.md#6-jpa--이-문서의-심장), [7장 트랜잭션](../00-기초개념.md#7-트랜잭션--transactional의-진짜-동작))
>
> **이 도메인 하나만 제대로 이해하면 Spring + JPA의 어려운 부분 절반을 가져갑니다.**
> 설계 아이디어는 훌륭한데 구현에 치명적인 실수가 있어서, 배울 것이 양쪽으로 다 있습니다.

---

## 파일 지도

```
domain/point/
├── controller/PointController.java
├── dto/
│   ├── request/PointRequestDto.java
│   └── response/PointResponseDto.java, CurrentPointResponseDto.java
├── entity/
│   ├── Point.java          포인트 거래 원장 (한 줄 = 한 거래)
│   └── PointType.java      거래 종류 enum
├── exception/
│   ├── InsufficientFundsException.java
│   └── PointCreationRetryExhaustedException.java
├── repository/PointRepository.java
└── service/PointService.java      ★ 이 파일이 핵심
```

**누가 포인트를 건드리나** — 거의 모든 도메인이 이 서비스를 호출합니다.

```
BoardService.createBoard        →  +10  (BOARD)
CommentService.createComment    →  +?   (COMMENT)
AttendanceService               →  +100 (ATTENDANCE)
ModelFeedbackService            →  +10  (FEEDBACK)
Gemini/Openai/ClaudeQuestionSvc →   -5  (QUESTION)
InterviewService                →   -?  (INTERVIEW)
PaymentService                  →  +?   (SPONSORSHIP)
                        ↓
                   PointService.createPoint()
                        ↓
              Point 테이블에 새 행 + Member.points 갱신 + 티어 갱신
```

**즉 `PointService`가 깨지면 프로젝트 전체가 흔들립니다.** 그리고 지금 깨져 있습니다.

---

# 1. 설계 아이디어 — 통장 원장(Ledger) 방식

## 1-1. 잔액을 덮어쓰지 않는다

가장 단순한 포인트 설계는 이렇습니다:

```sql
-- ❌ 단순한 방식
UPDATE member SET points = points + 10 WHERE member_id = 1;
```

문제: **"왜 10점이 늘었는지" 기록이 없습니다.** 사용자가 "내 포인트 왜 줄었어요?"라고 물으면
답할 방법이 없고, 버그로 포인트가 어긋나도 복구할 수 없습니다.

이 프로젝트는 **거래마다 새 행을 추가**합니다.

```java
@Entity
@Table(name = "points", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"member_id", "version"})    // ★ 핵심
})
public class Point extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_id")
    private Long pointId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private Integer amount;            // 이번 거래 금액 (+10 또는 -5)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointType type;            // 왜 발생했는지

    @Column(name = "deposit_sum", nullable = false)
    private Integer depositSum;        // 이 시점까지의 누적 적립 (항상 양수)

    @Column(name = "withdraw_sum", nullable = false)
    private Integer withdrawSum;       // 이 시점까지의 누적 사용 (항상 음수)

    @Column(nullable = false)
    private LocalDateTime date;

    @Column(nullable = false)
    private Integer version;           // 회원별 거래 순번 (1, 2, 3, ...)
}
```

## 1-2. 실제 데이터로 보기

회원 1번이 게시글 작성(+10) → 질문 사용(-5) → 출석(+100) 순으로 활동하면:

| point_id | member_id | version | amount | type | deposit_sum | withdraw_sum | 잔액 계산 |
|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | +10 | BOARD | 10 | 0 | 10 + 0 = **10** |
| 2 | 1 | 2 | −5 | QUESTION | 10 | −5 | 10 + (−5) = **5** |
| 3 | 1 | 3 | +100 | ATTENDANCE | 110 | −5 | 110 + (−5) = **105** |

**현재 잔액 = 가장 최근 행의 `depositSum + withdrawSum`**

```java
// PointService.getCurrentPoint
return CurrentPointResponseDto.fromEntity(point, point.getDepositSum() + point.getWithdrawSum());
```

`withdrawSum`이 음수로 저장되므로 **더하기**로 잔액이 나옵니다.

```java
// 누적 합계 갱신 로직 — 부호로 어느 쪽에 더할지 결정
Integer newDepositSum  = amount > 0 ? currentDepositSum + amount : currentDepositSum;
Integer newWithdrawSum = amount < 0 ? currentWithdrawSum + amount : currentWithdrawSum;
```

## 1-3. 왜 누적 합계를 매 행에 저장하는가

`SUM(amount)`로 계산할 수도 있는데, 왜 중복 저장할까요?

```sql
-- 계산 방식: 행이 10만 개면 10만 행을 스캔
SELECT SUM(amount) FROM points WHERE member_id = 1;

-- 스냅샷 방식: 최신 1행만 읽으면 끝
SELECT * FROM points WHERE member_id = 1 ORDER BY version DESC LIMIT 1;
```

이것을 **러닝 밸런스(running balance)** 라고 하며, 실제 금융 시스템의 원장 설계와 같습니다.
**좋은 설계입니다.**

부수 효과로 **감사(audit)가 가능**해집니다.
`version = 5`인 행의 `depositSum`이 이전 행 + `amount`와 맞지 않으면 데이터가 손상된 것입니다.

## 1-4. `@UniqueConstraint(member_id, version)` — 동시성 방어선

이 제약이 이 설계의 핵심 안전장치입니다.

```java
@Table(name = "points", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"member_id", "version"})
})
```

**왜 필요한가** — 동시 요청 시나리오를 보세요.

```
       요청 A (게시글 +10)              요청 B (출석 +100)
t1  최신 version 조회 → 3
t2                                  최신 version 조회 → 3      ← 같은 값을 읽었다!
t3  nextVersion = 4로 INSERT ✅
t4                                  nextVersion = 4로 INSERT
                                    → UNIQUE 제약 위반! 💥
                                    → DataIntegrityViolationException
```

제약이 없다면 두 행이 모두 `version = 4`로 들어가고,
`depositSum`이 각각 `13`과 `103`이 되어 **한쪽 거래가 없어진 것처럼** 됩니다.
제약이 있으므로 **DB가 두 번째 요청을 거부**하고, 애플리케이션이 다시 시도할 기회를 얻습니다.

> 이것이 **낙관적 동시성 제어(optimistic concurrency control)** 의 한 형태입니다.
> "충돌은 드물 것이라 가정하고 일단 진행 → 충돌하면 재시도"
>
> 반대는 **비관적 락(pessimistic lock)** 입니다.
> `SELECT ... FOR UPDATE`로 행을 미리 잠가 다른 요청을 대기시킵니다.
> 충돌이 잦으면 비관적 락이, 드물면 낙관적 방식이 유리합니다.

**그래서 `PointService`가 재시도 로직을 가진 것입니다.** 그 재시도가 동작하지 않는다는 게 문제입니다.

## 1-5. `PointType` — enum에 설명을 담기

```java
public enum PointType {
    SPONSORSHIP("후원"), ATTENDANCE("출석"), BOARD("게시글"),
    COMMENT("댓글"), FEEDBACK("피드백"), QUESTION("질문"), INTERVIEW("면접");

    private final String description;

    PointType(String description) { this.description = description; }   // enum 생성자는 항상 private

    public String getDescription() { return description; }
}
```

`@Enumerated(EnumType.STRING)`이므로 DB에는 `"BOARD"`, `"QUESTION"` 문자열이 저장됩니다.
`ORDINAL`(기본값)을 썼다면 `SPONSORSHIP`을 목록 중간에 하나 추가하는 순간
**기존 데이터의 의미가 전부 밀립니다.** → [기초개념 6-2](../00-기초개념.md#enumeratedenumtypestring을-반드시-쓸-것)

---

# 2. `PointRepository`

```java
public interface PointRepository extends JpaRepository<Point, Long> {

    List<Point> findPointsByMemberIdOrderByVersionDesc(Long memberId);

    @Query("SELECT p FROM Point p WHERE p.member.id = :memberId ORDER BY p.version DESC LIMIT 1")
    Optional<Point> findLatestPointByMemberId(@Param("memberId") Long memberId);
}
```

## 2-1. 쿼리 메서드 이름 해석

```
findPointsByMemberIdOrderByVersionDesc
    │    │      │              │
    │  "Points"는 무시됨    OrderBy version DESC
    │           └─ By 뒤부터가 조건: member.id
   find
```

**`By` 앞의 단어는 전부 무시됩니다.** `findPointsBy...`, `findAllBy...`, `getBy...` 모두 같습니다.
가독성을 위해 붙이는 장식입니다.

`MemberId`는 `member` 연관관계의 `id`로 해석됩니다.
→ `WHERE p.member.id = ?` → 실제 SQL은 `WHERE member_id = ?` (JOIN 없이 FK 컬럼만 봅니다)

## 2-2. `@Query`를 쓴 이유 — JPQL의 `LIMIT`

```java
@Query("SELECT p FROM Point p WHERE p.member.id = :memberId ORDER BY p.version DESC LIMIT 1")
```

**JPQL이란**: SQL과 비슷하지만 **테이블이 아니라 엔티티를 대상**으로 하는 쿼리 언어입니다.
`FROM Point p`는 `points` 테이블이 아니라 `Point` **클래스**를 가리킵니다.
하이버네이트가 이를 각 DB 방언(MySQL, PostgreSQL...)의 SQL로 번역합니다.

`LIMIT`은 **JPQL 표준이 아닙니다.** Hibernate 6.2부터 지원하는 확장 문법입니다.
표준 방식은 아래 둘입니다:

```java
// 방법 A — 쿼리 메서드의 First/Top 키워드 (가장 깔끔)
Optional<Point> findFirstByMemberIdOrderByVersionDesc(Long memberId);

// 방법 B — Pageable로 개수 제한
@Query("SELECT p FROM Point p WHERE p.member.id = :memberId ORDER BY p.version DESC")
List<Point> findLatest(@Param("memberId") Long memberId, Pageable pageable);
// 호출: findLatest(1L, PageRequest.of(0, 1))
```

**방법 A로 바꾸면 `@Query`를 아예 지울 수 있습니다.**

## 2-3. 성능 — 인덱스가 필요하다

```sql
SELECT * FROM points WHERE member_id = ? ORDER BY version DESC LIMIT 1
```

이 쿼리는 **포인트가 생길 때마다 실행**되는 가장 뜨거운 쿼리입니다.
`@UniqueConstraint(member_id, version)`이 만든 유니크 인덱스가 있으므로
`(member_id, version)` 순서의 인덱스를 그대로 활용할 수 있습니다. **운 좋게 잘 맞았습니다.**

반면 `findPointsByMemberIdOrderByVersionDesc`는 **회원의 모든 거래를 다 가져옵니다.**
활동이 많은 회원이면 수천 행이 한 번에 로드됩니다. **페이징이 필요합니다.**

```java
Page<Point> findByMemberIdOrderByVersionDesc(Long memberId, Pageable pageable);
```

---

# 3. `PointService` — 세 개의 버그가 겹친 곳

전체 구조부터 봅니다.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PointService {                    // ⚠️ 클래스에 @Transactional이 없다

    private final PointRepository pointRepository;
    private final MemberRepository memberRepository;
    private final PointTierService pointTierService;

    public List<PointResponseDto> getAllPoint(...)         { ... }   // 트랜잭션 없음
    public CurrentPointResponseDto getCurrentPoint(...)    { ... }   // 트랜잭션 없음
    public PointResponseDto createPoint(...)               { ... }   // 트랜잭션 없음 (재시도 루프)

    @Transactional
    private PointResponseDto createPointInternal(...)      { ... }   // ⚠️ 무시됨
    private void updateMemberTierIfNeeded(...)             { ... }   // ⚠️ 무시됨
}
```

## 3-1. 🔴 버그 ① — `private` 메서드의 `@Transactional`은 무시된다

```java
public PointResponseDto createPoint(PointRequestDto dto, PrincipalDetails principal) {
    ...
    while (retryCount < maxRetries) {
        try {
            return createPointInternal(pointRequestDto, memberId);   // ★ this.createPointInternal()
        } catch (...) { ... }
    }
}

/**
 * 포인트 생성 내부 로직 (각 재시도마다 새로운 트랜잭션에서 실행)   ← 주석의 의도
 */
@Transactional                                                        // ⚠️ 아무 효과 없음
private PointResponseDto createPointInternal(PointRequestDto dto, Long memberId) { ... }
```

**두 가지 이유로 완전히 무시됩니다.**

### 이유 A — `private`은 프록시가 오버라이드할 수 없다

스프링은 `PointService`를 **상속한 프록시 클래스**를 만들어 빈으로 등록합니다.

```
     실제 빈                              원본
┌─────────────────────────────┐      ┌──────────────────┐
│ PointService$$SpringCGLIB$$0 │────▶ │ PointService     │
│  extends PointService        │ 상속  │                  │
│                              │      │ createPoint()    │
│  @Override createPoint() {   │      │ createPointInt.. │ ← private이라
│    tx.begin();               │      └──────────────────┘   자식이 볼 수 없음
│    super.createPoint();      │                              → 오버라이드 불가
│    tx.commit();              │                              → 트랜잭션 코드 삽입 불가
│  }                           │
│  // createPointInternal은    │
│  // 감쌀 수 없음              │
└─────────────────────────────┘
```

### 이유 B — self-invocation은 프록시를 거치지 않는다

`public`으로 바꿔도 여전히 무시됩니다.

```java
public PointResponseDto createPoint(...) {
    createPointInternal(...);        // 이것은 this.createPointInternal()
    //                                  = 원본 객체 내부에서의 직접 호출
    //                                  = 프록시를 거치지 않음
}
```

프록시는 **바깥에서 들어오는 호출**만 가로챕니다.
이미 원본 객체 안에 들어온 뒤의 호출은 프록시를 통과하지 않습니다.

> 컴파일 에러도, 경고도, 로그도 없습니다. **조용히 동작하지 않습니다.**
> IntelliJ는 이 경우 회색 경고를 표시하는데(`@Transactional` 밑줄), 놓치기 쉽습니다.

### 이 버그의 실제 결과

`createPoint`가 어디서 호출되느냐에 따라 두 가지 다른 사고가 납니다.

**케이스 1 — 트랜잭션 밖에서 호출 (예: `PointController.create`)**

```java
@PostMapping   // 컨트롤러는 @Transactional이 없다
public ... create(@RequestBody PointRequestDto request, ...) {
    pointService.createPoint(request, principalDetails);
}
```

```
createPointInternal 실행 (트랜잭션 없음!)
  ↓
memberRepository.findById(memberId)
  → Spring Data JPA의 save/find는 각각 자기만의 짧은 트랜잭션을 엽니다
  → 조회가 끝나면 그 트랜잭션이 닫히고 member는 "준영속(detached)"이 됩니다
  ↓
pointRepository.save(newPoint)
  → 자체 트랜잭션에서 INSERT ✅ (이건 저장됨)
  ↓
member.changePoint(calculatedPoint)
  → 준영속 엔티티의 필드만 바뀜
  → 영속성 컨텍스트가 없으므로 더티 체킹 대상이 아님
  → ❌ UPDATE member SET points = ? 가 실행되지 않음!
  ↓
updateMemberTierIfNeeded(member, ...)
  → member.changeTier(newTier)
  → ❌ 역시 저장되지 않음
```

**결과: `Point` 행은 생기지만 `Member.points`와 티어는 갱신되지 않습니다.**

즉 **포인트 원장과 회원의 표시 포인트가 영구히 어긋납니다.**
`GET /api/point/current`(원장 기준)와 `GET /api/member/info`(Member.points 기준)가
**다른 값을 반환합니다.** 게시글 목록의 작성자 레벨도 틀립니다.

→ 원리는 [기초개념 6-3](../00-기초개념.md#6-3-영속성-컨텍스트--jpa를-이해하는-열쇠), [6-4](../00-기초개념.md#6-4-더티-체킹--save-없이-update가-되는-이유)

**케이스 2 — 트랜잭션 안에서 호출 (예: `BoardService.createBoard`)**

```java
@Transactional                       // ★ 여기서 트랜잭션이 이미 시작됨
public void createBoard(...) {
    ...
    pointService.createPoint(pointRequestDto, principalDetails);
}
```

이 경우 `createPointInternal`은 **호출자의 트랜잭션에 얹혀서** 실행됩니다.
`member`가 영속 상태이므로 `changePoint()`가 더티 체킹으로 반영됩니다. **우연히 잘 동작합니다.**

**하지만 재시도가 망가집니다.** → 버그 ②

## 3-2. 🔴 버그 ② — 재시도 로직이 원리적으로 동작할 수 없다

```java
while (retryCount < maxRetries) {
    try {
        return createPointInternal(pointRequestDto, memberId);

    } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
        retryCount++;
        log.warn("포인트 생성 충돌 발생 - 재시도: {}/{}", retryCount, maxRetries);
        if (retryCount >= maxRetries) throw new PointCreationRetryExhaustedException();
        Thread.sleep(50L * retryCount);     // 50ms, 100ms, 150ms
    }
}
```

의도는 훌륭합니다. `version` 유니크 충돌이 나면 잠깐 쉬고 다시 시도하는 것.
**하지만 `@Transactional`이 무시되므로 "새 트랜잭션"이 만들어지지 않습니다.**

### 케이스 2에서 무슨 일이 벌어지나 — rollback-only 마킹

```
BoardService.createBoard 의 @Transactional 시작 (트랜잭션 T)
   ↓
pointService.createPoint()
   ↓ 1회차 시도
   createPointInternal() → T 안에서 실행 → INSERT에서 UNIQUE 충돌
   → DataIntegrityViolationException
   → ★ 트랜잭션 T가 "rollback-only"로 마킹됨 (되돌릴 수 없음)
   ↓ catch에서 잡고 50ms 대기 후 2회차 시도
   createPointInternal() → 여전히 T 안 → T는 이미 rollback-only
   → 하이버네이트 세션도 예외로 오염된 상태
   ↓ 3회차도 동일
   ↓
throw PointCreationRetryExhaustedException
   ↓
BoardService.createBoard 밖으로 전파 → 게시글도 롤백
   ↓
사용자: 500 에러. 게시글도 안 써짐.
```

**핵심 규칙**

> **JPA 예외가 한 번 발생한 트랜잭션은 재사용할 수 없습니다.**
> `catch`로 잡아도 트랜잭션은 이미 rollback-only이고, 하이버네이트 세션 상태도 신뢰할 수 없습니다.
> **재시도는 반드시 새 트랜잭션에서 해야 합니다.**
>
> → [기초개념 7-5](../00-기초개념.md#7-5-전파propagation--트랜잭션-안에서-트랜잭션을-만나면)

### 추가로 — 예외가 catch 지점까지 오지도 않는다

트랜잭션이 없는 케이스 1에서는 `pointRepository.save()`가 예외를 던지므로 catch가 동작합니다.
하지만 케이스 2(트랜잭션 안)에서는 **UNIQUE 위반이 `save()` 시점이 아니라
트랜잭션 커밋 시점(flush)에 발생할 수 있습니다.**

```java
pointRepository.save(newPoint);      // 영속성 컨텍스트에만 등록. INSERT는 아직 안 나갈 수 있음
                                     // (IDENTITY 전략이면 즉시 나가지만, 다른 전략에서는 지연)
...
// 커밋 시점에 flush → 여기서 예외 → 이미 catch 블록 밖!
```

`IDENTITY` 전략이라 이 프로젝트에서는 `save()` 시점에 INSERT가 나가므로 catch가 되지만,
**"어디서 예외가 나는지"를 의식하지 않은 코드**라는 점은 알아둘 필요가 있습니다.

## 3-3. 🟠 버그 ③ — `updateMemberTierIfNeeded`의 재시도는 의미가 없다

```java
private void updateMemberTierIfNeeded(Member member, Integer newPointTotal) {
    int retryCount = 0;
    while (retryCount < 3) {
        try {
            PointTier newTier = pointTierService.getTierByPoints(newPointTotal);   // SELECT

            if (member.getCurrentTier() == null ||
                    !Objects.equals(member.getCurrentTier().getTierOrder(), newTier.getTierOrder())) {
                member.changeTier(newTier);        // 필드 대입뿐 — UPDATE는 커밋 시점
            }
            return;

        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
            retryCount++;
            ...
        }
    }
}
```

**이 블록 안에서 `DataIntegrityViolationException`이 날 일이 없습니다.**

- `getTierByPoints`는 SELECT입니다 → 무결성 위반이 발생하지 않습니다
- `member.changeTier(newTier)`는 **자바 필드 대입**입니다 → SQL이 실행되지 않습니다
- 실제 UPDATE는 **트랜잭션 커밋 시점**에 나가는데, 그건 이 메서드가 끝난 뒤입니다

즉 **catch 블록은 절대 실행되지 않는 죽은 코드**이고, 재시도 루프는 항상 1회차에 `return`합니다.
"안정성 보장"이라는 주석은 사실과 다릅니다.

**추가 문제 — `getTierByPoints`가 던지는 예외**

```java
// PointTierService
public PointTier getTierByPoints(int points) {
    return pointTierRepository.findTierByPoints(points)
            .orElseThrow(() -> new IllegalStateException("해당 포인트에 맞는 등급을 찾을 수 없습니다: " + points));
}
```

`IllegalStateException`은 `catch (Exception e)` 블록에 걸려 **로그만 남기고 조용히 넘어갑니다.**

```java
} catch (Exception e) {
    log.error("티어 업데이트 예상치 못한 오류 - 회원ID: {}, ...", member.getId(), ...);
    return;      // 예외를 삼킨다
}
```

**포인트가 음수가 되면 이 일이 실제로 발생합니다.**
`point_tier` 테이블의 최하위 `minPoint`가 0이라면, `points = -5`인 회원에게 맞는 티어가 없습니다.
그러면 티어 갱신이 조용히 실패하고 로그만 남습니다.

> 포인트가 음수가 될 수 있나? → [4-2](#4-2-잔액-검증의-구멍) 참조. 될 수 있습니다.

## 3-4. 🟢 잘 만든 부분 — 잔액 검증

```java
// 포인트 잔액 검증
if (pointRequestDto.amount() + currentDepositSum + currentWithdrawSum < 0) {
    throw new InsufficientFundsException();
}
```

`현재잔액 + 이번거래금액 < 0`이면 거부합니다. 차감 시 잔액 부족을 막는 정상적인 검증입니다.
(단, 이 검증에도 동시성 구멍이 있습니다 → [4-2](#4-2-잔액-검증의-구멍))

## 3-5. 🟢 잘 만든 부분 — `InterruptedException` 처리

```java
try {
    Thread.sleep(50L * retryCount);
} catch (InterruptedException ie) {
    Thread.currentThread().interrupt();     // ★ 인터럽트 상태를 복원
    throw new PointCreationRetryExhaustedException();
}
```

**`Thread.currentThread().interrupt()`를 다시 호출하는 이유**

`InterruptedException`이 발생하면 JVM은 **스레드의 인터럽트 플래그를 자동으로 지웁니다.**
그대로 삼키면 "이 스레드를 종료하라"는 신호가 사라져서, 상위 코드(스레드풀 종료 로직 등)가
종료 요청을 알 수 없게 됩니다.

`interrupt()`를 다시 호출해 **플래그를 복원**하는 것이 Java 동시성의 표준 관례입니다.
학생 프로젝트에서 이걸 지킨 것은 인상적입니다.

---

# 4. 남은 동시성 문제

## 4-1. `Member.points`에도 갱신 유실이 있다

```java
Member member = memberRepository.findById(memberId).orElseThrow(...);
...
member.changePoint(calculatedPoint);       // 계산된 절대값으로 덮어쓴다
```

`Point` 원장은 `version` 유니크 제약으로 보호되지만, `Member.points`는 그렇지 않습니다.

```
       요청 A (+10)                    요청 B (+100)
t1  member 조회 (points=50)
t2                                 member 조회 (points=50)
t3  Point INSERT version=4 ✅
t4                                 Point INSERT version=4 → 충돌 → 재시도
t5                                 재시도로 version=5 INSERT ✅ (depositSum=160)
t6  member.changePoint(60) 커밋
t7                                 member.changePoint(160) 커밋 → 최종 160 ✅
```

이 순서면 결과가 맞습니다. 하지만 커밋 순서가 뒤집히면:

```
t6                                 member.changePoint(160) 커밋
t7  member.changePoint(60) 커밋  → ⚠️ 최종 60. 100점이 사라졌다.
```

**근본 해결: `Member.points`를 아예 없애고 원장에서 계산하기**

```java
// Member에서 points 필드 삭제, 필요할 때 최신 Point 행에서 읽기
public int getCurrentPoint(Long memberId) {
    return pointRepository.findFirstByMemberIdOrderByVersionDesc(memberId)
            .map(p -> p.getDepositSum() + p.getWithdrawSum())
            .orElse(0);
}
```

**차선책: 낙관적 락(`@Version`)을 추가하기**

```java
@Entity
public class Member extends BaseTimeEntity {
    ...
    @Version                      // JPA 표준 낙관적 락
    private Long lockVersion;
}
```

`@Version`이 붙으면 하이버네이트가 UPDATE에 조건을 자동으로 붙입니다:

```sql
UPDATE member SET points = 60, lock_version = 6 WHERE member_id = 1 AND lock_version = 5
```

다른 요청이 먼저 커밋해 `lock_version`이 6이 되어 있으면 **업데이트된 행이 0개**가 되고,
하이버네이트가 `ObjectOptimisticLockingFailureException`을 던집니다.
→ 그래서 `PointService`의 catch 절에 이 예외가 있는 것입니다. **의도는 알았지만 `@Version`을 안 붙였습니다.**

> `@Version` 필드를 추가하면 `PointService`의 catch가 드디어 의미를 갖습니다.
> 단, `ddl-auto: update`로는 컬럼이 추가되지만 기존 행이 `NULL`이 되어 문제가 생길 수 있으니
> `DEFAULT 0`으로 직접 넣어야 합니다.

## 4-2. 잔액 검증의 구멍

```java
if (amount + currentDepositSum + currentWithdrawSum < 0) throw new InsufficientFundsException();
```

이 검증은 **`t1`에 읽은 값**을 기준으로 합니다.

```
잔액 5점인 회원이 -5점 요청을 동시에 2번 보내면:

       요청 A (-5)                     요청 B (-5)
t1  최신 조회: version=3, 잔액 5
t2                                 최신 조회: version=3, 잔액 5
t3  검증: 5 - 5 = 0 >= 0 ✅
t4                                 검증: 5 - 5 = 0 >= 0 ✅     ← 둘 다 통과
t5  version=4 INSERT ✅ (잔액 0)
t6                                 version=4 INSERT → 충돌 → 재시도
t7                                 재시도: 최신 조회 version=4, 잔액 0
                                   검증: 0 - 5 = -5 < 0 → 거부 ✅
```

**`version` 유니크 제약이 잔액 검증까지 간접적으로 지켜줍니다.**
재시도할 때 최신 값을 다시 읽기 때문입니다.

**하지만 `@Transactional`이 무시돼 재시도가 동작하지 않으므로, 이 방어도 무력합니다.**
즉 **포인트가 음수로 내려갈 수 있습니다.** ([3-3](#3-3--버그--updatemembertierifneeded의-재시도는-의미가-없다)의 티어 조회 실패와 연결됩니다.)

## 4-3. `getCurrentPoint`와 `getAllPoint`에 트랜잭션이 없다

```java
public List<PointResponseDto> getAllPoint(PrincipalDetails principalDetails) {
    List<Point> points = pointRepository.findPointsByMemberIdOrderByVersionDesc(memberId);
    return points.stream().map(PointResponseDto::fromEntity).collect(Collectors.toList());
}
```

```java
// PointResponseDto.fromEntity 안에서
point.getMember().getId()        // ★ LAZY 프록시 초기화!
```

`Point.member`는 `LAZY`입니다. 트랜잭션(영속성 컨텍스트)이 없으면
**`LazyInitializationException`이 나야 정상**입니다.

**왜 실제로는 안 나는가?**
`getId()`는 **FK 값**이라서 프록시가 이미 알고 있습니다(추가 SELECT 없이 반환).
하이버네이트 프록시는 ID 접근만으로는 초기화되지 않습니다.

**즉 `getId()`이므로 우연히 통과한 것입니다.**
누군가 나중에 `point.getMember().getMemberBase().getNickname()`을 추가하면
**즉시 `LazyInitializationException`이 터집니다.**

```java
// 반드시 붙여야 하는 어노테이션
@Transactional(readOnly = true)
public List<PointResponseDto> getAllPoint(PrincipalDetails principalDetails) { ... }

@Transactional(readOnly = true)
public CurrentPointResponseDto getCurrentPoint(PrincipalDetails principalDetails) { ... }
```

→ [기초개념 6-6](../00-기초개념.md#6-6-연관관계와-지연-로딩lazy)

## 4-4. `memberId == null` 검사는 죽은 코드

```java
Long memberId = principalDetails.getMember().getId();
if (memberId == null) {
    throw new UserInvalidAccessException();
}
```

`principalDetails`가 `null`이면 **첫 줄에서 이미 NPE가 납니다.**
`getMember()`가 반환하는 `Member`는 DB에서 조회한 것이므로 `id`가 `null`일 수 없습니다.

즉 이 검사는 **아무것도 막지 못합니다.** 진짜 막아야 할 것은 `principalDetails == null`입니다.

```java
if (principalDetails == null) {
    throw new UserInvalidAccessException();
}
Long memberId = principalDetails.getMember().getId();
```

> 더 나은 방법은 **시큐리티 설정에서 인증을 강제**하는 것입니다.
> `/api/point/**`는 이미 `authenticated()`이므로 실제로는 `null`이 올 수 없습니다.
> 그래도 방어 코드를 남긴다면 올바른 대상을 검사해야 합니다.

---

# 5. 전체를 고친 코드

지금까지의 문제를 모두 반영한 형태입니다.

## 5-1. 트랜잭션 단위를 별도 빈으로 분리

```java
package com.gaebang.backend.domain.point.service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointTransactionService {

    private final PointRepository pointRepository;
    private final MemberRepository memberRepository;
    private final PointTierService pointTierService;

    /**
     * 포인트 1건을 독립 트랜잭션에서 생성한다.
     * REQUIRES_NEW: 호출자의 트랜잭션과 분리되어야 재시도가 가능하다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PointResponseDto create(PointRequestDto dto, Long memberId) {

        Member member = memberRepository.findById(memberId)
                .orElseThrow(UserNotFoundException::new);

        Point latest = pointRepository.findFirstByMemberIdOrderByVersionDesc(memberId)
                .orElse(null);

        int nextVersion    = (latest == null) ? 1 : latest.getVersion() + 1;
        int currentDeposit = (latest == null) ? 0 : latest.getDepositSum();
        int currentWithdraw= (latest == null) ? 0 : latest.getWithdrawSum();

        int amount = dto.amount();
        if (amount + currentDeposit + currentWithdraw < 0) {
            throw new InsufficientFundsException();
        }

        int newDeposit  = amount > 0 ? currentDeposit  + amount : currentDeposit;
        int newWithdraw = amount < 0 ? currentWithdraw + amount : currentWithdraw;

        Point newPoint = dto.toEntity(member, newDeposit, newWithdraw, nextVersion);
        pointRepository.save(newPoint);              // INSERT (새 엔티티)

        int calculated = newDeposit + newWithdraw;
        member.changePoint(calculated);              // 영속 상태 → 더티 체킹으로 UPDATE ✅
        updateTierIfChanged(member, calculated);     // 같은 트랜잭션 안 → 역시 더티 체킹 ✅

        log.info("포인트 생성 - 회원ID: {}, 금액: {}, 잔액: {}", memberId, amount, calculated);
        return PointResponseDto.fromEntity(newPoint);
    }

    /** 티어가 실제로 바뀔 때만 갱신. 재시도 루프는 제거 (여기서는 충돌이 발생하지 않는다) */
    private void updateTierIfChanged(Member member, int newTotal) {
        PointTier newTier = pointTierService.getTierByPoints(newTotal);
        PointTier current = member.getCurrentTier();

        if (current == null || !Objects.equals(current.getTierOrder(), newTier.getTierOrder())) {
            member.changeTier(newTier);
            log.info("티어 변경 - 회원ID: {}, 새 티어: {}", member.getId(), newTier.getTierType());
        }
    }
}
```

## 5-2. 재시도는 트랜잭션 밖에서

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)                    // ★ 기본은 읽기 전용
public class PointService {

    private static final int MAX_RETRIES = 3;

    private final PointRepository pointRepository;
    private final PointTransactionService pointTransactionService;   // ★ 다른 빈 → 프록시 경유

    public List<PointResponseDto> getAllPoint(PrincipalDetails principalDetails) {
        Long memberId = requireMemberId(principalDetails);
        return pointRepository.findByMemberIdOrderByVersionDesc(memberId).stream()
                .map(PointResponseDto::fromEntity)
                .toList();
    }

    public CurrentPointResponseDto getCurrentPoint(PrincipalDetails principalDetails) {
        Long memberId = requireMemberId(principalDetails);
        return pointRepository.findFirstByMemberIdOrderByVersionDesc(memberId)
                .map(p -> CurrentPointResponseDto.fromEntity(p, p.getDepositSum() + p.getWithdrawSum()))
                .orElseGet(() -> CurrentPointResponseDto.fromEntity(memberId, 0));
    }

    /**
     * 트랜잭션을 걸지 않는다 — 각 시도가 REQUIRES_NEW로 독립 실행되어야 하므로.
     * 만약 여기에 @Transactional을 걸면 재시도가 다시 망가진다.
     */
    public PointResponseDto createPoint(PointRequestDto dto, PrincipalDetails principalDetails) {
        Long memberId = requireMemberId(principalDetails);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return pointTransactionService.create(dto, memberId);      // ★ 프록시 경유 ✅

            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
                log.warn("포인트 생성 충돌 - 회원ID: {}, 시도: {}/{}", memberId, attempt, MAX_RETRIES);

                if (attempt == MAX_RETRIES) {
                    throw new PointCreationRetryExhaustedException();
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw new PointCreationRetryExhaustedException();   // 도달 불가 (컴파일러 요구)
    }

    public void deductQuestionPoints(PrincipalDetails principalDetails) {
        createPoint(PointRequestDto.builder().amount(-5).type(PointType.QUESTION).build(),
                    principalDetails);
    }

    public void rewardFeedbackPoints(PrincipalDetails principalDetails) {
        createPoint(PointRequestDto.builder().amount(10).type(PointType.FEEDBACK).build(),
                    principalDetails);
    }

    private Long requireMemberId(PrincipalDetails principalDetails) {
        if (principalDetails == null || principalDetails.getMember() == null) {
            throw new UserInvalidAccessException();
        }
        return principalDetails.getMember().getId();
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PointCreationRetryExhaustedException();
        }
    }
}
```

## 5-3. ⚠️ `REQUIRES_NEW`의 대가를 반드시 알고 쓰기

```java
@Transactional
public void createBoard(...) {
    boardRepository.save(board);                       // 트랜잭션 T1
    pointService.createPoint(...);                     // 트랜잭션 T2 (REQUIRES_NEW) → 즉시 커밋
    // 여기서 예외가 나면?
    //   T1(게시글) → 롤백
    //   T2(포인트) → 이미 커밋됨. 되돌아가지 않음. ⚠️
}
```

**게시글은 없는데 포인트만 적립되는 상태**가 생길 수 있습니다.

**두 가지 선택지**

| 선택 | 방법 | 장점 | 단점 |
|---|---|---|---|
| **A. 원자성 우선** | `REQUIRED`로 호출자 트랜잭션에 참여 | 게시글+포인트가 함께 성공/실패 | 재시도 불가 (충돌 시 전체 실패) |
| **B. 가용성 우선** | `REQUIRES_NEW` + 재시도 | 포인트 충돌을 흡수 | 부분 성공 가능 |

**실용적인 절충**: 포인트 적립을 **트랜잭션 커밋 후 이벤트**로 분리합니다.
이 프로젝트가 게시글 검열에 이미 쓰는 방식입니다.

```java
// BoardService — 게시글 저장만 트랜잭션에 두고, 포인트는 커밋 후에
@Transactional
public void createBoard(...) {
    Board saved = boardRepository.save(createBoard);
    eventPublisher.publishEvent(new PointEarnedEvent(loginMember.getId(), PointType.BOARD, 10));
}

// 별도 리스너 — 게시글이 확실히 저장된 뒤에만 실행됨
@Component
@RequiredArgsConstructor
public class PointEventListener {

    private final PointService pointService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PointEarnedEvent event) {
        pointService.createPointByMemberId(event.memberId(), event.type(), event.amount());
    }
}
```

이렇게 하면:
- 게시글 저장이 실패하면 → 포인트 이벤트는 발행되지 않음 (커밋 안 됨) ✅
- 포인트 적립이 실패하면 → 게시글은 남고 로그가 남음 (재처리 대상) ✅

> 이 프로젝트의 `AttendanceService`가 정확히 이 고민을 했고, `REQUIRES_NEW`를 쓰려 했습니다.
> 그런데 **self-invocation 때문에 그것도 동작하지 않습니다.** → [attendance.md](attendance.md)

## 5-4. 리포지토리 정리

```java
public interface PointRepository extends JpaRepository<Point, Long> {

    // @Query 없이 First 키워드로 (LIMIT 1 대체)
    Optional<Point> findFirstByMemberIdOrderByVersionDesc(Long memberId);

    // 페이징 추가 (거래가 많은 회원 대비)
    Page<Point> findByMemberIdOrderByVersionDesc(Long memberId, Pageable pageable);
}
```

---

# 6. `PointController` — 테스트용 API가 열려 있다

```java
@RestController
@RequestMapping("/api/point")
@RequiredArgsConstructor
public class PointController {

    @GetMapping("/history")   // 내 포인트 내역
    @GetMapping("/current")   // 내 현재 포인트

    // test point 생성용
    @PostMapping                                        // ⚠️ POST /api/point
    public ResponseEntity<ResponseDTO<PointResponseDto>> create(
            @RequestBody PointRequestDto request,        // ⚠️ amount를 클라이언트가 지정
            @AuthenticationPrincipal PrincipalDetails principalDetails) {
        PointResponseDto dto = pointService.createPoint(request, principalDetails);
        ...
    }
}
```

### 🔴 문제 — 사용자가 원하는 만큼 포인트를 만들 수 있다

`/api/point/**`는 `permitAll` 목록에 없으므로 **로그인은 필요합니다.**
하지만 로그인한 사용자가 `amount`를 **직접 지정**할 수 있습니다.

```bash
curl -X POST https://gaebang.site/api/point \
     -H "Authorization: Bearer <내토큰>" \
     -H "Content-Type: application/json" \
     -d '{"amount": 999999, "type": "SPONSORSHIP"}'
```

포인트는 질문·면접 기능을 쓰는 데 소비되고, 결제(`payment`)로 구매하는 대상이기도 합니다.
즉 **유료 기능을 무한히 공짜로 쓸 수 있습니다.**

주석에 "test point 생성용"이라고 적혀 있으니 개발용으로 만든 것이 분명합니다.
**삭제해야 합니다.** 관리자 기능으로 남기려면 최소한 권한을 걸어야 합니다.

```java
@PostMapping("/admin/grant")
@PreAuthorize("hasRole('ADMIN')")       // 메서드 보안 (@EnableMethodSecurity 필요)
public ResponseEntity<...> grant(@RequestBody PointGrantRequestDto request) { ... }
```

> `@PreAuthorize`도 프록시 기반이므로 `public` 메서드에만 동작합니다.
> 그리고 `@EnableMethodSecurity`를 설정 클래스에 붙여야 활성화됩니다.
> 이 프로젝트에는 아직 없습니다.

### 🟢 잘한 판단 — 수정/삭제 API를 만들지 않은 것

```java
// 포인트 수정, 삭제 api는 만들지 않는 것을 권장
// 대신 차감이나 재적립 api를 만드는 것이 좋다고 함.
```

**정확한 원칙입니다.** 원장(ledger)은 **추가 전용(append-only)** 이어야 합니다.
잘못 적립된 포인트를 "수정"하면 감사 추적이 끊깁니다.
대신 **상계 거래(반대 부호의 새 행)** 를 추가하는 것이 회계의 정석입니다.

```
version 4 | +100 | ATTENDANCE     ← 잘못 적립
version 5 | -100 | CORRECTION     ← 취소 거래를 추가 (기존 행은 그대로)
```

---

# 7. `PointResponseDto` — 날짜 직렬화 이야기

```java
public record PointResponseDto (
        Long pointId, Long memberId, Integer amount, PointType type,
        Integer depositSum, Integer withdrawSum,
//      LocalDateTime data 로 했을 경우 response 가 [ ... ] 이렇게 배열형식으로 넘어온다
        String date
) {
    public static PointResponseDto fromEntity(Point point) {
        return new PointResponseDto(
                ...,
                DataFormatter.getFormattedCreatedAtWithTime(point.getDate())   // "2025.07.23 14:30"
        );
    }
}
```

주석의 관찰이 정확합니다. `LocalDateTime`을 그대로 내보내면 이렇게 나갑니다:

```json
"date": [2025, 7, 23, 14, 30, 0]      // ← 배열!
```

**왜 배열인가**: Jackson의 `JavaTimeModule`은 기본적으로 `LocalDateTime`을
**필드 배열**로 직렬화합니다(`WRITE_DATES_AS_TIMESTAMPS`가 기본 활성).

**해결책 3가지**

```java
// ① 이 프로젝트의 방식 — 서버에서 문자열로 포맷
String date;   // "2025.07.23 14:30"

// ② Jackson 설정으로 전역 해결 (권장)
// AppConfig의 ObjectMapper에 한 줄 추가
objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
// → "date": "2025-07-23T14:30:00"  (ISO-8601 표준)

// ③ 필드별로 포맷 지정
@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
LocalDateTime date;
```

**②가 가장 좋습니다.** ISO-8601은 국제 표준이라 프론트엔드의 `new Date(...)`,
`dayjs()`, `Intl.DateTimeFormat`이 모두 바로 파싱합니다.

**①의 문제**: `"2025.07.23 14:30"`은 표준 형식이 아니라 프론트가 직접 파싱해야 하고,
**타임존 정보가 없습니다.** 서버가 UTC로 돌면 한국 사용자에게 9시간 어긋난 시간이 표시됩니다.
또 "표시 형식"은 화면(프론트)의 관심사인데 서버가 결정하고 있어서,
나중에 "상대 시간(3분 전)"으로 바꾸려면 서버를 고쳐야 합니다.

> `date` 필드가 `BaseTimeEntity.createdAt`과 **중복**이라는 점도 짚어둘 만합니다.
> `Point`는 `BaseTimeEntity`를 상속하므로 `created_at` 컬럼이 이미 있습니다.
> `date` 컬럼은 없어도 되고, `createdAt`을 쓰면 됩니다.

---

# 8. 정리 — 고쳐야 할 것

| 우선순위 | 문제 | 위치 | 참조 |
|---|---|---|---|
| 🔴 1 | `POST /api/point`로 무한 포인트 생성 가능 | `PointController` | [6장](#6-pointcontroller--테스트용-api가-열려-있다) |
| 🔴 2 | `private @Transactional` → 트랜잭션 완전 무시 | `PointService:110` | [3-1](#3-1--버그--private-메서드의-transactional은-무시된다) |
| 🔴 3 | 트랜잭션 밖 호출 시 `Member.points`·티어가 저장되지 않음 | `PointService` | [3-1 케이스1](#이-버그의-실제-결과) |
| 🔴 4 | 재시도가 원리적으로 동작 불가 (rollback-only) | `PointService:81` | [3-2](#3-2--버그--재시도-로직이-원리적으로-동작할-수-없다) |
| 🟠 5 | 조회 메서드에 트랜잭션 없음 → LAZY 폭탄 대기 중 | `getAllPoint`, `getCurrentPoint` | [4-3](#4-3-getcurrentpoint와-getallpoint에-트랜잭션이-없다) |
| 🟠 6 | `Member.points` 갱신 유실 (`@Version` 없음) | `Member` 엔티티 | [4-1](#4-1-memberpoints에도-갱신-유실이-있다) |
| 🟠 7 | 포인트가 음수가 되면 티어 조회 실패 → 조용히 무시 | `updateMemberTierIfNeeded` | [3-3](#3-3--버그--updatemembertierifneeded의-재시도는-의미가-없다) |
| 🟡 8 | `updateMemberTierIfNeeded`의 재시도 루프는 죽은 코드 | `PointService:190` | [3-3](#3-3--버그--updatemembertierifneeded의-재시도는-의미가-없다) |
| 🟡 9 | `memberId == null` 검사가 잘못된 대상 | `PointService` 3곳 | [4-4](#4-4-memberid--null-검사는-죽은-코드) |
| 🟡 10 | 전체 내역 조회에 페이징 없음 | `PointRepository` | [2-3](#2-3-성능--인덱스가-필요하다) |
| 🟢 11 | `date` 필드가 `createdAt`과 중복 | `Point` 엔티티 | [7장](#7-pointresponsedto--날짜-직렬화-이야기) |
| 🟢 12 | 날짜를 서버에서 문자열 포맷 (타임존 없음) | `PointResponseDto` | [7장](#7-pointresponsedto--날짜-직렬화-이야기) |
| 🟢 13 | `LIMIT 1` JPQL → `findFirstBy...`로 대체 가능 | `PointRepository` | [2-2](#2-2-query를-쓴-이유--jpql의-limit) |

# 9. 정리 — 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| 원장(ledger) 방식 설계 | 감사 가능, 이력 보존. 금융 시스템의 정석 |
| 러닝 밸런스(`depositSum`/`withdrawSum`) | 잔액 조회가 O(1). `SUM()` 스캔 불필요 |
| `@UniqueConstraint(member_id, version)` | 동시 삽입을 DB가 막아줌. 낙관적 제어의 기반 |
| 수정/삭제 API를 만들지 않은 판단 | append-only 원칙. 주석에 이유까지 적어둠 |
| `@Enumerated(EnumType.STRING)` | ORDINAL의 함정을 피함 |
| `Thread.currentThread().interrupt()` 복원 | Java 동시성 표준 관례를 지킴 |
| 잔액 검증 로직 자체 | 차감 시 음수 방지 의도가 명확 |
| `PointType`에 한글 설명 담기 | enum을 상수 나열이 아니라 객체로 활용 |

---

## 다음 문서

- [pointTier.md](pointTier.md) — 티어 조회가 목록에서 N번 실행되는 문제
- [attendance.md](attendance.md) — `REQUIRES_NEW`를 쓰려 했지만 같은 self-invocation 함정에 빠진 사례
- [member.md](member.md) — `Member.points`의 반정규화 배경
- [community.md](community.md) — `PointService`를 호출하는 가장 큰 소비자
