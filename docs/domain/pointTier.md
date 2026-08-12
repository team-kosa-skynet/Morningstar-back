# pointTier — 포인트 등급(티어)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [point.md](point.md)
>
> 파일 7개, 실질 코드는 60줄 정도인 가장 작은 도메인입니다.
> 그런데 **게시글 목록을 열 때마다 쿼리를 N번 만들어내는 성능 문제의 원인**이 여기 있습니다.
> 작은 코드가 어떻게 큰 부하를 만드는지 보는 좋은 예입니다.

---

## 파일 지도

```
domain/pointTier/
├── controller/PointTierController.java   ⚠️ 전체가 주석 처리된 빈 파일
├── dto/
│   ├── request/PointTierRequestDto.java   ⚠️ 내용 없는 빈 클래스
│   └── response/PointTierResponseDto.java ⚠️ 내용 없는 빈 클래스
├── entity/
│   ├── PointTier.java                    등급 정보
│   └── TierType.java                     등급 이름 enum (행성 테마)
├── repository/PointTierRepository.java
└── service/PointTierService.java         메서드 1개
```

**7개 파일 중 3개가 빈 파일입니다.** "패키지 구조를 먼저 만들고 채우려다 만" 흔적입니다.

```java
// PointTierRequestDto.java — 전부
public class PointTierRequestDto {
}

// PointTierResponseDto.java — 전부
public class PointTierResponseDto {
}

// PointTierController.java — 전부 주석
//@RestController
//@RequestMapping("/point-tier")
//public class PointTierController { ... }
```

> **빈 파일은 삭제해야 합니다.** 파일 목록을 볼 때 "여기에 뭔가 있다"고 오해하게 만들고,
> IDE의 자동완성 후보에 끼어들어 방해합니다.
> 실제로 이 문제 때문에 앞서 컨트롤러를 세다가 `PointTierController`를 살아있는 것으로 착각할 수 있었습니다.
>
> Git이 이력을 보관하므로 **"나중에 쓸지도 모르니 남겨둔다"는 이유는 성립하지 않습니다.**

---

# 1. `TierType` — 행성 테마 등급

```java
public enum TierType {
    MERCURY("수성"), VENUS("금성"), EARTH("지구"), MARS("화성"), JUPITER("목성"),
    SATURN("토성"), URANUS("천왕성"), NEPTUNE("해왕성"), SUN("태양"), BLACK_HOLE("블랙홀");

    private final String displayName;

    TierType(String displayName) { this.displayName = displayName; }

    public String getDisplayName() { return displayName; }
}
```

브론즈-실버-골드 대신 **태양계 순서**를 쓴 것이 서비스 컨셉("개발자의 방주")과 잘 맞습니다.
수성(태양에 가장 가까움) → 블랙홀(최상위)로 올라가는 구성입니다.

