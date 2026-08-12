# attendance — 출석 (트랜잭션 전파의 함정)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) ([4-4 프록시](../00-기초개념.md#4-4-프록시--spring-마법의-정체), [7-5 전파](../00-기초개념.md#7-5-전파propagation--트랜잭션-안에서-트랜잭션을-만나면)) · [point.md](point.md)
>
> 파일 6개, 코드 150줄 정도의 작은 도메인입니다.
> 그런데 **`Propagation.REQUIRES_NEW`를 세 번 쓰고 세 번 다 무효화되는** 매우 교육적인 사례입니다.
> [point.md](point.md)의 `private @Transactional` 문제와 **같은 원인, 다른 얼굴**입니다.

---

## 파일 지도

```
domain/attendance/
├── controller/AttendanceController.java      POST /api/attendance 하나
├── dto/response/AttendanceResponseDto.java
├── entity/Attendance.java
├── exception/AttendanceNotFoundException.java
├── repository/AttendanceRepository.java
└── service/AttendanceService.java            ★ 이 파일이 핵심
```

**기능**: 하루에 한 번 출석 체크 → 100포인트 지급. 같은 날 두 번 호출하면 포인트를 주지 않습니다.

---

# 1. `Attendance` 엔티티 — 유니크 제약이 핵심

```java
@Entity
@Getter
@NoArgsConstructor              // ⚠️ public (PROTECTED가 맞음)
@AllArgsConstructor
@Builder
@Table(name = "attendance", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"member_id", "attendance_date"})   // ★ 핵심
})
public class Attendance extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attendance_id")
    private Long attendanceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "attendance_date")           // ⚠️ nullable = false가 없다
    private LocalDate attendanceDate;
}
```

## 1-1. `@UniqueConstraint(member_id, attendance_date)`

