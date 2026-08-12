# global — 모든 도메인이 깔고 앉은 바닥

> 선행 문서: [00-기초개념.md](00-기초개념.md)
> 이 폴더는 **특정 도메인에 속하지 않는 공통 관심사**를 모아둔 곳입니다.
> 인증, 예외 처리, 응답 형식, 외부 인프라(S3·Redis·메일) 설정이 여기 있습니다.
> 도메인 문서를 읽기 전에 이 문서를 먼저 읽어야 합니다. **모든 도메인이 여기에 의존합니다.**

---

## 폴더 지도

```
global/
├── config/                     스프링 빈 설정 모음
│   ├── AppConfig.java              BCryptPasswordEncoder, ObjectMapper
│   ├── JpaConfig.java              @EnableJpaAuditing (createdAt/updatedAt 자동화)
│   ├── AsyncConfig.java            비동기 스레드풀 3개 + @EnableAsync
│   ├── HttpClientConfig.java       RestTemplate, RestClient(커넥션 풀)
│   ├── EmailConfig.java            Gmail SMTP JavaMailSender
│   └── AiMemberInitializer.java    기동 시 AI 봇 계정 자동 생성
│
├── entity/
│   └── BaseTimeEntity.java     모든 엔티티의 createdAt/updatedAt 부모 클래스
│
├── exception/                  예외 처리 3층 구조
│   ├── ErrorCode.java              에러코드 + HTTP상태 + 메시지 enum
│   ├── ApplicationException.java   커스텀 예외의 최상위 부모
│   ├── PermissionDeniedException.java
│   ├── S3Exception.java
│   └── GlobalExceptionRestAdvice.java   전역 예외 → JSON 응답 변환
│
├── util/
│   ├── ResponseDTO.java        모든 API 응답의 껍데기 (제네릭)
│   ├── CustomResponseUtil.java 필터 단계에서 직접 JSON 쓰기
│   ├── DataFormatter.java      날짜 포맷 유틸
│   ├── NicknameGenerator.java  랜덤 한글 닉네임 생성
│   └── S3/                     AWS S3 이미지 업로드
│       ├── S3Config.java
│       ├── S3ImageService.java
│       ├── S3Controller.java
│       └── Base64DecodedMultipartFile.java
│
├── jwt/                        JWT 발급·검증
│   ├── JwtProperties.java          만료시간 상수
│   ├── JwtKey.java                 비밀키 3개 로테이션
│   ├── JwtProvider.java            토큰 생성/파싱
│   ├── SigningKeyResolver.java     kid로 검증 키 찾기
│   ├── JwtAuthenticationFilter.java  로그인 처리 (인증)
│   └── JwtAuthorizationFilter.java   요청마다 토큰 검사 (인가)
│
├── springsecurity/             Spring Security 설정 + OAuth2
│   ├── SpringSecurityConfig.java   필터체인·CORS·URL 권한
│   ├── AuthConfig.java             AuthenticationProvider, UserDetailsService
│   ├── PrincipalDetails.java       Member를 감싼 인증 주체
│   ├── ApplicationAuditAware.java  JPA Auditing용 현재 사용자 제공
│   ├── OAuth2UserInfo.java         소셜 제공자 응답 통일 인터페이스
│   ├── GoogleUserInfo / KakaoUserInfo / NaverUserInfo
│   ├── PrincipalOauth2UserService.java  소셜 로그인 후 회원 조회/생성
│   └── OAuth2LoginSuccessHandler.java   소셜 로그인 성공 → 프론트로 리다이렉트
│
└── infrastructure/redis/       Redis 캐시
    ├── config/RedisConfig.java     LettuceConnectionFactory, RedisTemplate
    └── cache/
        ├── CacheVersion.java       캐시 버전 카운터
        ├── Keys.java               캐시 키 생성 규칙
        └── PageResponse.java       Page를 직렬화 가능한 형태로 변환
```

---

# 1. config — 빈 설정

## 1-1. `AppConfig` — 내가 만들지 않은 클래스를 빈으로 만들기

```java
@Configuration
public class AppConfig {

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }
}
```