**enum에 한글 표시명을 담는 패턴**
DB에는 `MERCURY`(영문 상수명)가 저장되고, 화면에는 `수성`이 표시됩니다.
언어를 바꿔야 하면 이 enum만 고치면 됩니다.
→ [기초개념 1-5](../00-기초개념.md#1-5-enum--상수의-집합-그런데-메서드도-가진다)

> 다만 `getDisplayName()`을 **아무도 호출하지 않습니다.**
> API는 `tierOrder`(정수)만 반환하고, 등급 이름은 프론트엔드가 자체 매핑하는 구조입니다.
> 서버가 한글명을 갖고 있는데 안 쓰는 것은 낭비입니다.

---

# 2. `PointTier` 엔티티

```java
@Entity
@Table(name = "point_tier")
@Getter
@NoArgsConstructor            // ⚠️ access = PROTECTED가 아니다
@AllArgsConstructor
@Builder
public class PointTier extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long tierId;                       // ⚠️ @Column(name=...) 없음 → 컬럼명 tier_id

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)   // ★ 유니크 제약이 있다 (좋음)
    private TierType tierType;

    @Column(nullable = false)
    private Integer minPoint;                  // 이 등급의 최소 포인트

    @Column
    private Integer maxPoint;                  // 최상위 등급은 null (상한 없음)

    @Column(nullable = false)
    private Integer tierOrder;                 // 정렬·비교용 순서 (1, 2, 3, ...)
}
```

## 2-1. `maxPoint`가 `null`일 수 있는 설계

```
tier_order | tier_type   | min_point | max_point
    1      | MERCURY     |     0     |    99
    2      | VENUS       |   100     |   499
    ...
   10      | BLACK_HOLE  | 100000    |  NULL    ← 상한 없음
```

**`null`을 "무한대"로 쓰는 것은 흔한 패턴**이지만, 쿼리를 복잡하게 만듭니다.

```java
@Query("SELECT t FROM PointTier t WHERE :points >= t.minPoint AND " +
        "(:points <= t.maxPoint OR t.maxPoint IS NULL) " +      // ★ null 처리가 필요
        "ORDER BY t.tierOrder DESC LIMIT 1")
```

**대안**: `maxPoint`를 아예 없애고 `minPoint`만 두는 방법입니다.

```java
// "내 포인트 이하의 minPoint를 가진 등급 중 가장 높은 것"
@Query("SELECT t FROM PointTier t WHERE t.minPoint <= :points ORDER BY t.tierOrder DESC LIMIT 1")
```

이렇게 하면 **구간이 겹치거나 빈틈이 생길 수 없습니다.**
지금 구조는 `MERCURY(0~99)`와 `VENUS(100~499)` 사이에 실수로 빈틈(`maxPoint=98`)을 만들면
포인트 99인 회원에게 맞는 등급이 **하나도 없게** 됩니다.

## 2-2. ⚠️ 음수 포인트 대비가 없다

`minPoint`의 최솟값이 `0`이라면, **포인트가 음수인 회원에게 맞는 등급이 없습니다.**

```java
// PointTierService
public PointTier getTierByPoints(int points) {
    return pointTierRepository.findTierByPoints(points)
            .orElseThrow(() -> new IllegalStateException("해당 포인트에 맞는 등급을 찾을 수 없습니다: " + points));
}
```

→ `IllegalStateException`이 던져집니다.
`PointService.updateMemberTierIfNeeded`의 `catch (Exception e)`가 이걸 삼켜서
**로그만 남고 티어 갱신이 조용히 실패**합니다. → [point.md 3-3](point.md#3-3--버그--updatemembertierifneeded의-재시도는-의미가-없다)

**포인트가 음수가 될 수 있는가?** → 될 수 있습니다.
`PointService`의 잔액 검증이 동시성 상황에서 뚫리기 때문입니다. → [point.md 4-2](point.md#4-2-잔액-검증의-구멍)

**해결**: 최하위 등급의 `minPoint`를 `Integer.MIN_VALUE`나 충분히 작은 음수로 두거나,
조회 실패 시 최하위 등급으로 폴백합니다.

```java
public PointTier getTierByPoints(int points) {
    return pointTierRepository.findTierByPoints(points)
            .orElseGet(this::getLowestTier);      // 예외 대신 최하위 등급 반환
}
```

## 2-3. ⚠️ `@NoArgsConstructor`가 `public`이다

```java
@NoArgsConstructor              // 기본값은 public!
```

다른 엔티티들은 이렇게 되어 있습니다:

```java
@NoArgsConstructor(access = AccessLevel.PROTECTED)     // Member, Board 등
```

`public` 기본 생성자는 아무 데서나 `new PointTier()`로 **빈 껍데기 등급 객체**를 만들 수 있게 합니다.
JPA가 요구하는 것은 "기본 생성자의 존재"일 뿐, `public`일 필요는 없습니다.
→ [기초개념 3-1](../00-기초개념.md#3-1-생성자-계열)

> 이 프로젝트에서 `@NoArgsConstructor`가 `public`인 엔티티: `PointTier`, `Point`, `Attendance` 등.
> `PROTECTED`인 엔티티: `Member`, `MemberBase`.
> **일관성이 없습니다.** 전부 `PROTECTED`로 통일하는 것이 맞습니다.

## 2-4. 이 테이블은 누가 채우나 — 아무도 채우지 않는다

`PointTier`를 저장하는 코드가 **프로젝트 전체에 없습니다.**
`ddl-auto: update`가 테이블은 만들어주지만 **행은 넣어주지 않습니다.**

그래서 `database/init.sql`이나 수동 SQL로 넣어야 하고, 그렇지 않으면:

```java
// MemberService.signup
PointTier pointTier = pointTierRepository.findById(1L)
        .orElseThrow(PointTierIsNotExistException::new);   // ← 회원가입이 전부 실패
```

**회원가입이 아예 동작하지 않습니다.**
`ErrorCode`에 이런 메시지가 있는 것을 보면 실제로 겪은 문제입니다:

```java
POINT_TIER_IS_NOT_EXIST(HttpStatus.BAD_REQUEST, "포인트 티어 정보가 DB에 초기화 되지 않았습니다."),
```

**개선 — `AiMemberInitializer`처럼 초기화 컴포넌트를 만들면 됩니다.**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PointTierInitializer {

    private final PointTierRepository pointTierRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        if (pointTierRepository.count() > 0) return;        // 멱등성

        List<PointTier> tiers = List.of(
            PointTier.builder().tierType(TierType.MERCURY).minPoint(0)     .maxPoint(99)    .tierOrder(1).build(),
            PointTier.builder().tierType(TierType.VENUS)  .minPoint(100)   .maxPoint(499)   .tierOrder(2).build(),
            // ...
            PointTier.builder().tierType(TierType.BLACK_HOLE).minPoint(100000).maxPoint(null).tierOrder(10).build()
        );
        pointTierRepository.saveAll(tiers);
        log.info("[PointTierInitializer] 등급 {}건 초기화 완료", tiers.size());
    }
}
```

이렇게 하면 **DB를 새로 만들어도 그냥 뜹니다.** 신입 팀원이 로컬 환경을 세팅할 때
"왜 회원가입이 안 되나요?"로 시간을 쓰지 않게 됩니다.

---

# 3. `PointTierRepository` — 쿼리 3개

```java
public interface PointTierRepository extends JpaRepository<PointTier, Long> {

    // ① 포인트에 해당하는 등급 엔티티
    @Query("SELECT t FROM PointTier t WHERE :points >= t.minPoint AND " +
            "(:points <= t.maxPoint OR t.maxPoint IS NULL) ORDER BY t.tierOrder DESC LIMIT 1")
    Optional<PointTier> findTierByPoints(@Param("points") int points);

    // ② 포인트에 해당하는 등급의 순서(정수)만
    @Query("SELECT t.tierOrder FROM PointTier t WHERE :points >= t.minPoint AND " +
            "(:points <= t.maxPoint OR t.maxPoint IS NULL) ORDER BY t.tierOrder DESC LIMIT 1")
    int findTierOrderByPoints(@Param("points") int points);

    // ③ 전체 등급 정렬 조회 (사용처 없음)
    List<PointTier> findAllByOrderByTierOrderAsc();
}
```

## 3-1. ①과 ②의 차이 — 프로젝션

`WHERE` 절이 완전히 같고 `SELECT` 대상만 다릅니다.

```java
SELECT t            → 엔티티 전체를 로드 (모든 컬럼 + 영속성 컨텍스트 등록)
SELECT t.tierOrder  → 정수 하나만 (엔티티를 만들지 않음)
```

②는 **스칼라 프로젝션**입니다. `tierOrder`만 필요한 곳에서 엔티티 전체를 로드하지 않으니
올바른 최적화입니다. 다만 반환 타입이 `int`(원시 타입)라서 **결과가 없으면 예외가 납니다.**

```java
int findTierOrderByPoints(int points);
// 결과 0건 → Spring Data가 null을 int에 대입하려 함 → EmptyResultDataAccessException
```

`Optional<Integer>`나 `Integer`로 받아 `null` 처리를 하는 편이 안전합니다.

## 3-2. ③은 아무도 쓰지 않는다

`findAllByOrderByTierOrderAsc()`를 호출하는 코드가 없습니다.
대신 `AiMemberInitializer`가 이렇게 하고 있습니다:

```java
PointTier defaultTier = pointTierRepository.findAll()      // 정렬 없이 전체 조회
        .stream()
        .min((t1, t2) -> Integer.compare(t1.getTierOrder(), t2.getTierOrder()))   // 자바에서 정렬
        .orElse(null);
```

**이미 만들어둔 정렬 쿼리를 쓰지 않고 자바에서 다시 정렬합니다.**
등급이 10개뿐이라 성능 차이는 없지만, **있는 것을 안 쓰는 것은 나쁜 신호**입니다.
DB가 인덱스로 처리할 수 있는 일을 애플리케이션이 하고 있습니다.

```java
// 이렇게 쓰면 됨
PointTier defaultTier = pointTierRepository.findAllByOrderByTierOrderAsc()
        .stream().findFirst().orElse(null);

// 더 나은 방법 — 필요한 1건만 조회
Optional<PointTier> findFirstByOrderByTierOrderAsc();
```

---

# 4. `PointTierService` — 메서드 하나

```java
@Service
@RequiredArgsConstructor
public class PointTierService {

    private final PointTierRepository pointTierRepository;

    public PointTier getTierByPoints(int points) {
        return pointTierRepository.findTierByPoints(points)
                .orElseThrow(() -> new IllegalStateException("해당 포인트에 맞는 등급을 찾을 수 없습니다: " + points));
    }
}
```

### ⚠️ 문제 — `IllegalStateException`은 이 프로젝트의 예외 규약을 어긴다

이 프로젝트는 `ApplicationException` 기반의 커스텀 예외 체계를 갖고 있습니다.
→ [global 3장](../global.md#3-exception--예외-처리-3층-구조)

그런데 여기서는 표준 `IllegalStateException`을 던집니다. 결과:

```
IllegalStateException
   ↓ ApplicationException 핸들러에 안 걸림
GlobalExceptionRestAdvice.serverException(RuntimeException e)
   ↓
500 "서버 에러!"    ← 원래 메시지도 사라진다
```

`ErrorCode`에 이미 적절한 코드가 **있습니다**:

```java
POINT_TIER_IS_NOT_EXIST(HttpStatus.BAD_REQUEST, "포인트 티어 정보가 DB에 초기화 되지 않았습니다."),
```

`PointTierIsNotExistException`도 이미 있습니다(`MemberService`가 사용).
**그냥 그걸 쓰면 됩니다.**

```java
public PointTier getTierByPoints(int points) {
    return pointTierRepository.findTierByPoints(points)
            .orElseThrow(PointTierIsNotExistException::new);
}
```

### ⚠️ 문제 — 트랜잭션 어노테이션이 없다

조회만 하므로 치명적이지 않지만, `PointTier`를 반환하면 그것은 **준영속 엔티티**입니다.
`PointService`가 이걸 받아 `member.changeTier(newTier)`로 연관관계를 설정합니다.

```java
// PointTransactionService (트랜잭션 안) 에서
PointTier newTier = pointTierService.getTierByPoints(calculated);   // 다른 트랜잭션에서 조회?
member.changeTier(newTier);
```

`pointTierService.getTierByPoints`에 트랜잭션이 없으면 호출자의 트랜잭션에 참여합니다
(`Propagation.REQUIRED`가 기본이지만, 어노테이션 자체가 없으면 **트랜잭션을 시작하지 않고
호출자 것을 그냥 씁니다**). 결과적으로는 같은 영속성 컨텍스트에서 조회되므로 동작합니다.

**하지만 명시하는 것이 안전합니다.**

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointTierService { ... }
```

> 만약 `REQUIRES_NEW`로 별도 트랜잭션에서 조회했다면 `newTier`가 준영속이 되어,
> `member.changeTier(newTier)` 시 **다른 영속성 컨텍스트의 엔티티를 연결**하는 상황이 됩니다.
> FK만 저장하는 `@ManyToOne`이라 실제로는 동작하지만, 이런 코드는 사고를 부릅니다.

---

# 5. 🔴 성능 문제 — 게시글 목록에서 쿼리가 N번 실행된다

**이 도메인의 가장 실질적인 문제입니다.**

## 5-1. 무슨 일이 벌어지나

```java
// BoardService.transformBoardDtos
private Page<BoardListResponseDto> transformBoardDtos(Page<BoardListProjectionDto> projectionDtos) {
    return projectionDtos.map(dto ->
            BoardListResponseDto.builder()
                    .boardId(dto.boardId())
                    .title(dto.title())
                    ...
                    .writerLevel(memberService.getMemberTierOrder(dto.writerPoint()))   // ★ 행마다!
                    .build()
    );
}
```

```java
// MemberService
public int getMemberTierOrder(int memberPoints) {
    return pointTierRepository.findTierOrderByPoints(memberPoints);      // SELECT 1회
}
```

**게시글 20개를 조회하면 티어 쿼리가 20번 실행됩니다.**

```
GET /api/boards?page=0&size=20
  ↓
① SELECT ... FROM board JOIN member ...       (프로젝션 조회 1회)
② SELECT t.tier_order FROM point_tier ...     ← 1번째 게시글 작성자
③ SELECT t.tier_order FROM point_tier ...     ← 2번째
④ ...                                          ← 20번째까지
  총 21번의 쿼리
```

## 5-2. 이것이 N+1 문제인가?

**엄밀히 말하면 다릅니다.**

| | 전형적인 N+1 | 이 경우 |
|---|---|---|
| 원인 | LAZY 연관관계를 반복 접근 | 반복문 안에서 리포지토리를 반복 호출 |
| 해결 | `JOIN FETCH`, `@EntityGraph` | 캐싱 또는 배치 조회 |

이 프로젝트는 **N+1을 피하려고 프로젝션 DTO를 잘 만들었는데**
(`BoardListProjectionDto`로 작성자 정보까지 한 번에 가져옴),
**티어 조회를 반복문에 넣어 같은 결과를 만들었습니다.** 아까운 부분입니다.

게다가 **20번의 쿼리가 대부분 동일한 결과**를 반환합니다.
포인트가 100인 사람이 5명이면 `findTierOrderByPoints(100)`을 5번 실행합니다.

## 5-3. 해결 — 메모리 캐싱

`point_tier` 테이블의 특성:

- 행이 **10개**뿐입니다
- **거의 절대 바뀌지 않습니다** (등급 정책 변경은 연 단위)
- 전체를 메모리에 올려도 수 KB입니다

**이런 데이터는 DB에 물어볼 필요가 없습니다.**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PointTierService {

    private final PointTierRepository pointTierRepository;

    /** 기동 시 한 번 로드해 메모리에 보관. tierOrder 내림차순으로 정렬해둔다. */
    private volatile List<PointTier> tiersDesc = List.of();

    @EventListener(ApplicationReadyEvent.class)
    public void loadTiers() {
        List<PointTier> loaded = pointTierRepository.findAllByOrderByTierOrderAsc();
        if (loaded.isEmpty()) {
            log.warn("[PointTierService] point_tier 테이블이 비어 있습니다.");
            return;
        }
        // 높은 등급부터 검사하도록 역순 정렬
        this.tiersDesc = loaded.stream()
                .sorted(Comparator.comparingInt(PointTier::getTierOrder).reversed())
                .toList();
        log.info("[PointTierService] 등급 {}건 캐싱 완료", tiersDesc.size());
    }

    /** 쿼리 0회 */
    public PointTier getTierByPoints(int points) {
        return tiersDesc.stream()
                .filter(t -> points >= t.getMinPoint())
                .findFirst()                                   // 내림차순이므로 첫 매치가 정답
                .orElseThrow(PointTierIsNotExistException::new);
    }

    /** 쿼리 0회 */
    public int getTierOrderByPoints(int points) {
        return tiersDesc.stream()
                .filter(t -> points >= t.getMinPoint())
                .findFirst()
                .map(PointTier::getTierOrder)
                .orElse(1);                                    // 음수 포인트 등은 최하위로 폴백
    }

    /** 등급 정책을 바꿨을 때 수동 갱신용 (관리자 API에서 호출) */
    public void reload() { loadTiers(); }
}
```

**`volatile`을 쓴 이유**
여러 요청 스레드가 `tiersDesc`를 읽고, `loadTiers()`가 다른 스레드에서 값을 바꿉니다.
`volatile`이 없으면 **한 스레드의 쓰기가 다른 스레드에 보이지 않을 수 있습니다**
(CPU 캐시에만 반영되고 메인 메모리에 늦게 반영되는 문제).

`volatile`은 "이 변수는 항상 메인 메모리에서 읽고 쓴다"를 보장합니다.
**참조 대입은 원자적**이고 `List.of()`/`toList()`가 만든 리스트는 불변이므로,
리스트 내용을 부분적으로 보는 일은 생기지 않습니다. → 락 없이 안전합니다.

**`maxPoint`를 검사하지 않는 이유**
내림차순으로 정렬한 뒤 `points >= minPoint`인 **첫 번째**를 취하면,
그것이 자동으로 올바른 등급입니다. `maxPoint`는 검사할 필요가 없습니다.
(구간 정의에 빈틈이 있어도 안전하게 동작합니다.)

## 5-4. 효과

```
[변경 전]  게시글 20개 조회 → 쿼리 21번
[변경 후]  게시글 20개 조회 → 쿼리 1번           (티어는 메모리에서 계산)
```

게시글 목록은 **가장 많이 호출되는 API**입니다. Redis 캐시가 붙어 있어도
캐시 MISS 시에는 이 쿼리들이 전부 실행됩니다.

> 참고로 `BoardService`가 이미 Redis로 목록 전체를 캐싱하고 있으므로
> 실제 부하는 캐시 MISS(3분마다 + 게시글 등록 시)에만 발생합니다.
> 그래도 **불필요한 쿼리 20개를 없애는 것**은 그 자체로 가치가 있고,
> `getBoardByWriter`(마이페이지)는 캐시가 없어서 매번 이 부하를 냅니다.

---

# 6. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🟠 1 | 목록 조회 시 티어 쿼리 N회 → 메모리 캐싱 필요 | [5장](#5--성능-문제--게시글-목록에서-쿼리가-n번-실행된다) |
| 🟠 2 | `point_tier` 초기 데이터를 넣는 코드가 없음 → 회원가입 불가 | [2-4](#2-4-이-테이블은-누가-채우나--아무도-채우지-않는다) |
| 🟠 3 | 음수 포인트 시 `IllegalStateException` → 500 | [2-2](#2-2-️-음수-포인트-대비가-없다) |
| 🟡 4 | `IllegalStateException` 대신 `PointTierIsNotExistException` 사용 | [4장](#4-pointtierservice--메서드-하나) |
| 🟡 5 | `int findTierOrderByPoints` → 결과 없으면 예외. `Integer`로 | [3-1](#3-1-과-의-차이--프로젝션) |
| 🟡 6 | `@NoArgsConstructor`가 `public` → `PROTECTED`로 | [2-3](#2-3-️-noargsconstructor가-public이다) |
| 🟡 7 | `@Transactional(readOnly = true)` 누락 | [4장](#4-pointtierservice--메서드-하나) |
| 🟢 8 | 빈 파일 3개 삭제 (`PointTierController`, DTO 2개) | [파일 지도](#파일-지도) |
| 🟢 9 | `findAllByOrderByTierOrderAsc()`를 쓰지 않고 자바에서 재정렬 | [3-2](#3-2-은-아무도-쓰지-않는다) |
| 🟢 10 | `TierType.getDisplayName()`이 미사용 | [1장](#1-tiertype--행성-테마-등급) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| `@Column(unique = true)` on `tierType` | 같은 등급이 두 번 생기는 것을 DB가 막음 |
| `tierOrder` 별도 필드 | enum 선언 순서에 의존하지 않고 정렬·비교 가능 |
| `findTierOrderByPoints` 스칼라 프로젝션 | 엔티티 전체를 로드하지 않는 올바른 최적화 |
| 행성 테마 `TierType` | 서비스 컨셉과 일관된 네이밍 |
| `maxPoint`를 `nullable`로 | 최상위 등급의 무한 상한을 표현 |

---

## 다음 문서

- [point.md](point.md) — 이 서비스를 호출하는 곳. 티어 갱신이 조용히 실패하는 이유
- [member.md](member.md) — `getMemberTierOrder` 오버로딩과 목록 조회 문제
- [community.md](community.md) — N번 호출의 실제 발생 지점