**"한 회원은 하루에 한 번만 출석"이라는 규칙을 DB에 새겼습니다.**
[point.md](point.md#1-4-uniqueconstraintmember_id-version--동시성-방어선)의 `(member_id, version)`과 같은 발상입니다.

애플리케이션 코드로만 막으면 동시 요청에 뚫립니다:

```
      요청 A                          요청 B
t1  existsBy... → false
t2                                existsBy... → false     ← 둘 다 통과
t3  INSERT ✅
t4                                INSERT → UNIQUE 위반 💥 (제약이 있으므로 막힌다)
```

**제약이 없다면 출석 행이 2개 생기고 포인트를 200점 받습니다.**
버튼을 빠르게 두 번 누르는 것만으로 재현되는 흔한 버그입니다.

## 1-2. `LocalDate` vs `LocalDateTime`

```java
private LocalDate attendanceDate;      // 날짜만 (2026-08-11)
```

"하루 한 번"을 판정하려면 **시각은 방해가 됩니다.**
`LocalDateTime`이면 `2026-08-11 09:00`과 `2026-08-11 14:00`이 다른 값이라
유니크 제약이 아무것도 막지 못합니다.

| 타입 | 표현 | 이 프로젝트 사용처 |
|---|---|---|
| `LocalDate` | 날짜만 `2026-08-11` | `Attendance.attendanceDate` ✅ |
| `LocalTime` | 시각만 `14:30:00` | — |
| `LocalDateTime` | 날짜+시각 (타임존 없음) | `BaseTimeEntity`, `Point.date` 등 대부분 |
| `ZonedDateTime` / `Instant` | 타임존 포함 / UTC 절대시각 | 사용 안 함 |

**정확한 타입 선택입니다.**

> ⚠️ **타임존 문제는 남아 있습니다.** `LocalDate.now()`는 **JVM의 기본 타임존**을 씁니다.
> `docker-compose-prod.yml`에 `TZ=Asia/Seoul`이 설정되어 있어 지금은 맞지만,
> 그 설정이 빠지면 서버가 UTC로 돌아 **한국 시각 오전 9시 이전 출석이 전날로 기록**됩니다.
>
> ```java
> // 코드에서 명시하는 편이 안전
> private static final ZoneId KST = ZoneId.of("Asia/Seoul");
> LocalDate today = LocalDate.now(KST);
> ```

## 1-3. ⚠️ `nullable = false` 누락

```java
@Column(name = "attendance_date")       // nullable = false가 없다
private LocalDate attendanceDate;
```

`member_id`에는 `nullable = false`가 있는데 날짜에는 없습니다.
유니크 제약의 구성 컬럼이 `NULL`이 될 수 있으면 곤란합니다
(SQL 표준에서 `NULL`은 서로 같지 않다고 취급되므로 **유니크 제약이 무력화됩니다**).

```java
@Column(name = "attendance_date", nullable = false)
```

---

# 2. `AttendanceRepository`

```java
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    boolean existsByMemberIdAndAttendanceDate(Long memberId, LocalDate attendanceDate);

    Optional<Attendance> findByMemberIdAndAttendanceDate(Long memberId, LocalDate attendanceDate);
}
```

**`existsBy...`를 쓴 것이 잘한 부분입니다.**

```sql
-- existsBy → 존재만 확인
SELECT 1 FROM attendance WHERE member_id = ? AND attendance_date = ? LIMIT 1

-- findBy → 모든 컬럼 로드 + 엔티티 생성 + 영속성 컨텍스트 등록
SELECT * FROM attendance WHERE member_id = ? AND attendance_date = ?
```

`MemberService`가 중복 확인에 `findBy...ifPresent()`를 쓰는 것과 대비됩니다.
→ [member.md 2-3](member.md#2-3-개선--존재-확인은-existsby가-맞다)

> 참고: 리포지토리 파일 안에 이런 주석이 남아 있습니다.
> ```java
> // 특정 회원의 특정 날짜 출석 정보 조회                                     │ │
> ```
> 끝의 `│ │`는 터미널 UI에서 코드를 복사할 때 딸려온 테두리 문자입니다.
> 동작에는 영향이 없지만 지워야 합니다.

---

# 3. `AttendanceService` — 의도는 훌륭하고 구현은 무효

## 3-1. 설계 의도 읽기

```java
@Transactional
public AttendanceResponseDto createAttendance(PrincipalDetails principalDetails) {
    Long memberId = principalDetails.getMember().getId();

    // 1단계: 출석 처리 (핵심 비즈니스 로직)
    AttendanceResponseDto attendanceResult = processAttendanceRecord(principalDetails, memberId);

    // 2단계: 포인트 지급 (별도 트랜잭션 - 실패해도 출석은 유지)
    if (attendanceResult.isNewAttendance()) {
        processPointRewardInSeparateTransaction(principalDetails);
    }

    return attendanceResult;
}
```

주석에 의도가 정확히 적혀 있습니다:

> **"포인트 지급이 실패해도 출석 기록은 남아야 한다"**

이것은 **올바른 비즈니스 판단**입니다. 출석은 사용자가 한 행위이고,
포인트는 그에 대한 보상입니다. 보상 시스템에 문제가 생겼다고 출석을 취소하면 안 됩니다.

그래서 세 메서드에 `REQUIRES_NEW`를 붙였습니다.

```java
@Transactional
public AttendanceResponseDto processAttendanceRecord(...) { ... }

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void processPointRewardInSeparateTransaction(...) { ... }

@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
public AttendanceResponseDto getExistingAttendanceInNewTransaction(...) { ... }
```

**`public`으로 만들었으니 `PointService`의 `private` 실수는 피했습니다.**
그런데도 동작하지 않습니다.

## 3-2. 🔴 왜 무효인가 — self-invocation

```java
@Transactional                                          // 트랜잭션 T 시작
public AttendanceResponseDto createAttendance(...) {

    processAttendanceRecord(principalDetails, memberId);
    //    ↑ 이것은 this.processAttendanceRecord(...)
    //      = 원본 객체 내부에서의 직접 호출
    //      = 프록시를 거치지 않음
    //      → @Transactional 무시, T에 그냥 얹혀서 실행

    processPointRewardInSeparateTransaction(principalDetails);
    //    ↑ 역시 this.xxx()
    //      → REQUIRES_NEW 무시! 새 트랜잭션이 만들어지지 않고 T에서 실행
}
```

**프록시가 개입할 기회는 "외부에서 빈의 메서드를 호출할 때" 딱 한 번입니다.**

```
[컨트롤러]
attendanceService.createAttendance(...)
        ↓
   ┌────────────────────────────────────────┐
   │ 프록시                                  │  ← 여기서만 가로챈다
   │  tx.begin();          ← T 시작          │
   │  super.createAttendance(...)            │
   └──────────────┬─────────────────────────┘
                  ↓
   ┌──────────────────────────────────────────────────┐
   │ 원본 AttendanceService 객체 내부                    │
   │                                                   │
   │  this.processAttendanceRecord()           ← 프록시 없음 │
   │  this.processPointRewardInSeparateTx()    ← 프록시 없음 │
   │  this.getExistingAttendanceInNewTx()      ← 프록시 없음 │
   │                                                   │
   │  → 세 개 모두 T 안에서 실행. 어노테이션 전부 무시.       │
   └──────────────────────────────────────────────────┘
                  ↓
   tx.commit();  ← T 하나만 커밋
```

**결과: 트랜잭션이 하나뿐입니다.** 의도한 "출석과 포인트 분리"가 이루어지지 않습니다.

## 3-3. 이 버그의 실제 결과

### 결과 ① 포인트 지급이 실패하면 출석도 롤백된다 — 의도와 정반대

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)   // 무시됨 → T에서 실행
public void processPointRewardInSeparateTransaction(PrincipalDetails principalDetails) {
    try {
        pointService.createPoint(pointRequestDto, principalDetails);
    } catch (Exception e) {
        log.error("출석 포인트 지급 실패 ...");           // ★ 예외를 잡아서 삼킨다
        try {
            Thread.sleep(1000);
            pointService.createPoint(pointRequestDto, principalDetails);   // 1회 재시도
        } catch (Exception retryException) {
            log.error("포인트 재시도도 실패 ...");         // 최종 실패도 삼킨다
        }
    }
}
```

예외를 `catch`로 삼키니 출석이 롤백되지 않을 것처럼 보입니다. **하지만 아닙니다.**

```
T 시작
  ↓
attendanceRepository.save(newAttendance)          → T에 INSERT 등록
  ↓
pointService.createPoint() → 내부에서 예외 발생
  → ★ 트랜잭션 T가 "rollback-only"로 마킹됨
  ↓
catch로 예외를 삼킴 (하지만 마킹은 지워지지 않는다)
  ↓
createAttendance 정상 종료 → 프록시가 commit() 시도
  ↓
스프링: "이 트랜잭션은 rollback-only인데 커밋하라고?"
  → UnexpectedRollbackException 발생
  → 전체 롤백. 출석 기록도 사라짐. 사용자는 500 응답.
```

**의도한 것과 정확히 반대의 결과입니다.**
"포인트가 실패해도 출석은 남기려" 했는데, **포인트가 실패하면 출석도 사라집니다.**

> **핵심 규칙**: `REQUIRED`(기본)로 참여한 내부 트랜잭션에서 예외가 나면,
> 바깥에서 `catch`로 잡아도 트랜잭션은 되살아나지 않습니다.
> **"예외를 삼켜서 부분 성공을 만들려면 반드시 별도 트랜잭션이어야 합니다."**
> → [기초개념 7-5](../00-기초개념.md#7-5-전파propagation--트랜잭션-안에서-트랜잭션을-만나면)

### 결과 ② 재시도가 동작할 수 없다

```java
Thread.sleep(1000);
pointService.createPoint(pointRequestDto, principalDetails);   // 재시도
```

첫 시도에서 이미 T가 rollback-only이므로, 1초를 기다려도 같은 T에서는 성공할 수 없습니다.
게다가 **`@Transactional` 메서드 안에서 `Thread.sleep(1000)`은 그 자체로 나쁩니다.**
DB 커넥션과 트랜잭션을 1초 동안 붙잡고 있습니다. 커넥션 풀이 30개인데
동시 출석 요청이 30건이면 **다른 모든 요청이 커넥션을 못 받습니다.**

### 결과 ③ `getExistingAttendanceInNewTransaction`의 이름이 거짓말이 된다

```java
/**
 * 새로운 트랜잭션으로 기존 출석 정보를 안전하게 조회
 * 트랜잭션 타이밍 이슈로 인한 RuntimeException 방지
 */
@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
public AttendanceResponseDto getExistingAttendanceInNewTransaction(Long memberId, LocalDate date) {
    Attendance attendance = attendanceRepository.findByMemberIdAndAttendanceDate(memberId, date)
            .orElseThrow(AttendanceNotFoundException::new);
    return AttendanceResponseDto.fromEntity(attendance, false);
}
```

메서드 이름에 `InNewTransaction`이 들어 있는데 **새 트랜잭션이 아닙니다.**

> **교훈: 메서드 이름은 검증되지 않습니다.**
> `REQUIRES_NEW`가 실제로 동작하는지 확인하려면 로그로 트랜잭션 경계를 봐야 합니다.
>
> ```yaml
> logging.level.org.springframework.transaction.interceptor: TRACE
> ```
> 이 설정을 켜면 트랜잭션이 몇 개 열리고 어디서 참여(participating)하는지 전부 찍힙니다.
> **`REQUIRES_NEW`를 쓸 때는 반드시 이걸로 확인하세요.**

## 3-4. 🟠 또 하나 — `catch (DataIntegrityViolationException)`도 동작하지 않는다

```java
try {
    if (!attendanceRepository.existsByMemberIdAndAttendanceDate(member.getId(), today)) {
        Attendance newAttendance = Attendance.builder()...build();
        attendanceRepository.save(newAttendance);
        return AttendanceResponseDto.fromEntity(newAttendance, true);
    }
} catch (DataIntegrityViolationException e) {
    // 동시 요청으로 인한 중복 출석 시도 - 정상 처리
    return getExistingAttendanceInNewTransaction(member.getId(), today);   // ⚠️
}
```

동시 요청으로 UNIQUE 위반이 나면 우아하게 처리하려는 의도입니다. **좋은 생각입니다.**
하지만 여기서도 같은 문제가 있습니다:

1. `catch`에서 호출하는 `getExistingAttendanceInNewTransaction`이 **같은 T에서 실행**됩니다
2. T는 이미 rollback-only이므로 **조회 자체는 되지만 최종 커밋에서 `UnexpectedRollbackException`** 이 납니다
3. 즉 "우아한 처리"가 500 에러가 됩니다

## 3-5. 🟡 죽은 코드 — `orElseThrow` 뒤의 `null` 검사

```java
Member member = memberRepository.findById(memberId)
        .orElseThrow(() -> new UserNotFoundException());

if (member == null) {                          // ⚠️ 절대 실행되지 않음
    throw new UserInvalidAccessException();
}
```

`orElseThrow`는 값이 없으면 **예외를 던지고**, 있으면 **`null`이 아닌 값을 반환**합니다.
따라서 `member`는 절대 `null`일 수 없습니다.

`Optional`을 쓰는 목적 자체가 이 `null` 검사를 없애는 것입니다.
→ [기초개념 1-7](../00-기초개념.md#1-7-optionalt--값이-없을-수-있음을-타입으로-표현)

## 3-6. 🟡 하드코딩된 포인트 100

```java
// AttendanceService
PointRequestDto pointRequestDto = PointRequestDto.builder()
        .amount(100)                                   // ← 여기
        .type(PointType.ATTENDANCE)
        .build();
```

```java
// AttendanceResponseDto — 같은 값이 또 등장
public static AttendanceResponseDto fromEntity(Attendance attendance, boolean isNew) {
    return new AttendanceResponseDto(
            ...,
            isNew ? 100 : 0                            // ← 여기도
    );
}
```

**같은 상수가 두 곳에 하드코딩되어 있습니다.** 한쪽만 고치면 "100점 받았습니다"라고
표시하면서 실제로는 50점을 주는 상황이 생깁니다.

게다가 `AttendanceResponseDto`는 **실제 지급 결과가 아니라 "지급될 예정 금액"을 반환**합니다.
포인트 지급이 실패해도 응답은 `pointsEarned: 100`입니다. **거짓 응답입니다.**

```java
// 상수를 한 곳에 (더 나아가면 설정 파일로)
public class AttendanceService {
    private static final int ATTENDANCE_POINT = 100;
}

// 또는 application.yml
attendance:
  reward-point: 100
```

---

# 4. 고친 코드

## 4-1. 트랜잭션 단위를 별도 빈으로 분리

```java
package com.gaebang.backend.domain.attendance.service;

/** 출석 기록만 담당하는 트랜잭션 단위 */
@Service
@RequiredArgsConstructor
public class AttendanceRecordService {

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;

    /**
     * 출석 기록을 남긴다. 이미 출석했으면 isNewAttendance = false로 반환.
     * REQUIRES_NEW: 포인트 지급 실패와 무관하게 이 트랜잭션은 독립적으로 커밋되어야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttendanceResult record(Long memberId, LocalDate today) {

        if (attendanceRepository.existsByMemberIdAndAttendanceDate(memberId, today)) {
            return AttendanceResult.alreadyChecked(
                    attendanceRepository.findByMemberIdAndAttendanceDate(memberId, today)
                            .orElseThrow(AttendanceNotFoundException::new));
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(UserNotFoundException::new);

        Attendance saved = attendanceRepository.save(
                Attendance.builder().member(member).attendanceDate(today).build());

        return AttendanceResult.newlyChecked(saved);
    }
}
```

## 4-2. 동시 중복은 별도 트랜잭션에서 복구

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final int ATTENDANCE_POINT = 100;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AttendanceRecordService attendanceRecordService;   // ★ 다른 빈 → 프록시 경유 ✅
    private final AttendanceRepository attendanceRepository;
    private final PointService pointService;

    /**
     * 트랜잭션을 걸지 않는다 — 출석 기록과 포인트 지급이 각각 독립 트랜잭션이어야 하므로.
     * 여기에 @Transactional을 걸면 REQUIRES_NEW가 의미를 잃는다.
     */
    public AttendanceResponseDto createAttendance(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();
        LocalDate today = LocalDate.now(KST);

        AttendanceResult result;
        try {
            result = attendanceRecordService.record(memberId, today);      // 트랜잭션 1 (커밋 완료)

        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 UNIQUE 충돌 → 상대 요청이 이미 기록했다는 뜻이므로 정상 처리
            log.info("동시 출석 요청 감지 - 회원ID: {}, 날짜: {}", memberId, today);
            return AttendanceResponseDto.of(
                    attendanceRepository.findByMemberIdAndAttendanceDate(memberId, today)
                            .orElseThrow(AttendanceNotFoundException::new),
                    false, 0);
        }

        if (!result.isNew()) {
            return AttendanceResponseDto.of(result.attendance(), false, 0);
        }

        // 트랜잭션 2 (독립) — 실패해도 위의 출석 기록은 이미 커밋되어 남는다 ✅
        int earned = rewardPoint(principalDetails, memberId);

        return AttendanceResponseDto.of(result.attendance(), true, earned);
    }

    /** 포인트 지급. 실패하면 0을 반환하고 출석은 유지한다. */
    private int rewardPoint(PrincipalDetails principalDetails, Long memberId) {
        PointRequestDto request = PointRequestDto.builder()
                .amount(ATTENDANCE_POINT)
                .type(PointType.ATTENDANCE)
                .build();
        try {
            pointService.createPoint(request, principalDetails);     // 내부에서 REQUIRES_NEW + 재시도
            return ATTENDANCE_POINT;

        } catch (Exception e) {
            // PointService가 이미 3회 재시도하므로 여기서 또 재시도하지 않는다
            log.error("출석 포인트 지급 최종 실패 - 회원ID: {}. 보정 배치 대상.", memberId, e);
            return 0;                                               // 실제 결과를 정직하게 반환
        }
    }
}
```

## 4-3. 응답 DTO가 실제 결과를 말하게 만들기

```java
public record AttendanceResponseDto(
        Long attendanceId,
        Long memberId,
        String attendanceDate,
        boolean isNewAttendance,
        Integer pointsEarned          // "예정 금액"이 아니라 "실제 지급된 금액"
) {
    public static AttendanceResponseDto of(Attendance attendance, boolean isNew, int earned) {
        return new AttendanceResponseDto(
                attendance.getAttendanceId(),
                attendance.getMember().getId(),
                attendance.getAttendanceDate().toString(),      // ISO-8601 "2026-08-11"
                isNew,
                earned
        );
    }
}
```

**변경점 2가지**

1. `pointsEarned`를 **인자로 받습니다.** 지급이 실패하면 `0`이 들어갑니다.
   프론트엔드가 "100포인트 획득!"이라고 거짓 표시하지 않게 됩니다.
2. 날짜 포맷을 `DateTimeFormatter.ofPattern("yyyy-MM-dd")` 대신 `toString()`으로 바꿨습니다.
   `LocalDate.toString()`은 이미 ISO-8601(`2026-08-11`)을 반환하므로 포매터가 불필요합니다.

## 4-4. 재시도 책임을 한 곳에 모으기

원래 코드는 **두 계층이 각각 재시도**합니다:

```
AttendanceService     → 1회 재시도 (Thread.sleep(1000))
     ↓
PointService          → 3회 재시도 (Thread.sleep(50~150))
```

최악의 경우 **4번 시도하고 1.15초를 기다립니다.** 그리고 어느 계층이 실패를 책임지는지 불분명합니다.

**원칙: 재시도는 가장 가까운 계층에서 한 번만.**
`PointService`가 이미 동시성 충돌을 재시도하므로, `AttendanceService`는
**실패를 기록하고 넘어가면** 됩니다.

---

# 5. `AttendanceController` — 응답 상태 코드 이야기

```java
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    @PostMapping()                       // ⚠️ 빈 괄호 — @PostMapping이면 충분
    public ResponseEntity<ResponseDTO<AttendanceResponseDto>> addAttendance(
            @AuthenticationPrincipal PrincipalDetails principalDetails) {
        AttendanceResponseDto dto = attendanceService.createAttendance(principalDetails);
        ResponseDTO<AttendanceResponseDto> response = ResponseDTO.okWithData(dto);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
```

`/api/attendance`는 `permitAll` 목록에 없으므로 **인증이 필요합니다.** 올바릅니다.

**`POST`를 쓴 것도 맞습니다.** 출석은 서버 상태를 바꾸는 동작이므로 `GET`이면 안 됩니다
(브라우저나 프록시가 `GET`을 임의로 재시도·캐시할 수 있습니다).

> 다만 REST 관점에서 "이미 출석함"은 `200`보다 `409 Conflict`가 어울립니다.
> 지금은 `isNewAttendance: false`로 구분하니 프론트가 처리할 수 있어 실용적으로는 문제없습니다.

---

# 6. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | `REQUIRES_NEW` 3개가 self-invocation으로 전부 무효 → 포인트 실패 시 출석도 롤백 | [3-2](#3-2--왜-무효인가--self-invocation), [3-3](#결과--포인트-지급이-실패하면-출석도-롤백된다--의도와-정반대) |
| 🔴 2 | `catch (DataIntegrityViolationException)`이 `UnexpectedRollbackException`으로 귀결 | [3-4](#3-4--또-하나--catch-dataintegrityviolationexception도-동작하지-않는다) |
| 🟠 3 | `@Transactional` 안에서 `Thread.sleep(1000)` → 커넥션 점유 | [3-3 결과②](#결과--재시도가-동작할-수-없다) |
| 🟠 4 | 응답의 `pointsEarned`가 실제 지급 결과와 무관 (항상 100) | [3-6](#3-6--하드코딩된-포인트-100) |
| 🟠 5 | 재시도가 두 계층에 중복 (최대 4회, 1.15초) | [4-4](#4-4-재시도-책임을-한-곳에-모으기) |
| 🟡 6 | `LocalDate.now()`가 JVM 타임존 의존 → `ZoneId` 명시 | [1-2](#1-2-localdate-vs-localdatetime) |
| 🟡 7 | `attendance_date`에 `nullable = false` 누락 | [1-3](#1-3-️-nullable--false-누락) |
| 🟡 8 | 포인트 100이 두 곳에 하드코딩 | [3-6](#3-6--하드코딩된-포인트-100) |
| 🟢 9 | `orElseThrow` 뒤의 `null` 검사 (죽은 코드) | [3-5](#3-5--죽은-코드--orelsethrow-뒤의-null-검사) |
| 🟢 10 | `@NoArgsConstructor`가 `public` | [1장](#1-attendance-엔티티--유니크-제약이-핵심) |
| 🟢 11 | 리포지토리 주석에 터미널 테두리 문자(`│ │`) 잔존 | [2장](#2-attendancerepository) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| `@UniqueConstraint(member_id, attendance_date)` | "하루 한 번"을 DB가 보장. 동시 요청 방어 |
| `LocalDate` 선택 | 시각을 배제해 유니크 제약이 의미를 갖게 함 |
| `existsBy...` 사용 | 존재 확인에 엔티티를 로드하지 않음 |
| 출석과 포인트를 분리하려는 **의도** | 비즈니스 판단이 정확하고 주석으로 설명까지 함 |
| `DataIntegrityViolationException`을 정상 흐름으로 처리하려는 **의도** | 동시성을 예외가 아닌 예상 상황으로 봄 |
| `POST` 메서드 + 인증 필요 | HTTP 의미론과 보안 모두 올바름 |

**이 도메인은 "생각은 맞았는데 프레임워크 동작을 몰라서 무효가 된" 전형적인 사례입니다.**
[point.md](point.md)와 함께 읽으면 **프록시 기반 AOP의 제약**이 확실히 각인됩니다.

---

## 다음 문서

- [point.md](point.md) — 같은 원인(프록시)의 다른 얼굴(`private`)
- [email.md](email.md) — 싱글턴 빈의 상태 관리 문제
- [../00-기초개념.md#4-4-프록시--spring-마법의-정체](../00-기초개념.md#4-4-프록시--spring-마법의-정체) — 원리 복습