**왜 `@Component`를 안 쓰고 `@Bean`을 쓰는가?**
`BCryptPasswordEncoder`와 `ObjectMapper`는 **라이브러리 클래스**입니다. 내 소스가 아니니
어노테이션을 붙일 수 없습니다. 이럴 때 `@Configuration` + `@Bean`으로 등록합니다.
→ [기초개념 4-2](00-기초개념.md#4-2-빈으로-등록하는-방법-2가지)

**`BCryptPasswordEncoder`가 하는 일**

```java
String encoded = encoder.encode("mypassword");
// → $2a$10$N9qo8uLOickgx2ZMRZoMye... (매번 다른 값!)

encoder.matches("mypassword", encoded);   // true
```

BCrypt는 **매번 다른 salt를 섞어 해싱**하므로 같은 비밀번호도 결과가 다릅니다.
그래서 비교는 `equals`가 아니라 반드시 `matches()`로 해야 합니다.
또 BCrypt는 **의도적으로 느린** 알고리즘입니다(기본 10라운드 ≈ 100ms). 무차별 대입 공격을 늦추기 위함입니다.

**`ObjectMapper` 설정 2가지의 의미**

| 설정 | 효과 |
|---|---|
| `NON_NULL` | `null`인 필드를 JSON에서 **아예 빼버립니다**. `ResponseDTO`의 `data`가 없을 때 `"data": null`이 안 나옵니다. |
| `JavaTimeModule` | `LocalDateTime` 직렬화를 가능하게 합니다. **이게 없으면 `LocalDateTime` 필드를 담은 응답에서 예외가 납니다.** |

> Jackson은 Java 8 날짜/시간(`java.time`)을 기본으로 모릅니다. 별도 모듈을 등록해야 합니다.
> Spring Boot의 자동 설정 `ObjectMapper`는 이미 등록해주는데, 이 프로젝트처럼
> **직접 `@Bean`으로 새로 만들면 자동 설정이 밀려나므로** 직접 등록해야 합니다. 그래서 이 줄이 있습니다.

## 1-2. `JpaConfig` — 딱 한 줄인데 없으면 시간이 안 들어간다

```java
@Configuration
@EnableJpaAuditing
public class JpaConfig { }
```

`BaseTimeEntity`의 `@CreatedDate`, `@LastModifiedDate`는 **이 어노테이션이 켜져 있어야 동작합니다.**
없으면 `createdAt`이 계속 `null`로 저장됩니다.

> `MorningstarApplication`에 `import ...EnableJpaAuditing;`이 남아 있지만 실제 사용은 없습니다.
> 예전에 거기 붙였다가 `JpaConfig`로 옮긴 흔적입니다. (`@EnableJpaAuditing`을 두 곳에 붙이면 안 됩니다.)

## 1-3. `AsyncConfig` — 비동기 작업을 어느 스레드에서 돌릴 것인가

```java
@Slf4j
@Configuration
@EnableAsync                      // @Async 어노테이션 활성화
@EnableTransactionManagement      // @Transactional 활성화 (스프링부트에선 사실 자동)
public class AsyncConfig implements AsyncConfigurer {
```

**스레드풀 3개를 나눠 만든 이유**

| 빈 이름 | 용도 | Core / Max / Queue | 거부 정책 |
|---|---|---|---|
| `taskExecutor` (기본) | 뉴스 수집 (`News-Async-`) | 3 / 10 / 100 | `CallerRunsPolicy` |
| `moderationExecutor` | 게시글 AI 검열 (`Moderation-`) | 5 / 10 / 100 | 로그만 남기고 버림 |
| `aiBotExecutor` | AI 봇 자동 답변 (`AiBot-`) | 3 / 8 / 50 | 로그만 남기고 버림 |

```java
@Async("moderationExecutor")     // 어느 풀에서 돌릴지 이름으로 지정
public void moderateAsync(Long boardId) { ... }
```

**왜 나누는가?** 하나의 풀을 공유하면 **느린 AI 호출이 큐를 가득 채워 다른 작업을 굶깁니다.**
격리(bulkhead) 패턴입니다. 스레드 이름 접두사(`setThreadNamePrefix`)를 다르게 준 것도
**로그에서 어느 작업의 스레드인지 바로 보이게** 하기 위함입니다.

**`ThreadPoolTaskExecutor`의 동작 순서** — 헷갈리기 쉬운 부분입니다:

```
작업 도착
  ↓
① 실행 중 스레드 < corePoolSize?  → 새 스레드를 만들어 실행
  ↓ 아니오
② 큐에 자리가 있나?               → 큐에 넣고 대기          ← 여기가 먼저!
  ↓ 아니오 (큐가 꽉 찼다)
③ 스레드 < maxPoolSize?           → 새 스레드를 만들어 실행
  ↓ 아니오
④ 거부 정책(RejectedExecutionHandler) 실행
```

**즉 큐가 꽉 차기 전까지는 maxPoolSize까지 늘어나지 않습니다.**
`queueCapacity`를 100으로 크게 잡으면 maxPoolSize 10은 사실상 쓰이지 않습니다.

**거부 정책의 차이**

```java
// 기본 executor: 거부되면 "호출한 스레드"가 직접 실행한다 → 작업을 잃지 않지만 호출자가 느려진다
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

// 검열/AI봇: 로그만 남기고 조용히 버린다 → 부가 기능이므로 유실을 감수
executor.setRejectedExecutionHandler((runnable, pool) -> log.warn("거부됨 - 큐: {}", ...));
```

**`getAsyncUncaughtExceptionHandler`가 필요한 이유**

```java
@Override
public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (throwable, method, params) -> log.error("비동기 예외 - {}", method.getName(), throwable);
}
```

`@Async void` 메서드에서 예외가 터지면 **호출자에게 전달될 방법이 없습니다**(이미 리턴했으므로).
이 핸들러가 없으면 예외가 **아무 로그도 없이 사라집니다.** 반드시 등록해야 하는 안전장치입니다.
(반환 타입이 `Future`/`CompletableFuture`면 예외가 그 안에 담겨 전달됩니다.)

## 1-4. `HttpClientConfig` — 외부 API를 부르는 두 가지 도구

```java
@Bean public RestTemplate restTemplate()   // Payment 도메인에서 사용 (구식, 유지보수 모드)
@Bean public RestClient restClient()       // Question 도메인에서 사용 (Spring 6.1+ 신규)
```

`RestClient`에는 세심한 설정이 들어가 있습니다:

```java
PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(100);              // 전체 동시 연결 100개
cm.setDefaultMaxPerRoute(20);     // 목적지(호스트)당 20개

RequestConfig rc = RequestConfig.custom()
    .setConnectionRequestTimeout(Timeout.ofSeconds(30))  // 풀에서 연결을 못 받으면 30초 후 포기
    .setResponseTimeout(Timeout.ofSeconds(600))          // 응답 대기 10분
    .build();
```

**커넥션 풀이 필요한 이유**: TCP 연결과 TLS 핸드셰이크는 비쌉니다(수십~수백 ms).
매 요청마다 새로 맺으면 낭비이므로 연결을 재사용합니다.

**타임아웃 3종을 구분하세요** — 면접에서 자주 묻습니다:

| 타임아웃 | 의미 |
|---|---|
| Connection Request Timeout | **풀에서 커넥션을 빌려올 때까지** 기다리는 시간 |
| Connect Timeout | TCP 연결 수립까지의 시간 |
| Response(Read) Timeout | 연결 후 **응답 데이터를 기다리는** 시간 |

응답 타임아웃 **600초(10분)** 는 매우 긴 값입니다. 이미지 생성 API 때문이라고 주석에 적혀 있지만,
이 설정이 `RestClient` 전체에 적용되므로 다른 API 호출도 10분간 스레드를 붙잡을 수 있습니다.
**용도별로 `RestClient`를 나누는 것이 안전합니다.**

## 1-5. `EmailConfig` — Gmail SMTP

```java
private static Dotenv dotenv = Dotenv.load();                    // ⚠️ 1-7 참조
private static final String TEST_ID = dotenv.get("TEST_ID");

@Bean
public JavaMailSender javaMailSender() {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost("smtp.gmail.com");
    sender.setPort(587);                          // 587 = STARTTLS, 465 = SSL
    sender.setUsername(TEST_ID);
    sender.setPassword(TEST_ID_PASSWORD);         // Gmail 앱 비밀번호 (일반 비번 아님)
    sender.setDefaultEncoding("utf-8");           // 없으면 한글 제목이 깨진다
    sender.setJavaMailProperties(getMailProperties());
    return sender;
}
```

```java
properties.setProperty("mail.smtp.auth", "true");
properties.setProperty("mail.smtp.starttls.enable", "true");   // 평문 연결 → TLS 승격
properties.setProperty("mail.debug", "true");                  // ⚠️ SMTP 통신 전체를 로그에 출력
```

> `mail.debug=true`는 **운영에서 꺼야 합니다.** SMTP 대화 내용(수신자, 헤더 등)이 전부 로그로 나갑니다.

## 1-6. `AiMemberInitializer` — 기동 시 한 번 실행되는 초기화

```java
@Component
@RequiredArgsConstructor
public class AiMemberInitializer {

    private static final Long AI_BOT_MEMBER_ID = 999L;

    @EventListener(ApplicationReadyEvent.class)     // 애플리케이션이 완전히 뜬 직후 1회
    @Transactional
    public void initializeAiMember() {
        if (memberRepository.existsById(AI_BOT_MEMBER_ID)) return;   // 멱등성 보장
        ...
        Member aiMember = Member.builder()
                .email("ai-assistant@morningstar.com")
                .nickname("AI어시스턴트")
                .password("")                        // 로그인 불가 계정
                .authority("ROLE_BOT")
                .provider("SYSTEM")
                .currentTier(defaultTier)
                .build();
        memberRepository.save(aiMember);
    }
}
```

**배울 점 3가지**

1. **`ApplicationReadyEvent`**: 모든 빈 생성과 초기화가 끝난 뒤 발생합니다.
   `@PostConstruct`는 그 빈만 준비된 시점이라 DB 접근이 이를 수 있는데, 이 이벤트는 안전합니다.
2. **멱등성(idempotency)**: `existsById`로 먼저 확인하므로 재기동해도 중복 생성되지 않습니다.
   초기화 코드는 반드시 이렇게 짜야 합니다.
3. **예외를 삼킴**: `try/catch`로 잡고 로그만 남깁니다. **AI 계정 생성 실패로 서버가 안 뜨면 안 되므로**
   의도적인 선택입니다.

> ⚠️ 다만 `AI_BOT_MEMBER_ID = 999L`로 ID를 **가정**하는데, 실제 ID는 AUTO_INCREMENT가 정합니다.
> `existsById(999L)`는 "999번 회원이 있는가"를 묻지만 생성되는 회원의 ID는 999가 아닐 수 있습니다.
> 결과적으로 **매 기동마다 AI 계정이 하나씩 늘어날 수 있습니다.** 이메일로 조회하는 것이 옳습니다.
>
> ```java
> if (memberRepository.findByMemberBaseEmail("ai-assistant@morningstar.com").isPresent()) return;
> ```

## 1-7. 공통 문제 — `Dotenv.load()`를 static 필드에서 호출

`JwtKey`, `S3Config`, `EmailConfig`, `S3ImageService`, `RecruitmentService` 5곳이 같은 패턴입니다.

```java
private static Dotenv dotenv = Dotenv.load();
private static final String JWT_SECRET_KEY1 = dotenv.get("JWT_SECRET_KEY1");
```

**문제점**

1. **Spring 설정 체계를 우회합니다.** `application.yml`에는 이미
   `spring.config.import: optional:file:.env[.properties]`가 있어 `.env`가 프로퍼티로 로드됩니다.
   즉 `@Value("${JWT_SECRET_KEY1}")`로 읽을 수 있는데 별도 경로를 하나 더 만든 것입니다.
2. **`.env` 파일이 없으면 클래스 로딩 시점에 죽습니다.** `Dotenv.load()`는 파일이 없으면 예외를 던지고,
   static 초기화 중 예외는 `ExceptionInInitializerError`가 됩니다. → [기초개념 1-2](00-기초개념.md#1-2-static--클래스에-붙는-것-vs-객체에-붙는-것)
3. **테스트가 불가능해집니다.** static 필드는 테스트에서 갈아끼울 수 없습니다.
   `@SpringBootTest` 하나뿐인 테스트가 사실상 못 도는 이유 중 하나입니다.
4. **프로필별 분리가 안 됩니다.** dev/prod에서 다른 값을 쓸 수 없습니다.

**올바른 형태**

```java
@Component
public class JwtKeyProvider {
    private final Map<String, String> keys;

    public JwtKeyProvider(
        @Value("${JWT_SECRET_KEY1}") String k1,
        @Value("${JWT_SECRET_KEY2}") String k2,
        @Value("${JWT_SECRET_KEY3}") String k3
    ) {
        this.keys = Map.of("key1", k1, "key2", k2, "key3", k3);
    }
}
```

---

# 2. entity — `BaseTimeEntity`

```java
@Getter
@MappedSuperclass                                  // 테이블이 생기지 않는다
@EntityListeners(AuditingEntityListener.class)     // JPA 생명주기를 감시할 리스너
public abstract class BaseTimeEntity {

    @CreatedDate      protected LocalDateTime createdAt;
    @LastModifiedDate protected LocalDateTime updatedAt;
}
```

이 프로젝트의 대부분 엔티티가 `extends BaseTimeEntity`입니다.

**동작 흐름**

```
boardRepository.save(board)
      ↓
Hibernate가 INSERT 직전 @PrePersist 이벤트 발생
      ↓
AuditingEntityListener가 가로챔
      ↓
@CreatedDate 필드에 현재 시각 대입, @LastModifiedDate에도 대입
      ↓
INSERT INTO board (..., created_at, updated_at) VALUES (..., now, now)
```

UPDATE 시에는 `@PreUpdate` 이벤트에서 `updatedAt`만 갱신됩니다.

**왜 `protected`인가?** 자식 엔티티가 직접 접근할 수 있게 하되 외부에는 열지 않기 위함입니다.
읽기는 `@Getter`가 만든 `getCreatedAt()`으로 합니다.

**왜 `abstract`인가?** 이 클래스 자체는 실체가 없는 "공통 필드 묶음"이므로 인스턴스화를 막습니다.

> `@MappedSuperclass`가 붙어 있으므로 **`base_time_entity` 테이블은 생기지 않습니다.**
> `created_at`, `updated_at` 컬럼이 자식 테이블(`board`, `points`, `member` …) 각각에 들어갑니다.
> → [기초개념 6-2](00-기초개념.md#6-2-엔티티-매핑-어노테이션)

---

# 3. exception — 예외 처리 3층 구조

이 프로젝트의 예외 설계는 **잘 만들어진 부분**입니다. 구조를 이해하면 모든 도메인의 예외가 읽힙니다.

```
[1층] ErrorCode (enum)              — 에러코드 + HTTP상태 + 사용자 메시지를 한 덩어리로
   ↑
[2층] ApplicationException           — ErrorCode를 품은 RuntimeException
   ↑     └── BoardNotFoundException, UserNotFoundException, ... (도메인별 40여 개)
   ↑
[3층] GlobalExceptionRestAdvice      — 예외를 잡아 ResponseDTO JSON으로 변환
```

## 3-1. `ErrorCode` — 에러의 단일 진실 공급원

```java
@Getter
public enum ErrorCode {
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 회원입니다."),
    INSUFFICIENT_POINT(HttpStatus.BAD_REQUEST, "남은 포인트가 사용하고자 하는 포인트보다 부족합니다."),
    POST_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "5분 내 게시글 작성 한도(3개)를 초과했습니다."),
    ...
    private final HttpStatus httpStatus;
    private final String message;
}
```

**이 설계의 이점**: 에러 메시지가 코드 곳곳에 문자열 리터럴로 흩어지지 않습니다.
문구를 바꿀 때 이 파일 한 곳만 고치면 됩니다. 전체 에러 목록을 한눈에 볼 수도 있습니다.

> 다만 대부분이 `BAD_REQUEST(400)`로 통일돼 있습니다.
> `USER_NOT_FOUND`, `BOARD_NOT_FOUND`는 의미상 `404 NOT_FOUND`가 맞습니다.
> HTTP 상태 코드는 클라이언트의 분기 처리 기준이므로 의미를 맞추는 편이 좋습니다.

## 3-2. `ApplicationException` — 커스텀 예외의 부모

```java
@Getter
public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ApplicationException(ErrorCode errorCode) { this.errorCode = errorCode; }

    @Override
    public String getMessage() {
        // 개별 메시지를 주지 않았으면 ErrorCode의 기본 메시지를 사용
        return super.getMessage() == null ? errorCode.getMessage() : super.getMessage();
    }
}
```

**`RuntimeException`을 상속한 이유** — 두 가지입니다:

1. Checked 예외라면 모든 호출 경로에 `throws`를 달아야 해서 코드가 지저분해집니다.
2. **`@Transactional`은 `RuntimeException`에만 롤백합니다.**
   Checked 예외로 만들면 예외가 났는데도 커밋되는 사고가 납니다. → [기초개념 7-4](00-기초개념.md#7-4-롤백-규칙--checked-예외는-롤백되지-않는다)

**생성자가 `protected`인 이유**: `new ApplicationException(...)`을 직접 못 쓰게 막아
**반드시 도메인별 구체 예외를 만들어 쓰도록** 강제합니다.

```java
public class BoardNotFoundException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.BOARD_NOT_FOUND;
    public BoardNotFoundException() { super(ERROR_CODE); }
}
```

인자 없는 생성자이므로 **메서드 참조**로 간결하게 쓸 수 있습니다:

```java
boardRepository.findById(id).orElseThrow(BoardNotFoundException::new);
```

`orElseThrow`는 `Supplier<X>`를 받습니다. `BoardNotFoundException::new`는
"호출하면 새 인스턴스를 만들어주는 함수"로 해석되어 딱 맞습니다.

## 3-3. `GlobalExceptionRestAdvice` — 예외를 JSON으로

```java
@Slf4j
@RestControllerAdvice          // = @ControllerAdvice + @ResponseBody
public class GlobalExceptionRestAdvice {

    @ExceptionHandler
    public ResponseEntity<ResponseDTO<Void>> applicationException(ApplicationException e) {
        log.error(e.getMessage(), e);
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())        // ErrorCode가 상태코드를 결정
                .body(ResponseDTO.error(e.getErrorCode()));
    }
```

**`@RestControllerAdvice`란**: 모든 컨트롤러에 공통 적용되는 전역 예외 처리기입니다.
컨트롤러마다 try/catch를 쓰지 않아도 됩니다.

**`@ExceptionHandler`의 대상 결정**: 어노테이션에 값을 안 주면 **메서드 파라미터 타입**으로 판단합니다.
아래 두 줄은 같은 의미입니다.

```java
@ExceptionHandler                                  // 파라미터 타입으로 추론
public ... applicationException(ApplicationException e)

@ExceptionHandler(ApplicationException.class)      // 명시
```

**핸들러 우선순위**: 스프링은 **가장 구체적인(가까운) 타입**의 핸들러를 고릅니다.
`BoardNotFoundException`이 던져지면 `ApplicationException` 핸들러가 잡습니다
(`RuntimeException` 핸들러보다 더 구체적이므로).

**등록된 핸들러 목록**

| 예외 | 응답 | 언제 |
|---|---|---|
| `ApplicationException` | ErrorCode의 상태 | 우리가 의도적으로 던진 비즈니스 예외 |
| `BindException` | 400 | 폼/쿼리 파라미터 바인딩·검증 실패 |
| `MethodArgumentNotValidException` | 400 + 필드별 메시지 | `@Valid @RequestBody` 검증 실패 |
| `HttpMessageNotReadableException` | 400 | JSON 문법 오류 등 본문 파싱 실패 |
| `HttpRequestMethodNotSupportedException` | 405 | GET만 있는 곳에 POST |
| `MissingServletRequestParameterException` | 400 | 필수 `@RequestParam` 누락 |
| `TypeMismatchException` | 400 | `Long` 자리에 문자열 |
| `NoHandlerFoundException` | 404 | 없는 엔드포인트 |
| `DataAccessException` | 500 "서버 에러!" | DB 예외 |
| `RuntimeException` | 500 "서버 에러!" | 나머지 전부 |

**`NoHandlerFoundException`이 동작하려면 설정이 필요합니다**

```yaml
# application.yml
spring.web.resources.add-mappings: false   # 정적 리소스 매핑 비활성화
```

기본 설정에서는 매핑되지 않은 경로를 "정적 파일 요청"으로 보고 정적 리소스 핸들러가 처리해버려서
`NoHandlerFoundException`이 발생하지 않습니다. 이 프로젝트는 그래서 위 설정을 꺼두었고,
주석에도 그 이유가 적혀 있습니다. **API 전용 서버에서는 올바른 선택입니다.**

### ⚠️ 문제 — `RuntimeException` 포괄 핸들러가 400을 500으로 만든다

```java
@ExceptionHandler
public ResponseEntity<ResponseDTO<Void>> serverException(RuntimeException e) {
    return ResponseEntity.status(500).body(ResponseDTO.errorWithMessage(500, "서버 에러!"));
}
```

`IllegalArgumentException`은 `RuntimeException`의 자손이므로 이 핸들러에 걸립니다.
그런데 `InterviewController.validateFile()`은 클라이언트 잘못(파일 없음, 형식 미지원)에
`IllegalArgumentException`을 던집니다. 결과적으로 **"지원하지 않는 파일 형식입니다"가
400이 아니라 500 "서버 에러!"로 나가고, 원래 메시지도 사라집니다.**

**고치는 방향**

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ResponseDTO<Void>> handleIllegalArgument(IllegalArgumentException e) {
    log.warn("잘못된 인자: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST, e.getMessage()));
}

// 업로드 크기 초과도 핸들러가 없어 500이 납니다 → 추가 필요
@ExceptionHandler(MaxUploadSizeExceededException.class)
public ResponseEntity<ResponseDTO<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ResponseDTO.errorWithMessage(HttpStatus.PAYLOAD_TOO_LARGE, "파일 크기가 너무 큽니다."));
}
```

> 더 근본적으로는 **컨트롤러가 `IllegalArgumentException` 대신 도메인 예외를 던지는 것**이 이 프로젝트의
> 설계와 일관됩니다. (`ErrorCode`에 `UNSUPPORTED_FILE_TYPE`을 추가하고 `UnsupportedFileTypeException`을 만드는 방식)

---

# 4. util — 응답 껍데기와 유틸

## 4-1. `ResponseDTO<T>` — 모든 응답의 형식

```java
@Getter
public class ResponseDTO<T> {
    private final int code;
    private final String message;
    private final T data;

    @Builder
    private ResponseDTO(int code, String message, T data) { ... }   // 생성자를 막고

    public static ResponseDTO<Void> ok() { ... }
    public static ResponseDTO<Void> okWithMessage(String message) { ... }
    public static <T> ResponseDTO<T> okWithData(T data) { ... }
    public static <T> ResponseDTO<T> okWithData(T data, String message) { ... }
    public static ResponseDTO<Void> error(ErrorCode errorCode) { ... }
    public static ResponseDTO<Void> errorWithMessage(HttpStatus status, String msg) { ... }
}
```

**응답 예시**

```json
{ "code": 200, "message": "요청이 성공적으로 처리되었습니다.", "data": { "boardId": 3, "title": "..." } }
{ "code": 400, "message": "존재하지 않는 회원입니다." }
```

`data`가 없으면 `NON_NULL` 설정 덕분에 JSON에서 그 키가 아예 빠집니다.

**`Void` 제네릭**: 데이터가 없는 응답의 타입을 `ResponseDTO<Void>`로 표현합니다.
`Void`는 인스턴스를 만들 수 없는 특수 클래스로, "값 없음"을 타입으로 표현하는 관용구입니다.

**정적 팩토리 + private 생성자**: 생성자는 이름을 가질 수 없으니 `ok()`, `error()`처럼
**의도가 드러나는 이름**을 주기 위해 이 패턴을 씁니다.
→ [기초개념 8-5](00-기초개념.md#8-5-정적-팩토리-메서드--private-생성자)

**컨트롤러의 반복 패턴**

```java
ResponseDTO<T> response = ResponseDTO.okWithData(result, "면접 세션이 성공적으로 시작되었습니다.");
return ResponseEntity.status(response.getCode()).body(response);
```

HTTP 상태 코드와 본문의 `code`를 **일치시키기 위해** `response.getCode()`를 다시 꺼내 씁니다.

> 상태 코드를 본문에 중복해서 담는 설계는 논쟁적입니다. HTTP 상태만으로 충분하다는 견해도 있고,
> 프론트 처리를 단순화한다는 견해도 있습니다. **일관성이 있다면 문제는 아닙니다.**

## 4-2. `CustomResponseUtil` — 필터에서 직접 JSON 쓰기

```java
public static void fail(HttpServletResponse response, String msg, HttpStatus httpStatus) {
    ObjectMapper objectMapper = new ObjectMapper();
    response.setStatus(httpStatus.value());
    String jsonResponse = objectMapper.writeValueAsString(ResponseDTO.errorWithMessage(httpStatus, msg));
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(jsonResponse);
}
```

**왜 이런 클래스가 필요한가?**
필터/시큐리티 단계는 `@RestControllerAdvice`가 닿지 않는 영역입니다.
→ [기초개념 5](00-기초개념.md#5-spring-mvc--요청-하나가-지나가는-길)
그래서 `HttpServletResponse`에 **직접 JSON 문자열을 써야** 합니다.
`SpringSecurityConfig`의 `authenticationEntryPoint`, `accessDeniedHandler`가 이 메서드를 씁니다.

```java
exceptionHandling.authenticationEntryPoint(
    (request, response, authException) -> CustomResponseUtil.fail(response, "로그인을 진행해 주세요", UNAUTHORIZED));
```

> 개선점: 매 호출마다 `new ObjectMapper()`를 만듭니다. `ObjectMapper`는 생성 비용이 크고
> **스레드 세이프하므로 빈으로 주입받아 재사용**하는 것이 맞습니다.
> (`AppConfig`에 이미 빈이 있는데 활용하지 않고 있습니다.)

## 4-3. `NicknameGenerator` — 랜덤 한글 닉네임

```java
private static final List<String> PART1 = List.of("자유로운", "행복한", ...);   // 29개
private static final List<String> PART2 = List.of("텁속의", "바다의", ...);      // 31개
private static final List<String> PART3 = List.of("사자", "너구리", ...);        // 27개

private static String getRandomPart(List<String> list) {
    return list.get(ThreadLocalRandom.current().nextInt(list.size()));
}

public static String generateName() {
    return getRandomPart(PART1) + getRandomPart(PART2) + getRandomPart(PART3);
}
```

**배울 점 2가지**

1. **`static final List.of(...)`**: `List.of`는 **불변 리스트**를 만듭니다.
   `static final`이므로 클래스 로딩 시 딱 한 번 만들어지고 재사용됩니다.
   메서드 안에서 매번 만들면 호출마다 리스트 29개를 새로 할당하게 됩니다.
2. **`ThreadLocalRandom` vs `Random`**: `Random`은 여러 스레드가 공유하면 내부 시드에 대한
   경쟁(CAS 재시도)이 생겨 느려집니다. `ThreadLocalRandom`은 **스레드마다 별도 시드**를 쓰므로
   경쟁이 없습니다. 웹 서버(멀티스레드)에서는 후자가 정답입니다.

> 참고로 `JwtKey`는 여전히 `new Random()`을 씁니다. 보안 목적의 난수라면
> `SecureRandom`이 맞고, 성능만 보면 `ThreadLocalRandom`이 맞습니다.

조합 수는 29 × 31 × 27 = **24,273개**뿐입니다. 회원이 늘면 충돌이 잦아집니다.
그래서 `PrincipalOauth2UserService`가 중복 확인 루프를 도는데, 그 방식에 문제가 있습니다. ([6-4](#6-4-principaloauth2userservice--소셜-로그인-후처리))

---

# 5. jwt — 토큰 발급과 검증

## 5-1. JWT란 무엇인가

```
eyJhbGciOiJIUzUxMiIsImtpZCI6ImtleTIifQ . eyJzdWIiOiJhQGIuY29tIiwiaWF0Ijo... . 3vQ2p8...
└──────── Header ────────┘              └────────── Payload ──────────┘   └─ Signature ─┘
   {"alg":"HS512","kid":"key2"}          {"sub":"a@b.com","iat":...,"exp":...}
```

- 점(`.`)으로 구분된 3덩어리. 각각 Base64URL 인코딩입니다.
- **Header/Payload는 암호화가 아니라 인코딩입니다.** 누구나 디코딩해서 읽을 수 있습니다.
  → **JWT에 비밀번호 같은 민감 정보를 넣으면 안 됩니다.**
- **Signature**가 위조를 막습니다. 서버만 아는 비밀키로 `Header.Payload`를 해싱한 값입니다.
  내용을 한 글자라도 바꾸면 서명이 안 맞아 검증에 실패합니다.
- 서버가 세션을 저장하지 않아도 되므로 **stateless** 인증이 가능합니다.

**표준 클레임(payload 필드)**

| 클레임 | 의미 | 이 프로젝트 |
|---|---|---|
| `sub` (subject) | 토큰의 주체 | **이메일** |
| `iat` (issued at) | 발급 시각 | `setIssuedAt(now)` |
| `exp` (expiration) | 만료 시각 | `now + 40시간` |

## 5-2. `JwtProperties` — 만료 시간

```java
public class JwtProperties {
    public static final int ACCESS_TOKEN_EXPIRATION_TIME  = 1000 * 60 * 60 * 40; // 10분 -> 600000
    public static final int REFRESH_TOKEN_EXPIRATION_TIME = 1000 * 60 * 60 * 40;
    public static final String COOKIE_NAME = "JWT-AUTHENTICATION";
}
```

`1000 * 60 * 60 * 40` = 밀리초 × 초 × 분 × 40 = **144,000,000ms = 40시간**입니다.

### ⚠️ 문제 3가지

1. **주석이 거짓입니다.** "10분"이라고 적혀 있지만 실제로는 40시간입니다.
   개발 중 편의로 늘렸다가 주석을 안 고친 것으로 보입니다.
2. **액세스 토큰 40시간은 너무 깁니다.** 토큰이 탈취되면 40시간 동안 유효합니다.
   JWT는 **서버에서 무효화할 수 없으므로**(stateless) 만료 시간이 유일한 방어선입니다.
   보통 액세스는 15~30분으로 짧게 둡니다.
3. **액세스와 리프레시 만료가 같아 리프레시 토큰이 무의미합니다.**
   리프레시 토큰의 존재 이유는 "액세스는 짧게, 갱신은 길게"인데, 둘이 같으면
   액세스가 죽는 순간 리프레시도 죽습니다.

```java
// 올바른 형태
public static final long ACCESS_TOKEN_EXPIRATION_TIME  = 1000L * 60 * 30;        // 30분
public static final long REFRESH_TOKEN_EXPIRATION_TIME = 1000L * 60 * 60 * 24 * 14; // 14일
```

> **`int` vs `long` 주의**: 지금 값(1.44억)은 `int` 범위(약 21.4억) 안이라 괜찮지만,
> `1000 * 60 * 60 * 24 * 14`(12억)를 넘어 한 달 이상으로 늘리면 **조용히 오버플로**가 납니다.
> 시간 계산은 처음부터 `long`(`1000L`)으로 쓰는 습관이 안전합니다.

## 5-3. `JwtKey` — 비밀키 3개 로테이션

```java
public class JwtKey {
    private static final Map<String, String> SECRET_KEY_SET = Map.of(
        "key1", JWT_SECRET_KEY1, "key2", JWT_SECRET_KEY2, "key3", JWT_SECRET_KEY3);
    private static final String[] KID_SET = SECRET_KEY_SET.keySet().toArray(new String[0]);
    private static Random randomIndex = new Random();

    public static Pair<String, Key> getRandomKey() {              // 발급 시: 무작위 키 선택
        String kid = KID_SET[randomIndex.nextInt(KID_SET.length)];
        return Pair.of(kid, Keys.hmacShaKeyFor(SECRET_KEY_SET.get(kid).getBytes(UTF_8)));
    }

    public static Key getKey(String kid) {                         // 검증 시: kid로 키 찾기
        String key = SECRET_KEY_SET.getOrDefault(kid, null);
        return key == null ? null : Keys.hmacShaKeyFor(key.getBytes(UTF_8));
    }
}
```

**아이디어**: 토큰을 만들 때 3개 중 하나를 무작위로 골라 서명하고, 어떤 키를 썼는지
**헤더의 `kid`(Key ID)** 에 적어둡니다. 검증할 때 `kid`를 읽어 해당 키로 확인합니다.

```java
// 발급 (JwtProvider)
.setHeaderParam(JwsHeader.KEY_ID, key.getFirst())   // 헤더에 kid 기록
.signWith(key.getSecond())

// 검증 (SigningKeyResolver)
public Key resolveSigningKey(JwsHeader jwsHeader, Claims claims) {
    String kid = jwsHeader.getKeyId();
    return JwtKey.getKey(kid);      // kid에 해당하는 키 반환
}
```

**`kid`는 왜 필요한가?** 키가 여러 개면 검증할 때 **어느 키로 서명했는지 알 수 없습니다.**
모든 키를 하나씩 시도하는 건 낭비이므로, `kid`를 헤더에 남겨 즉시 찾게 합니다.
실제 서비스에서 **키를 무중단으로 교체(rotation)** 하는 표준 방식이 이것입니다.

**`SigningKeyResolverAdapter` 상속**: `jjwt` 라이브러리가 제공하는 추상 클래스로,
"토큰 파싱 중 서명 검증 키가 필요할 때 불러주는 콜백"입니다.

```java
public static SigningKeyResolver instance = new SigningKeyResolver();   // 싱글턴 (상태가 없으므로 안전)
```

**`Keys.hmacShaKeyFor()`** 는 바이트 배열로 HMAC 키를 만듭니다.
⚠️ 키 길이 검증이 들어 있습니다 — **HS512는 최소 64바이트(512비트)** 가 필요하고,
짧으면 `WeakKeyException`이 발생합니다. `.env`의 비밀키가 충분히 길어야 합니다.

## 5-4. `JwtProvider` — 토큰 만들기 / 읽기

```java
@Component
@RequiredArgsConstructor
public class JwtProvider {

    public String createToken(Member member) {
        Claims claims = Jwts.claims().setSubject(member.getMemberBase().getEmail());
        Date now = new Date();
        Pair<String, Key> key = JwtKey.getRandomKey();
        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(now)
            .setExpiration(new Date(now.getTime() + JwtProperties.ACCESS_TOKEN_EXPIRATION_TIME))
            .setHeaderParam(JwsHeader.KEY_ID, key.getFirst())
            .signWith(key.getSecond())
            .compact();                       // 최종 문자열 생성
    }

    public String getEmail(String token) {
        return Jwts.parserBuilder()
            .setSigningKeyResolver(SigningKeyResolver.instance)
            .build()
            .parseClaimsJws(token)            // ⚠️ 여기서 예외가 던져진다
            .getBody()
            .getSubject();
    }
}
```

**`parseClaimsJws`가 던지는 예외들** — 여기가 중요합니다:

| 예외 | 상황 |
|---|---|
| `ExpiredJwtException` | 만료됨 |
| `SignatureException` | 서명 불일치 (위조) |
| `MalformedJwtException` | 형식이 깨짐 |
| `UnsupportedJwtException` | 지원하지 않는 형식 |
| `IllegalArgumentException` | 토큰이 빈 문자열 |

모두 `JwtException` 또는 `RuntimeException` 계열입니다. **호출하는 쪽에서 반드시 잡아야 합니다.**

> **토큰에 이메일만 담는 설계**: payload에 이메일(`sub`)만 넣고, 나머지는 매 요청마다 DB에서 조회합니다.
> 장점은 권한 변경이 즉시 반영된다는 것이고, 단점은 **요청마다 회원 조회 쿼리가 1번 발생**한다는 것입니다.
> 트래픽이 늘면 여기가 병목이 되므로, 필요한 정보(id, authority)를 클레임에 담거나
> 캐시를 두는 개선이 가능합니다.

## 5-5. `JwtAuthenticationFilter` — 로그인 처리 (인증, Authentication)

`UsernamePasswordAuthenticationFilter`를 상속했습니다. 기본 동작은 `POST /login`을 가로채는 것입니다.

```
POST /login  { "email": "a@b.com", "password": "1234" }
     │
     ▼ attemptAuthentication()
       요청 JSON을 LoginRequestDto로 파싱
       UsernamePasswordAuthenticationToken(email, password) 생성
       AuthenticationManager.authenticate(token) 호출
             │
             ▼ (AuthConfig의 DaoAuthenticationProvider가 처리)
               UserDetailsService.loadUserByUsername(email) → DB에서 회원 조회
               BCryptPasswordEncoder.matches(입력 비번, 저장된 해시) 비교
             │
     ┌───────┴────────┐
     ▼ 성공            ▼ 실패
successfulAuthentication()   unsuccessfulAuthentication()
  JWT 발급                     예외 종류별 메시지 분기
  LoginResponseDto로 응답       400 + 메시지 응답
```

```java
@Override
protected void successfulAuthentication(..., Authentication authResult) throws IOException {
    Member member = ((PrincipalDetails) authResult.getPrincipal()).getMember();
    String token = jwtProvider.createToken(member);

    int level = memberService.getMemberTierOrder(member);
    LoginResponseDto dto = LoginResponseDto.fromEntity(member, token, level);
    sendJsonResponse(response, ResponseDTO.okWithData(dto), HttpStatus.OK);
}
```

**`getPrincipal()`이 반환하는 것**: 인증 성공 후 `Authentication` 객체 안의 주체입니다.
이 프로젝트는 `PrincipalDetails`를 넣었으므로 캐스팅해서 `Member`를 꺼냅니다.

```java
private String getAuthenticationErrorMessage(AuthenticationException exception) {
    if (exception instanceof BadCredentialsException) return "이메일 또는 비밀번호 에러";
    else if (exception instanceof UsernameNotFoundException) return "존재하지 않는 유저";
    else return "인증 실패";
}
```

**`instanceof`로 예외 종류를 분기**하는 전형적인 패턴입니다.

> 보안 관점에서는 "이메일이 없음"과 "비밀번호 틀림"을 구분해주면
> **공격자가 가입된 이메일 목록을 알아낼 수 있습니다**(계정 열거 공격).
> 실무에서는 둘 다 "이메일 또는 비밀번호가 올바르지 않습니다"로 통일합니다.
> 참고로 Spring Security는 기본적으로 `UsernameNotFoundException`을
> `BadCredentialsException`으로 감싸 숨깁니다(`hideUserNotFoundExceptions=true`).

> 개선점: `attemptAuthentication`과 `sendJsonResponse`에서 각각 `new ObjectMapper()`를 만듭니다.
> 빈으로 주입받아 재사용해야 합니다.

## 5-6. `JwtAuthorizationFilter` — 요청마다 토큰 검사 (인가, Authorization)

```java
@Component
public class JwtAuthorizationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        List<String> headerValues = Collections.list(request.getHeaders("Authorization"));
        String accessToken = headerValues.stream()
                .findFirst()
                .map(header -> header.replace("Bearer ", ""))
                .orElse(null);

        Authentication authentication = getUsernamePasswordAuthenticationToken(accessToken);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);      // 다음 필터로 넘김 — 반드시 호출해야 한다
    }
```

**`OncePerRequestFilter`를 상속하는 이유**: 서블릿 필터는 forward/include 같은 내부 디스패치 때
**여러 번 호출될 수 있습니다.** 이 클래스는 요청당 한 번만 실행되도록 보장합니다.
인증 필터는 중복 실행하면 안 되므로 이 부모를 씁니다.

**`SecurityContextHolder`**: 현재 요청의 인증 정보를 담아두는 저장소입니다.
내부적으로 `ThreadLocal`을 쓰므로 **같은 스레드 안에서는 어디서든 꺼낼 수 있습니다.**
`@AuthenticationPrincipal PrincipalDetails`가 값을 얻는 곳이 바로 여기입니다.

```
JwtAuthorizationFilter가 SecurityContext에 넣음
        ↓
        ThreadLocal
        ↓
컨트롤러의 @AuthenticationPrincipal이 꺼내 씀
```

> ⚠️ `@Async`로 다른 스레드에 넘어가면 `ThreadLocal`이 전달되지 않으므로 인증 정보가 사라집니다.
> 비동기 작업에 사용자 정보가 필요하면 **파라미터로 명시적으로 넘겨야** 합니다.
> 이 프로젝트의 검열/AI봇 비동기 로직이 `memberId`를 인자로 받는 이유입니다.

### ⚠️ 문제 1 — 예외 처리가 없어 만료 토큰이 500을 반환

```java
private Authentication getUsernamePasswordAuthenticationToken(String token) {
    if (token == null) return null;
    String email = jwtProvider.getEmail(token);        // ⚠️ 만료/위조 시 JwtException을 던진다
    if (email != null) {
        return memberRepository.findByMemberBaseEmail(email)
                .map(PrincipalDetails::new)
                .map(pd -> new UsernamePasswordAuthenticationToken(pd, null, pd.getAuthorities()))
                .orElseThrow(IllegalAccessError::new);   // ⚠️ Error를 던지고 있다
    }
    return null;
}
```

**무슨 일이 벌어지나**

```
만료된 토큰으로 요청
      ↓
jwtProvider.getEmail() → ExpiredJwtException 발생
      ↓
필터 안에서 예외가 그대로 밖으로 던져짐
      ↓
@RestControllerAdvice는 DispatcherServlet 뒤에 있어서 잡지 못함
      ↓
서블릿 컨테이너 기본 에러 처리 → 500 Internal Server Error
```

프론트엔드는 **"토큰 만료 → 재로그인"** 분기를 만들 수 없습니다. 401을 받아야 하니까요.

**또 하나**: `orElseThrow(IllegalAccessError::new)`는 `Error`를 던집니다.
`Error`는 `OutOfMemoryError`처럼 **JVM 수준의 복구 불가능한 상황**을 위한 것입니다.
애플리케이션 코드가 던져서는 안 됩니다. → [기초개념 1-6](00-기초개념.md#1-6-예외--exception-vs-error-checked-vs-unchecked)

**고치는 방향**

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws IOException, ServletException {
    String accessToken = resolveToken(request);

    if (accessToken != null) {
        try {
            Authentication authentication = getAuthentication(accessToken);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            CustomResponseUtil.fail(response, "토큰이 만료되었습니다. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
            return;                                     // 체인을 끊고 즉시 응답
        } catch (JwtException | IllegalArgumentException e) {
            CustomResponseUtil.fail(response, "유효하지 않은 토큰입니다.", HttpStatus.UNAUTHORIZED);
            return;
        }
    }
    chain.doFilter(request, response);   // 토큰이 없으면 익명으로 통과 → 시큐리티 인가가 판단
}

private Authentication getAuthentication(String token) {
    String email = jwtProvider.getEmail(token);
    return memberRepository.findByMemberBaseEmail(email)
            .map(PrincipalDetails::new)
            .map(pd -> new UsernamePasswordAuthenticationToken(pd, null, pd.getAuthorities()))
            .orElse(null);        // 회원이 없으면 인증 실패로 처리 (Error를 던지지 않는다)
}
```

**핵심 원칙**: 필터에서 응답을 직접 쓴 뒤에는 **`chain.doFilter()`를 호출하지 않고 `return`** 해야 합니다.
호출하면 요청이 계속 진행되어 응답이 두 번 쓰이는 사고가 납니다.

### ⚠️ 문제 2 — 토큰 접두사 처리가 허술하다

```java
.map(header -> header.replace("Bearer ", ""))
```

`replace`는 **문자열 안의 모든 위치**를 바꿉니다. 토큰 본문에 우연히 `Bearer `가 있으면 그것도 지워집니다.
접두사 검사도 없어서 `Basic xxx`가 와도 그냥 통과시킵니다.

```java
// 올바른 형태
private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
        return header.substring(7);      // "Bearer ".length() == 7
    }
    return null;
}
```

---

# 6. springsecurity — 인증·인가 설정

## 6-1. `SpringSecurityConfig` — 필터 체인의 설계도

```java
@Configuration
@RequiredArgsConstructor
public class SpringSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ...) throws Exception {
```

`SecurityFilterChain` 빈 하나가 **보안 설정 전체**입니다. 항목별로 봅니다.

### ① 상태를 갖지 않게 만들기

```java
http.httpBasic(AbstractHttpConfigurer::disable);   // 브라우저 기본 인증창 끔
http.csrf(AbstractHttpConfigurer::disable);        // CSRF 토큰 검사 끔
http.rememberMe(AbstractHttpConfigurer::disable);
http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
```

**CSRF를 왜 끄는가?** CSRF 공격은 **브라우저가 쿠키를 자동으로 첨부하는 성질**을 악용합니다.
JWT를 `Authorization` 헤더로 보내는 방식은 브라우저가 자동 첨부하지 않으므로
CSRF 위험이 원리적으로 없습니다. **단, 토큰을 쿠키에 담는다면 CSRF를 다시 켜야 합니다.**

**`STATELESS`**: 서버가 세션(`HttpSession`)을 만들지 않습니다.
JSESSIONID 쿠키도 발급하지 않아 서버 확장(스케일 아웃)이 쉬워집니다.

### ② 필터 등록 순서

```java
http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
    .addFilterBefore(jwtAuthorizationFilter, BasicAuthenticationFilter.class);
```

Spring Security 필터 체인은 **순서가 정해진 목록**입니다.
`addFilterBefore(A, B.class)`는 "A를 B 앞에 넣어라"입니다.

```
... → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter →
      JwtAuthorizationFilter  → BasicAuthenticationFilter → ... → 인가 검사(FilterSecurityInterceptor)
```

**인가 검사가 마지막에 있는 것이 핵심입니다.** `JwtAuthorizationFilter`가 먼저
`SecurityContext`를 채워두고, 마지막 단계에서 "이 사용자가 이 URL에 접근 가능한가"를 판단합니다.

### ③ URL별 권한 — ⚠️ 이 프로젝트 최대의 문제

```java
http.authorizeHttpRequests(authz -> authz
    .requestMatchers(new AntPathRequestMatcher("/api/member/signup")).permitAll()
    ...
    // 임시
    .requestMatchers(new AntPathRequestMatcher("/api/boards/**")).permitAll()   // ⚠️
    .requestMatchers(new AntPathRequestMatcher("/api/interview/**")).permitAll() // ⚠️
    .requestMatchers(new AntPathRequestMatcher("/api/conversations/**")).permitAll() // ⚠️
    .requestMatchers(new AntPathRequestMatcher("/actuator/**")).permitAll()      // ⚠️
    .anyRequest().authenticated());
```

**`AntPathRequestMatcher`의 패턴 규칙**

| 패턴 | 의미 |
|---|---|
| `?` | 문자 1개 |
| `*` | 경로 한 구간 안의 문자들 (`/`는 안 넘음) |
| `**` | **경로 구간 여러 개** (`/`를 넘어감) |

**문제**: `AntPathRequestMatcher("/api/boards/**")`에 **HTTP 메서드 제한이 없습니다.**
따라서 다음이 모두 무인증으로 통과합니다:

```
POST   /api/boards         게시글 생성
PATCH  /api/boards/3       게시글 수정
DELETE /api/boards/3       게시글 삭제
```

그런데 `BoardController`는 이렇게 생겼습니다:

```java
@PostMapping("/boards")
public ResponseEntity<Void> createBoard(@RequestBody BoardCreateAndEditRequestDto dto,
                                        @AuthenticationPrincipal PrincipalDetails principalDetails) {
    boardService.createBoard(principalDetails, dto);   // 내부에서 principalDetails.getMember() 호출
}
```

인증이 없으면 `principalDetails`가 `null`이므로 **NPE → 500**이 납니다.
즉 **"보안 구멍 + 잘못된 에러 응답"이 동시에** 있는 상태입니다.

**고치는 방향** — 읽기만 열고 쓰기는 인증을 요구합니다:

```java
.requestMatchers(HttpMethod.GET, "/api/boards", "/api/boards/search", "/api/boards/{boardId}").permitAll()
.requestMatchers("/api/boards/**").authenticated()      // POST/PATCH/DELETE는 인증 필요
```

`/actuator/**`도 무인증입니다. `application-prod.yml`에서 `prometheus,health`만 노출하도록
제한해두었지만, **메트릭 정보는 외부에 열 이유가 없습니다.**
보안 그룹/방화벽으로 내부 IP만 허용하거나 별도 관리 포트로 분리하는 것이 맞습니다.

### ④ CORS 설정 — 죽은 코드가 섞여 있다

```java
http.cors(cors -> cors.configurationSource(request -> {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.applyPermitDefaultValues();
    configuration.setAllowCredentials(true);
    configuration.addAllowedOriginPattern("");                        // ⚠️ 의미 없는 빈 패턴
    configuration.addAllowedOriginPattern("http://localhost:3000");
    configuration.addAllowedOriginPattern("https://gaebang.site");
    ...
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();  // ⚠️ 만들고
    src.registerCorsConfiguration("/api/interview/tts/**", configuration);        // ⚠️ 등록하고
    return configuration;                                                         // ⚠️ 안 쓰고 반환
}));
```

**CORS란**: 브라우저가 **다른 출처(origin = 스킴+호스트+포트)** 로 요청할 때 적용하는 보안 정책입니다.
서버가 `Access-Control-Allow-Origin` 헤더로 허용을 표시해야 브라우저가 응답을 넘겨줍니다.

**`allowedOrigins` vs `allowedOriginPatterns`**

```java
setAllowedOrigins(List.of("*"))          // allowCredentials(true)와 함께 쓸 수 없다 (스펙 위반)
addAllowedOriginPattern("https://*.gaebang.site")   // 와일드카드 + 인증정보 동시 허용 가능
```

`allowCredentials(true)`(쿠키·인증 헤더 허용)와 `Origin: *`는 **동시에 쓸 수 없습니다.**
그래서 스프링이 `allowedOriginPatterns`를 따로 제공합니다.
`applyPermitDefaultValues()`가 `allowedOrigins`를 `*`로 설정하지만,
`addAllowedOriginPattern`을 호출하면 스프링이 그 `*`를 지워주므로 결과적으로 동작합니다.
**의도한 게 아니라 우연히 맞은 구조**이니 정리하는 편이 좋습니다.

**정리한 형태**

```java
http.cors(cors -> cors.configurationSource(request -> {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOriginPatterns(List.of(
        "http://localhost:3000", "http://localhost:8081", "http://localhost:5727",
        "http://127.0.0.1:5727", "https://gaebang.site", "https://www.gaebang.site"));
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
    c.setExposedHeaders(List.of("Content-Disposition", "Content-Type", "Content-Length"));
    c.setAllowCredentials(true);
    c.setMaxAge(3600L);          // preflight 결과를 1시간 캐시 → OPTIONS 요청 감소
    return c;
}));
```

**`setExposedHeaders`가 필요한 이유**: 브라우저는 기본적으로 몇 개의 안전한 헤더만
JavaScript에 노출합니다. 파일 다운로드 파일명(`Content-Disposition`)을 프론트에서 읽으려면
서버가 명시적으로 노출을 허용해야 합니다.

### ⑤ 예외 처리 진입점

```java
http.exceptionHandling(eh -> {
    eh.authenticationEntryPoint((req, res, ex) ->
        CustomResponseUtil.fail(res, "로그인을 진행해 주세요", HttpStatus.UNAUTHORIZED));   // 401
    eh.accessDeniedHandler((req, res, ex) ->
        CustomResponseUtil.fail(res, "접근 권한이 없습니다", HttpStatus.FORBIDDEN));        // 403
});
```

| 핸들러 | 언제 | 상태 |
|---|---|---|
| `authenticationEntryPoint` | **인증이 아예 없음** (로그인 안 함) | 401 Unauthorized |
| `accessDeniedHandler` | **인증은 됐지만 권한 부족** (USER가 ADMIN 영역 접근) | 403 Forbidden |

401과 403의 차이를 정확히 구분해 처리하고 있습니다. **잘 작성된 부분입니다.**

## 6-2. `AuthConfig` — 아이디/비밀번호 검증 담당

```java
@Bean
public AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
    authProvider.setUserDetailsService(userDetailsService());   // 사용자를 어디서 찾을지
    authProvider.setPasswordEncoder(passwordEncoder());          // 비밀번호를 어떻게 비교할지
    return authProvider;
}

@Bean
public UserDetailsService userDetailsService() {
    return this::loadUserByUsername;      // 람다(메서드 참조)로 인터페이스 구현
}

private PrincipalDetails loadUserByUsername(String email) {
    return memberRepository.findByMemberBaseEmail(email)
        .map(PrincipalDetails::new)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
}
```

**`return this::loadUserByUsername;`이 되는 이유**
`UserDetailsService`는 **추상 메서드가 하나뿐인 인터페이스(함수형 인터페이스)** 입니다.

```java
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}
```

시그니처가 맞는 메서드 참조/람다는 그 인터페이스의 구현체로 쓸 수 있습니다.
`PrincipalDetails`가 `UserDetails`를 구현하므로 반환 타입도 호환됩니다.

**인증 흐름 정리**

```
AuthenticationManager
      ↓ 위임
DaoAuthenticationProvider
      ├─ UserDetailsService.loadUserByUsername(email)   → DB에서 회원 조회
      └─ PasswordEncoder.matches(입력, 저장된해시)        → 비밀번호 비교
      ↓
성공 시 Authentication 객체 반환 (principal = PrincipalDetails)
```

### ⚠️ 문제 — `PasswordEncoder` 빈이 자기 자신을 참조

```java
@Autowired
private PasswordEncoder passwordEncoder;      // ← 주입받고

@Bean
public PasswordEncoder passwordEncoder() {
    return passwordEncoder;                    // ← 그걸 그대로 빈으로 등록
}
```

`AppConfig`에 이미 `BCryptPasswordEncoder` 빈이 있는데, 여기서 `PasswordEncoder` 타입 빈을
**하나 더** 만듭니다. 그러면 `PasswordEncoder` 타입 후보가 2개가 되고,
필드 주입은 타입이 모호할 때 **필드 이름(`passwordEncoder`)으로 매칭**을 시도합니다.
그 이름의 빈이 바로 이 `@Bean` 메서드 자신이므로 **순환 참조 위험**이 있는 구조입니다.
(현재 동작하고 있더라도 매우 깨지기 쉬운 형태입니다.)

**정리한 형태 — 중복 빈을 없애고 주입만 받습니다**

```java
@Configuration
@RequiredArgsConstructor
public class AuthConfig {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;   // AppConfig의 빈을 생성자 주입

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(userDetailsService());
        p.setPasswordEncoder(passwordEncoder);
        return p;
    }
    // @Bean passwordEncoder() 는 삭제
}
```

또 `auditorAware()` 빈이 등록되어 있지만, `@EnableJpaAuditing(auditorAwareRef = "auditorAware")`
설정이 없고 엔티티에 `@CreatedBy`/`@LastModifiedBy` 필드도 없어서 **실제로는 쓰이지 않습니다.**
(`ApplicationAuditAware`는 "지금 로그인한 회원 ID"를 제공하는 클래스인데, 소비자가 없습니다.)

## 6-3. `PrincipalDetails` — Member를 감싼 인증 주체

```java
@Getter
@AllArgsConstructor
@RequiredArgsConstructor
public class PrincipalDetails implements UserDetails, OAuth2User {

    private Member member;
    private String username;
    private String password;
    private Map<String, Object> attributes;

    public PrincipalDetails(Member member) { this.member = member; }                 // 일반 로그인
    public PrincipalDetails(Member member, Map<String, Object> attributes) {          // OAuth 로그인
        this.member = member;                       // ⚠️ attributes를 대입하지 않는다!
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(member.getMemberBase().getAuthority()));
    }

    @Override public String getPassword() { return member.getMemberBase().getPassword(); }
    @Override public String getUsername() { return member.getMemberBase().getNickname(); }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return true; }
}
```

**두 인터페이스를 동시에 구현한 이유**: 일반 로그인은 `UserDetails`,
소셜 로그인은 `OAuth2User`를 요구합니다. 하나의 클래스로 양쪽을 처리하려는 설계입니다.

**`getAuthorities()`와 `ROLE_` 접두사**
`member.authority`에 `"ROLE_USER"` 문자열이 그대로 저장되어 있습니다.

```java
.hasRole("USER")       // 내부적으로 "ROLE_USER"를 찾는다 (접두사를 자동으로 붙임)
.hasAuthority("ROLE_USER")   // 문자열 그대로 비교
```

DB에 `ROLE_` 접두사를 포함해 저장하는 것이 `hasRole()`과 맞물리는 관례입니다. 잘 맞췄습니다.

**`isEnabled()` 등이 모두 `true`인 것**: 계정 정지·만료 기능을 쓰지 않겠다는 뜻입니다.
나중에 정지 기능을 넣는다면 `Member`에 상태 필드를 두고 여기서 반영해야 합니다.

### ⚠️ 문제 — 생성자에서 `attributes`를 버린다

```java
public PrincipalDetails(Member member, Map<String, Object> attributes) {
    this.member = member;
    // this.attributes = attributes;   ← 누락!
}
```

그래서 `getAttributes()`는 항상 `null`입니다. OAuth 프로필 원본을 쓰려 하면 NPE가 납니다.
`getName()`이 `null`을 반환하는 것도 `OAuth2User` 규약 위반입니다.
지금은 `OAuth2LoginSuccessHandler`가 `attributes`를 쓰지 않아 드러나지 않는 잠재 버그입니다.

**또한 `@AllArgsConstructor` + `@RequiredArgsConstructor` + 수동 생성자 2개**가 겹쳐 있어
어느 생성자가 쓰이는지 파악하기 어렵습니다. `member` 하나만 남기고 나머지 필드를 지우는 편이 깔끔합니다.

## 6-4. `PrincipalOauth2UserService` — 소셜 로그인 후처리

```java
@Service
@RequiredArgsConstructor
public class PrincipalOauth2UserService extends DefaultOAuth2UserService {

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User oauth2User = super.loadUser(userRequest);      // 제공자에게 프로필 조회
        String provider = userRequest.getClientRegistration().getRegistrationId();

        OAuth2UserInfo info = null;
        if (provider.equals("google"))      info = new GoogleUserInfo(oauth2User.getAttributes());
        else if (provider.equals("naver"))  info = new NaverUserInfo((Map) oauth2User.getAttributes().get("response"));
        else if (provider.equals("kakao"))  info = new KakaoUserInfo(oauth2User.getAttributes());

        Optional<Member> user = memberRepository.findByMemberBaseEmailAndProvider(
                info.getEmail(), info.getProvider());
        ...
    }
}
```

### `OAuth2UserInfo` — 전략 패턴으로 제공자 차이를 흡수

제공자마다 응답 JSON 구조가 완전히 다릅니다.

```java
// Google — 평평한 구조
{ "email": "a@b.com", "name": "홍길동", "picture": "https://..." }

// Kakao — 두 단계 중첩
{ "id": 12345,
  "kakao_account": { "email": "a@b.com" },
  "properties": { "nickname": "홍길동", "profile_image": "https://..." } }

// Naver — response 안에 감싸져 있음
{ "response": { "email": "a@b.com", "name": "홍길동", "mobile": "010-..." } }
```

공통 인터페이스로 이 차이를 감춥니다:

```java
public interface OAuth2UserInfo {
    String getProvider();
    String getEmail();
    String getName();
    String getPhoneNumber();
    String getProfileImage();
}
```

```java
// KakaoUserInfo — 중첩 구조를 내부에서 파고든다
@Override
public String getName() {
    Map<String, Object> properties = (Map<String, Object>) attributes.get("properties");
    return (String) properties.get("nickname");
}
```

**이것이 전략 패턴(Strategy Pattern)** 입니다. 새 제공자(예: 애플)를 추가할 때
`AppleUserInfo`만 만들면 되고, 호출하는 쪽 코드는 바뀌지 않습니다.
**잘 설계된 부분입니다.**

> 개선점: `if/else if` 분기가 늘어나는 문제는 `Map<String, Function<Map, OAuth2UserInfo>>`나
> enum 팩토리로 정리할 수 있습니다. 또 지원하지 않는 provider가 오면 `info`가 `null`이 되어
> 바로 다음 줄에서 NPE가 납니다. `else throw new UnsupportedProviderException()`이 필요합니다.

### 이메일에 제공자 이름을 붙이는 트릭

```java
@Override
public String getEmail() {
    return (String) attributes.get("email") + "GoogleOAuth2";   // a@b.com → a@b.comGoogleOAuth2
}
```

**의도**: 같은 이메일로 구글·카카오에 각각 가입할 수 있게 하려는 것입니다.
이메일 컬럼이 유니크하다면 접미사 없이는 두 번째 가입이 실패합니다.

**하지만 나쁜 방법입니다.**

1. **이메일이 더 이상 유효한 이메일이 아닙니다.** 이 계정으로는 메일을 보낼 수 없습니다.
2. `provider` 컬럼이 이미 있고, 조회도 `findByMemberBaseEmailAndProvider(email, provider)`로
   **두 값을 함께** 하고 있습니다. 즉 **접미사가 필요 없습니다.**
3. 사용자에게 이메일을 보여주면 `a@b.comGoogleOAuth2`가 노출됩니다.

**올바른 방법** — `(email, provider)` 복합 유니크 제약을 두고 이메일은 원본을 저장합니다:

```java
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"email", "provider"}))
```

### ⚠️ 닉네임 중복 확인 루프의 문제

```java
String generatedNickname = "";
while (true) {
    generatedNickname = NicknameGenerator.generateName();
    Optional<Member> member = memberRepository.findByMemberBase_Nickname(generatedNickname);
    if (member.isEmpty()) break;
}

if (user.isEmpty()) {          // ⚠️ 신규 가입일 때만 닉네임이 필요한데
    Member newUser = Member.builder().nickname(generatedNickname)...build();
```

문제 3가지:

1. **이미 가입한 사용자여도 루프를 돕니다.** 로그인할 때마다 불필요한 DB 조회가 발생합니다.
   `if (user.isEmpty())` 블록 **안으로** 옮겨야 합니다.
2. **종료 보장이 없습니다.** 조합이 24,273개인데 회원이 많아지면 루프가 길어지고,
   이론적으로 무한 루프가 가능합니다. 최대 시도 횟수를 두고 초과 시 숫자 접미사를 붙여야 합니다.
3. **경쟁 조건(race condition)**: 조회 시점엔 없었지만 저장 직전 다른 요청이 같은 닉네임을 만들 수 있습니다.
   근본 해결은 DB 유니크 제약 + 실패 시 재시도입니다.

```java
// 개선된 형태
if (user.isEmpty()) {
    String nickname = generateUniqueNickname();
    ...
}

private String generateUniqueNickname() {
    for (int i = 0; i < 10; i++) {
        String candidate = NicknameGenerator.generateName();
        if (memberRepository.findByMemberBase_Nickname(candidate).isEmpty()) return candidate;
    }
    return NicknameGenerator.generateName() + ThreadLocalRandom.current().nextInt(1000, 9999);
}
```

또 아래 빈 `if` 블록은 삭제해야 할 잔여 코드입니다:

```java
if (provider.equals("kakao")) {
}
```

## 6-5. `OAuth2LoginSuccessHandler` — 소셜 로그인 성공 후

```java
@Override
public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                    Authentication authentication) throws IOException {
    PrincipalDetails principalDetails = (PrincipalDetails) authentication.getPrincipal();
    String token = jwtProvider.createToken(principalDetails.getMember());
    ...
    String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);   // 한글 닉네임 처리

    String redirectUrl = "http://www.gaebang.site/auth/social?email=" + email
            + "&name=" + encodedName + "&token=" + token + "&userId=" + memberId
            + "&role=" + role + "&point=" + point + "&level=" + level;
    getRedirectStrategy().sendRedirect(request, response, redirectUrl);
}
```

**왜 리다이렉트인가?** OAuth2 흐름은 **브라우저 페이지 이동**으로 진행됩니다.
JSON을 응답해도 브라우저 주소창에 그냥 표시될 뿐 프론트 앱이 받을 수 없습니다.
그래서 쿼리 파라미터에 결과를 담아 프론트 페이지로 보냅니다.

**`URLEncoder.encode`가 필요한 이유**: 한글이나 `&`, `=` 같은 문자가 URL에 그대로 들어가면
파라미터 경계가 깨집니다. 퍼센트 인코딩(`%EA%B0%80`)으로 바꿔야 안전합니다.

### ⚠️ 문제

1. **토큰이 URL에 노출됩니다.** URL은 브라우저 히스토리·서버 액세스 로그·`Referer` 헤더에 기록됩니다.
   짧은 수명의 일회용 코드를 넘기고 프론트가 그걸로 토큰을 교환하는 방식이 안전합니다.
2. **리다이렉트 주소가 하드코딩되어 있고 `http`입니다.**
   로컬 개발 시 `localhost`로 보낼 수 없고, 평문 HTTP로 토큰이 전달됩니다.
   `@Value("${app.oauth2.redirect-url}")`로 빼고 `https`를 써야 합니다.

---

# 7. infrastructure/redis — 캐시

## 7-1. `RedisConfig` — 직렬화 설정이 핵심

```java
@Bean
public LettuceConnectionFactory redisConnectionFactory() {
    return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
}
```

**Lettuce vs Jedis**: 둘 다 Java Redis 클라이언트입니다.
Lettuce는 Netty 기반 **비동기·스레드 세이프**로, Spring Boot 2부터 기본값입니다.

```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf, ObjectMapper objectMapper) {
    RedisTemplate<String, Object> tpl = new RedisTemplate<>();
    tpl.setConnectionFactory(cf);

    tpl.setKeySerializer(new StringRedisSerializer());        // 키는 사람이 읽을 수 있게
    tpl.setHashKeySerializer(new StringRedisSerializer());

    ObjectMapper redisObjectMapper = objectMapper.copy();     // 원본을 훼손하지 않도록 복사
    redisObjectMapper.activateDefaultTyping(
        redisObjectMapper.getPolymorphicTypeValidator(),
        ObjectMapper.DefaultTyping.NON_FINAL);                // ★ 타입 정보를 JSON에 심는다

    GenericJackson2JsonRedisSerializer ser = new GenericJackson2JsonRedisSerializer(redisObjectMapper);
    tpl.setValueSerializer(ser);
    tpl.setHashValueSerializer(ser);
    tpl.afterPropertiesSet();
    return tpl;
}
```

**`StringRedisSerializer`를 키에 쓰는 이유**
기본 직렬화기는 `JdkSerializationRedisSerializer`로, 키가 바이너리가 되어
`redis-cli`에서 `\xac\xed\x00\x05...`처럼 읽을 수 없습니다. 문자열 직렬화기를 쓰면
`getBoards:v=1:cond=:p=0:s=10:sort=unsorted`처럼 **눈으로 확인·디버깅**이 가능합니다.

**`activateDefaultTyping`이 반드시 필요한 이유**
제네릭 타입 소거([기초개념 1-3](00-기초개념.md#1-3-제네릭-t--타입을-나중에-정하기)) 때문에
Redis에서 꺼낼 때 JSON만 보고는 어떤 클래스로 복원할지 알 수 없습니다.

```json
// 타입 정보가 없으면 → LinkedHashMap으로 복원됨 → ClassCastException
{ "content": [...], "totalPages": 3 }

// 타입 정보를 심으면 → 정확한 클래스로 복원됨
["com.gaebang.backend...PageResponse", { "content": [...], "totalPages": 3 }]
```

`objectMapper.copy()`로 복사본에만 적용한 것이 중요합니다. **원본에 적용하면 HTTP 응답 JSON에도
클래스 이름이 섞여 나갑니다.** 세심하게 처리한 부분입니다.

> ⚠️ 보안 주의: `activateDefaultTyping`은 역직렬화 취약점(임의 클래스 인스턴스화)의 원인이 될 수 있습니다.
> Redis에 우리 애플리케이션만 쓰기 때문에 실질 위험은 낮지만, 원리는 알고 있어야 합니다.
> `PolymorphicTypeValidator`를 쓰는 것이 그 완화책입니다.

## 7-2. `CacheVersion` — 버전 번호로 캐시를 한 번에 무효화

```java
@Component
@RequiredArgsConstructor
public class CacheVersion {
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY = "ver:getBoards";

    public String current() {
        String v = stringRedisTemplate.opsForValue().get(KEY);
        return (v != null) ? v : "1";        // 없으면 기본 "1"
    }

    public long bump() {
        Long nv = stringRedisTemplate.opsForValue().increment(KEY);   // Redis INCR — 원자적
        return (nv != null) ? nv : 1L;
    }
}
```

**해결하려는 문제**: 게시글 목록을 페이지·검색조건·정렬별로 캐시했다면 키가 수백 개입니다.
게시글 하나가 새로 등록되면 **그 키를 다 찾아 지워야** 합니다.
`KEYS getBoards:*` + `DEL`은 Redis를 블로킹시키는 위험한 방법입니다.

**해결책**: 키에 **버전 번호**를 포함시킵니다.

```
버전 1일 때 캐시된 키들:  getBoards:v=1:cond=:p=0:...   getBoards:v=1:cond=java:p=2:...
        ↓ 게시글 등록 → bump() → 버전 2
버전 2로 조회하면:        getBoards:v=2:cond=:p=0:...   ← 전부 캐시 MISS = 무효화됨
```

**옛 키를 지우지 않아도 됩니다.** TTL 3분이 지나면 Redis가 알아서 정리합니다.
`INCR`는 **원자적 연산**이라 동시 요청에도 안전합니다. **매우 좋은 패턴입니다.**

## 7-3. `Keys` — 캐시 키 생성 규칙

```java
public final class Keys {
    private Keys() {}          // 인스턴스화 방지 (유틸 클래스 관용구)

    public static String normalizeCond(String cond) {
        return cond == null ? "" : cond.trim().toLowerCase(Locale.ROOT);
    }

    public static String canonicalSort(Sort sort) {
        if (sort == null || sort.isUnsorted()) return "unsorted";
        return sort.stream()
            .map(o -> o.getProperty() + "," + o.getDirection().name())
            .sorted()                                       // ★ 정렬해서 순서를 고정
            .collect(Collectors.joining("|"));
    }

    public static String boardsKey(String ver, String cond, Pageable p) {
        return "getBoards:v=" + ver + ":cond=" + condHash(cond)
             + ":p=" + p.getPageNumber() + ":s=" + p.getPageSize()
             + ":sort=" + canonicalSort(p.getSort());
    }
}
```

**정규화(normalize)가 왜 중요한가**
`"Java"`, `"java "`, `"JAVA"`는 같은 검색입니다. 정규화하지 않으면 **키가 3개로 갈라져**
캐시 적중률이 떨어지고 메모리를 낭비합니다.

**`canonicalSort`에서 `.sorted()`를 호출하는 이유**
`sort=createdAt,DESC&sort=id,ASC`와 `sort=id,ASC&sort=createdAt,DESC`는
**결과가 다른 쿼리**지만, 스프링이 만든 `Sort` 객체의 순서 표현을 그대로 쓰면 키가 갈라집니다.
알파벳순으로 고정하면 같은 요청이 같은 키를 갖습니다.

**`final class` + `private` 생성자**: 상속과 인스턴스화를 모두 막는 정적 유틸 클래스 관용구입니다.

## 7-4. `PageResponse<T>` — `Page`를 직렬화 가능하게 감싸기

```java
public static <T> PageResponse<T> from(Page<T> page) { ... }      // Page → 저장용 DTO
public Page<T> toPageUsingRequest(Pageable requestPageable) {
    return new PageImpl<>(content, requestPageable, totalElements);
}
```

**왜 `Page`를 그대로 캐시하지 않는가?**
`PageImpl`은 **기본 생성자가 없고** Jackson이 역직렬화할 수 없습니다.
실제로 `PageImpl`을 직접 직렬화하면 스프링이 경고를 냅니다(향후 구조 변경 가능성).
그래서 필요한 필드만 담은 평범한 DTO로 옮겨 저장하고, 꺼낼 때 다시 `PageImpl`로 복원합니다.

**`toPageUsingRequest(pageable)`의 영리한 점**
복원할 때 **캐시에 저장된 pageable이 아니라 현재 요청의 pageable**을 씁니다.
캐시된 `Pageable` 객체를 그대로 되살리려 하면 `Sort` 내부 구조까지 복원해야 해서 복잡해지는데,
현재 요청 것을 쓰면 그 문제를 우회합니다. (키에 페이지 정보가 이미 포함돼 있으므로 값은 같습니다.)

## 7-5. `RedisCacheConfig` — 빈 파일

```java
public class RedisCacheConfig { }
```

내용이 없습니다. `@EnableCaching` + `@Cacheable`을 쓰려다 `RedisTemplate` 직접 사용으로
방향을 바꾼 흔적입니다. **삭제 대상입니다.**

> 참고로 `build.gradle`에 `spring-boot-starter-cache`가 있지만
> `@EnableCaching`이 어디에도 없어 실제로는 쓰이지 않습니다.
> `@Cacheable` 어노테이션 방식으로 가면 `BoardService`의 캐시 코드 30여 줄이 사라질 수 있습니다.
> (다만 `@Cacheable`은 프록시 기반이므로 [기초개념 4-4](00-기초개념.md#4-4-프록시--spring-마법의-정체)의 제약이 적용됩니다.)

---

# 8. util/S3 — 이미지 업로드

## 8-1. `S3Config`

```java
@Configuration
public class S3Config {
    private static Dotenv dotenv = Dotenv.load();          // ⚠️ 1-7 참조
    private final String accessKey = dotenv.get("ACCESS_KEY");

    @Bean
    public AmazonS3 amazonS3() {
        AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
        return AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withRegion(region)
                .build();
    }
}
```

**`com.amazonaws.*` = AWS SDK for Java **v1**입니다.**
`build.gradle`의 `spring-cloud-starter-aws:2.2.6.RELEASE`(2020년 릴리스)가 끌고 오는 것으로,
이 스타터는 **EOL(지원 종료)** 이고 SDK v1도 2025년에 지원이 끝났습니다.

**이전 대상**: `io.awspring.cloud:spring-cloud-aws-starter-s3` + SDK v2(`software.amazon.awssdk`)
v2는 논블로킹 지원, 더 작은 의존성, 페이지네이션 개선 등이 있습니다.

**액세스 키를 코드로 주입하는 것도 개선 여지가 있습니다.**
EC2에서 돌린다면 **IAM 역할(Instance Profile)** 을 붙이면 키를 아예 관리하지 않아도 됩니다.
`DefaultAWSCredentialsProviderChain`이 자동으로 찾아냅니다.

## 8-2. `S3ImageService`

```java
public String upload(MultipartFile image) {
    if (image.isEmpty() || Objects.isNull(image.getOriginalFilename())) throw new S3Exception();
    return this.uploadImage(image);
}

private void validateImageFileExtention(String filename) {
    String extention = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    List<String> allowed = Arrays.asList("jpg", "jpeg", "png", "gif");
    if (!allowed.contains(extention)) throw new S3Exception();
}
```

**확장자 검증의 한계**: 확장자는 **사용자가 자유롭게 바꿀 수 있습니다.**
`virus.exe`를 `virus.png`로 바꾸면 통과합니다. 실무에서는 파일 앞부분의
**매직 넘버(magic number)** 로 실제 형식을 확인합니다.
(이 프로젝트는 `tika-core` 의존성이 이미 있어서 `Tika().detect(bytes)`로 바로 할 수 있습니다.)

```java
String s3FileName = UUID.randomUUID().toString().substring(0, 10) + originalFilename;
```

**UUID 접두사를 붙이는 이유**: 같은 파일명으로 업로드하면 S3에서 덮어써집니다.
UUID로 충돌을 막습니다. 다만 `substring(0, 10)`으로 잘라내면 충돌 확률이 올라가고,
`originalFilename`을 그대로 붙이면 한글·공백·특수문자가 URL에서 문제가 됩니다.
**전체 UUID + 확장자만** 쓰는 것이 안전합니다.

```java
.withCannedAcl(CannedAccessControlList.PublicRead)      // 누구나 읽을 수 있게
```

이미지를 공개 URL로 제공하려는 설정입니다. 다만 2023년 4월 이후 생성된 S3 버킷은
**ACL이 기본 비활성화**되어 이 호출이 실패할 수 있습니다.
현대적 방법은 **버킷 정책 + CloudFront** 또는 **presigned URL**입니다.

```java
metadata.setContentType("image/" + extention);      // ⚠️ extention에 점(.)이 포함되어 있다
```

`extention`을 `originalFilename.substring(lastIndexOf("."))`로 구했으므로 `".png"`입니다.
따라서 `"image/.png"`라는 **잘못된 MIME 타입**이 저장됩니다.
브라우저가 이미지를 인식하지 못하거나 다운로드로 처리할 수 있습니다.

```java
// 수정
String ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
metadata.setContentType("image/" + ext);
```

**`try-finally`로 스트림을 닫는 부분**

```java
try {
    amazonS3.putObject(putObjectRequest);
} catch (Exception e) {
    throw new S3Exception();
} finally {
    byteArrayInputStream.close();
    is.close();
}
```

`finally`는 **예외가 나든 안 나든 반드시 실행**됩니다. 스트림·커넥션 같은 자원을 닫는 자리입니다.
다만 Java 7부터는 **try-with-resources**가 더 간결하고 안전합니다:

```java
try (InputStream is = image.getInputStream();
     ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
    amazonS3.putObject(...);
}   // 자동으로 close() 호출, 예외도 억제하지 않음
```

`encodeImageToBase64`에서는 실제로 try-with-resources를 쓰고 있습니다.

**`catch (Exception e) { throw new S3Exception(); }`의 문제**
원인 예외를 **버립니다.** 스택트레이스가 사라져 디버깅이 불가능해집니다.

```java
// 원인을 보존하는 형태
catch (Exception e) {
    log.error("S3 업로드 실패 - 파일: {}", s3FileName, e);
    throw new S3Exception();
}
```

## 8-3. `Base64DecodedMultipartFile` — 인터페이스 직접 구현 예제

```java
public class Base64DecodedMultipartFile implements MultipartFile {

    public Base64DecodedMultipartFile(String base64String, String contentType) {
        // "data:image/jpeg;base64,/9j/4AAQ..." → 콤마 뒤만 취한다
        String pureBase64 = base64String.startsWith("data:") ? base64String.split(",")[1] : base64String;
        this.content = Base64.getDecoder().decode(pureBase64);
        this.originalFilename = UUID.randomUUID() + "." + contentType.split("/")[1];
    }
    // getName, getSize, getBytes, getInputStream, transferTo ... 전부 구현
}
```

**왜 필요한가?** AI가 생성한 이미지는 **Base64 문자열**로 옵니다.
그런데 `S3ImageService.upload()`는 `MultipartFile`(HTTP 업로드 파일)을 받습니다.

두 가지 선택이 있었습니다:
- ① `S3ImageService`에 `byte[]`를 받는 메서드를 추가한다
- ② Base64를 `MultipartFile`처럼 보이게 감싼다 ← 이 프로젝트의 선택

②는 **어댑터 패턴(Adapter Pattern)** 입니다. 기존 코드를 건드리지 않고 새 입력을 끼워 넣습니다.
`MultipartFile`의 모든 메서드를 구현해야 하는 비용이 있지만, 재사용성이 좋습니다.

**`Base64`란**: 바이너리를 **텍스트로 안전하게 표현**하는 인코딩입니다.
JSON은 바이너리를 담을 수 없으므로 이미지를 API로 주고받을 때 씁니다.
64개 문자(A-Z a-z 0-9 + /)를 쓰며, 6비트를 한 글자로 표현하므로 **크기가 약 133%로 늘어납니다.**

---

# 9. 이 폴더에서 고쳐야 할 것 정리

우선순위 순서입니다.

| 우선순위 | 파일 | 문제 | 참조 |
|---|---|---|---|
| 🔴 1 | `SpringSecurityConfig` | `/api/boards/**` 등 **쓰기 API가 무인증**. HTTP 메서드 제한 없음 | [6-1 ③](#③-url별-권한--️-이-프로젝트-최대의-문제) |
| 🔴 2 | `JwtAuthorizationFilter` | 만료 토큰에 **500 응답**. `IllegalAccessError` 사용 | [5-6](#5-6-jwtauthorizationfilter--요청마다-토큰-검사-인가-authorization) |
| 🟠 3 | `JwtProperties` | 만료 40시간, 주석은 10분, 리프레시 무의미 | [5-2](#5-2-jwtproperties--만료-시간) |
| 🟠 4 | `GlobalExceptionRestAdvice` | `IllegalArgumentException`·업로드 초과가 500으로 나감 | [3-3](#️-문제--runtimeexception-포괄-핸들러가-400을-500으로-만든다) |
| 🟠 5 | `AuthConfig` | `PasswordEncoder` 빈 중복 → 순환 참조 위험 | [6-2](#️-문제--passwordencoder-빈이-자기-자신을-참조) |
| 🟠 6 | `PrincipalDetails` | OAuth 생성자가 `attributes`를 버림 | [6-3](#️-문제--생성자에서-attributes를-버린다) |
| 🟡 7 | `PrincipalOauth2UserService` | 닉네임 루프 위치·종료보장, 이메일 접미사 트릭, 빈 `if` | [6-4](#️-닉네임-중복-확인-루프의-문제) |
| 🟡 8 | `S3ImageService` | `Content-Type`이 `image/.png`로 잘못 설정됨 | [8-2](#8-2-s3imageservice) |
| 🟡 9 | `AiMemberInitializer` | ID 999 가정 → 재기동마다 계정 증식 가능 | [1-6](#1-6-aimemberinitializer--기동-시-한-번-실행되는-초기화) |
| 🟡 10 | `OAuth2LoginSuccessHandler` | 토큰이 URL에 노출, 리다이렉트 주소 하드코딩 + `http` | [6-5](#6-5-oauth2loginsuccesshandler--소셜-로그인-성공-후) |
| 🟢 11 | 5개 클래스 | `Dotenv.load()` static 호출 → `@Value`로 통일 | [1-7](#1-7-공통-문제--dotenvload를-static-필드에서-호출) |
| 🟢 12 | `RedisCacheConfig` | 빈 파일 → 삭제 | [7-5](#7-5-rediscacheconfig--빈-파일) |
| 🟢 13 | `CustomResponseUtil`, `JwtAuthenticationFilter` | `new ObjectMapper()` 반복 생성 → 빈 주입 | [4-2](#4-2-customresponseutil--필터에서-직접-json-쓰기) |
| 🟢 14 | `EmailConfig` | `mail.debug=true` 운영에서 끄기 | [1-5](#1-5-emailconfig--gmail-smtp) |

---

# 10. 이 폴더에서 잘 만든 것

배울 만한 부분도 분명히 있습니다.

| 항목 | 왜 좋은가 |
|---|---|
| `ErrorCode` + `ApplicationException` 3층 구조 | 던지는 쪽이 HTTP를 몰라도 됨. 메시지가 한 곳에 모임 |
| 커스텀 예외를 모두 `RuntimeException` 기반으로 | `@Transactional` 롤백 규칙과 정확히 맞음 |
| `CacheVersion` 버전 키 무효화 | `KEYS`+`DEL` 없이 원자적으로 전체 무효화. 실무 패턴 |
| `objectMapper.copy()` 후 Redis 전용 타이핑 적용 | HTTP 응답 JSON을 오염시키지 않는 세심한 처리 |
| `Keys.normalizeCond` / `canonicalSort` | 캐시 키 갈라짐을 막는 정석적인 정규화 |
| `OAuth2UserInfo` 전략 패턴 | 제공자 추가 시 기존 코드 수정 불필요 |
| 401/403 구분 처리 | `authenticationEntryPoint` vs `accessDeniedHandler`를 정확히 이해 |
| 스레드풀 3개 분리 + 이름 접두사 | 벌크헤드 격리. 로그 추적성도 확보 |
| `getAsyncUncaughtExceptionHandler` 등록 | 비동기 예외가 조용히 사라지는 것을 막음 |
| `spring.web.resources.add-mappings: false` | `NoHandlerFoundException`을 살려 404를 정확히 응답 |
| `open-in-view: false` (prod) | DB 커넥션 점유 시간 단축. 정석 |

---

## 다음 문서

- [domain/member.md](domain/member.md) — 이 문서의 `PrincipalDetails`, `AuthConfig`가 실제로 쓰이는 곳
- [domain/point.md](domain/point.md) — 트랜잭션과 동시성의 교과서 사례
- [README.md](README.md) — 전체 인덱스
