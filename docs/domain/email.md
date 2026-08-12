# email — 이메일 인증 (싱글턴 빈의 상태 관리)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [global.md](../global.md) ([1-5 EmailConfig](../global.md#1-5-emailconfig--gmail-smtp))
>
> 파일 8개, 코드 130줄의 작은 도메인입니다.
> **"스프링 빈은 싱글턴이다"** 라는 사실을 잊으면 어떤 일이 벌어지는지 보여주는 사례이고,
> [member.md 7장](member.md#7--치명적-누구나-남의-비밀번호를-바꿀-수-있다)의 계정 탈취 취약점의 **다른 절반**이 여기 있습니다.

---

## 파일 지도

```
domain/email/
├── controller/EmailController.java
├── dto/
│   ├── request/EmailRequest.java              { email }
│   ├── request/EmailVerifyCodeRequest.java     { email, code }
│   └── response/EmailVerifiedResponse.java     { emailVerified: boolean }
├── exception/
│   ├── EmailSendingException.java
│   ├── EmailTemplateLoadException.java
│   └── InvalidEmailCodeException.java
└── service/EmailService.java                   ★ 이 파일이 핵심

관련 파일 (다른 폴더)
├── global/config/EmailConfig.java              JavaMailSender 빈 설정
└── resources/templates/email_template.html     메일 본문 HTML
```

**기능**: 이메일로 8자리 인증번호를 보내고, 사용자가 입력한 값과 비교합니다.
회원가입과 비밀번호 찾기에서 사용하도록 만들어졌습니다.

---

# 1. 스프링 빈은 싱글턴이다 — 이 도메인의 모든 문제의 출발점

## 1-1. 먼저 원리

```java
@Service
public class EmailService { ... }
```

이 클래스의 인스턴스는 **애플리케이션 전체에 딱 하나** 만들어집니다.
스프링 빈의 기본 스코프가 `singleton`이기 때문입니다.

```
[요청 1] ─┐
[요청 2] ─┼──▶ 같은 EmailService 인스턴스 하나
[요청 3] ─┘     (서로 다른 스레드가 동시에 진입)
```

**그래서 빈은 상태(mutable field)를 가지면 안 됩니다.**
컨트롤러·서비스·리포지토리가 필드로 갖는 것은 보통 다른 빈(불변 참조)뿐입니다.

```java
@Service
@RequiredArgsConstructor
public class BoardService {
    private final BoardRepository boardRepository;    // final + 불변 참조 → 안전
    private final PointService pointService;
}
```

**메서드 안의 지역 변수는 스레드마다 별개**(스택에 할당)이므로 안전합니다.
위험한 것은 **인스턴스 필드**입니다.

## 1-2. 이 프로젝트가 위반한 지점

```java
@Service
@RequiredArgsConstructor
@Transactional
public class EmailService {

    private final JavaMailSender javaMailSender;

    private final Map<String, String> verificationCodes = new HashMap<>();   // 🔴 여기
    //            ↑ 여러 스레드가 동시에 읽고 쓰는 가변 상태
```

`{ "user@gmail.com" → "a7Bx9k2Q" }` 형태로 인증번호를 **애플리케이션 메모리에** 담아둡니다.

```java
public String sendVerificationEmail(String to) throws Exception {
    String authCode = generateAuthCode();
    MimeMessage message = createMessage(to, authCode);
    try {
        javaMailSender.send(message);
        verificationCodes.put(to, authCode);        // 🔴 쓰기
        return authCode;
    } catch (MailException ex) {
        throw new EmailSendingException();
    }
}

public ResponseDTO<EmailVerifiedResponse> verifyEmailCode(String email, String code) {
    String storedCode = verificationCodes.get(email);   // 🔴 읽기
    ...
}
```

---

# 2. `HashMap`을 쓴 것이 만드는 문제 6가지

## 2-1. 🔴 스레드 안전하지 않다

`HashMap`은 **동시 수정에 대한 보호가 전혀 없습니다.**

여러 스레드가 동시에 `put()`을 호출하면:

- **데이터 유실**: 두 스레드가 같은 버킷에 동시에 쓰면 한쪽이 사라집니다
- **크기 값 오류**: `size` 카운터가 어긋납니다
- **리사이징 중 무한 루프**: `HashMap`이 내부 배열을 확장(resize)하는 도중 다른 스레드가
  삽입하면, Java 7까지는 연결 리스트에 **순환 참조**가 만들어져 `get()`이 **영원히 반환하지 않았습니다.**
  CPU 100%로 스레드가 멈추는 유명한 장애 패턴입니다.
  Java 8부터 리사이징 구현이 바뀌어 무한 루프는 사실상 사라졌지만,
  **데이터 유실과 상태 손상은 여전히 발생합니다.**

**해결 후보**

| 선택 | 특징 |
|---|---|
| `Collections.synchronizedMap(new HashMap<>())` | 모든 연산에 락. 단순하지만 느림 |
| `ConcurrentHashMap` | 버킷 단위 락. 읽기는 락 없음. **자바 표준 해답** |
| **Redis** | 프로세스 밖에 저장. **이 프로젝트의 정답** (아래 설명) |

## 2-2. 🔴 인증번호가 영원히 만료되지 않는다

```java
verificationCodes.put(to, authCode);   // 넣기만 하고 지우는 코드가 없다
```

`remove()`를 호출하는 곳이 **어디에도 없습니다.**

```java
public ResponseDTO<EmailVerifiedResponse> verifyEmailCode(String email, String code) {
    String storedCode = verificationCodes.get(email);

    if (storedCode != null && storedCode.equals(code)) {
        return ResponseDTO.okWithData(new EmailVerifiedResponse(true), "이메일 인증에 성공 했습니다.");
        // ⚠️ 성공했는데 지우지 않는다 → 같은 코드를 무한히 재사용 가능
    }
    ...
}
```

**두 가지 결과**

1. **인증번호가 무기한 유효합니다.** 3개월 전에 받은 메일의 코드로 지금 인증할 수 있습니다.
   메일함이 노출되면 시간 제한 없이 악용됩니다.
2. **일회용이 아닙니다.** 같은 코드로 몇 번이든 인증을 통과할 수 있습니다.

**인증번호의 기본 요건**

> **① 짧은 만료 시간 (3~5분)** — 노출 창을 최소화
> **② 일회용** — 사용 즉시 폐기
> **③ 추측 불가** — 암호학적 난수
> **④ 시도 횟수 제한** — 무차별 대입 차단

**넷 다 지켜지지 않았습니다.**

## 2-3. 🔴 메모리 누수

지워지지 않으므로 맵이 **계속 자랍니다.**

```
가입 시도 10만 건 → 맵에 10만 개 엔트리 (이메일 + 8자 코드)
→ 재기동할 때까지 절대 해제되지 않음 → 힙 압박 → 결국 OutOfMemoryError
```

메모리 캐시를 직접 만들 때는 **반드시 제거 정책(eviction)** 이 있어야 합니다.
`ConcurrentHashMap`으로 바꿔도 이 문제는 남습니다.

## 2-4. 🔴 재기동하면 전부 사라진다

배포할 때마다 진행 중인 모든 인증이 무효가 됩니다.
사용자는 "인증번호가 틀렸습니다"만 보고 이유를 알 수 없습니다.

## 2-5. 🔴 서버를 2대로 늘리면 동작하지 않는다

이것이 가장 결정적입니다.

```
              [로드밸런서]
             /            \
    [서버 A]                [서버 B]
  메모리 맵 A               메모리 맵 B
  (독립)                    (독립)

① POST /api/email/confirmation  → 서버 A로 라우팅 → 맵 A에 코드 저장
② POST /api/email/verify        → 서버 B로 라우팅 → 맵 B에는 없음 → 인증 실패 ❌
```

**요청이 같은 서버로 갈 보장이 없습니다.** 지금은 EC2 1대라 드러나지 않지만,
**수평 확장이 원리적으로 불가능한 구조**입니다.

> 이 프로젝트는 **이미 Redis를 쓰고 있습니다** (`RedisConfig`, `CacheVersion`, 게시글 캐시).
> 인증번호는 Redis에 넣기에 완벽한 데이터입니다:
> - TTL 기능이 내장되어 있어 만료가 자동 (2-2, 2-3 해결)
> - 프로세스 밖에 있어 재기동에 안전 (2-4 해결)
> - 모든 서버가 공유 (2-5 해결)
> - 단일 스레드 모델이라 원자성 보장 (2-1 해결)
>
> **다섯 문제가 Redis 한 줄로 전부 사라집니다.**

## 2-6. 🟠 `@Transactional`이 무의미하다

```java
@Service
@RequiredArgsConstructor
@Transactional              // ⚠️ 이 클래스는 DB를 전혀 건드리지 않는다
public class EmailService {
```

리포지토리가 하나도 없고 JPA 엔티티도 다루지 않습니다.
트랜잭션을 열 이유가 없는데 **모든 메서드가 DB 커넥션을 점유합니다.**

```
sendVerificationEmail() 호출
  → 트랜잭션 시작 → 커넥션 풀에서 커넥션 1개 대출
  → SMTP 메일 발송 (수백 ms ~ 수 초 소요!)
  → 트랜잭션 커밋 → 커넥션 반납
```

**메일 발송처럼 느린 I/O 동안 DB 커넥션을 붙잡는 것은 명백한 낭비입니다.**
HikariCP 풀이 30개인데 동시 메일 발송이 30건이면 다른 모든 요청이 커넥션을 기다립니다.

**`@Transactional`을 지우면 됩니다.**

> 일반 원칙: **트랜잭션 안에서 외부 I/O(HTTP 호출, 메일 발송, 파일 업로드)를 하지 마세요.**
> 이 프로젝트는 `Gemini/OpenaiQuestionService`에서 이 원칙을 의식하고 있습니다
> ("AI API 호출은 트랜잭션 외부에서 처리" 주석). 여기서는 놓쳤습니다.

---

# 3. `generateAuthCode` — 난수 생성의 문제

```java
private String generateAuthCode() {
    Random random = new Random();                    // ⚠️ 매번 새로 생성 + 보안용이 아님
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < 8; i++) {
        int index = random.nextInt(3);
        switch (index) {
            case 0: key.append((char) (random.nextInt(26) + 97)); break;   // a-z
            case 1: key.append((char) (random.nextInt(26) + 65)); break;   // A-Z
            case 2: key.append(random.nextInt(10));               break;   // 0-9
        }
    }
    return key.toString();
}
```

## 3-1. 문자 코드 계산 읽기

```java
(char) (random.nextInt(26) + 97)     // 97 = 'a'  → 97~122 = a~z
(char) (random.nextInt(26) + 65)     // 65 = 'A'  → 65~90  = A~Z
random.nextInt(10)                    // 0~9 (int라서 문자열 연결 시 "0"~"9")
```

ASCII 코드 산술입니다. 동작은 하지만 의도가 잘 드러나지 않습니다.

```java
// 더 읽기 쉬운 형태
private static final String CHARSET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
// ↑ 혼동되는 문자 제외: 0/O, 1/l/I  ← 사용자가 메일을 보고 손으로 입력하므로 중요
```

## 3-2. 🟠 `Random`은 암호학적으로 안전하지 않다

`java.util.Random`은 **선형 합동 생성기(LCG)** 입니다.

- 시드가 **48비트**뿐입니다
- **연속된 출력 2개를 관찰하면 내부 상태를 복원**하고 이후 모든 값을 예측할 수 있습니다
- 시드는 기본적으로 `System.nanoTime()` 기반이라 **생성 시점을 추정하면 좁혀집니다**

인증번호는 **보안 토큰**입니다. 예측 가능하면 안 됩니다.

```java
// 반드시 SecureRandom을 사용
private static final SecureRandom SECURE_RANDOM = new SecureRandom();   // static final로 재사용

private String generateAuthCode() {
    StringBuilder sb = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
        sb.append(CHARSET.charAt(SECURE_RANDOM.nextInt(CHARSET.length())));
    }
    return sb.toString();
}
```

**`static final`로 두는 이유**: `SecureRandom` 인스턴스 생성은 엔트로피 소스 초기화를 포함해
비용이 큽니다. 그리고 `SecureRandom`은 **스레드 세이프**하므로 공유해도 안전합니다.
반면 `new Random()`을 매 호출마다 만드는 것은 [`NicknameGenerator`가 `ThreadLocalRandom`을 쓴 이유](../global.md#4-3-nicknamegenerator--랜덤-한글-닉네임)와 반대되는 실수입니다.

## 3-3. 🟠 시도 횟수 제한이 없다

8자리 영숫자면 조합이 충분하지만, **무한히 시도할 수 있다면 무의미합니다.**

```bash
# 인증번호를 계속 찍어볼 수 있다
for code in $(생성기); do
  curl -X POST .../api/email/verify -d "{\"email\":\"victim@x.com\",\"code\":\"$code\"}"
done
```

Redis로 옮기면 시도 횟수도 함께 셀 수 있습니다.

```java
String attemptKey = "email:attempt:" + email;
Long attempts = redisTemplate.opsForValue().increment(attemptKey);
if (attempts == 1) redisTemplate.expire(attemptKey, Duration.ofMinutes(5));
if (attempts > 5) {
    redisTemplate.delete("email:code:" + email);    // 코드 자체를 무효화
    throw new TooManyVerifyAttemptsException();
}
```

## 3-4. 🔴 발송 횟수 제한도 없다

```java
@PostMapping("/confirmation")
public ResponseEntity<ResponseDTO<Void>> mailConfirm(@RequestBody EmailRequest emailRequest)
        throws Exception {
    emailService.sendVerificationEmail(emailRequest.email());
    ...
}
```

이 API는 `permitAll`(`/api/email/**`)이고 제한이 없습니다.

```bash
# 남의 메일함에 메일 폭탄을 보낼 수 있다
while true; do
  curl -X POST https://gaebang.site/api/email/confirmation \
       -d '{"email":"victim@gmail.com"}'
done
```

**두 가지 피해**

1. **피해자 메일함 폭격** (이 서비스가 스팸 발신자로 신고됨 → Gmail 계정 차단)
2. **Gmail SMTP 일일 한도 소진** (무료 계정 하루 500통) → **정상 사용자가 인증 메일을 못 받음**

이 프로젝트에는 이미 도배 방지 서비스가 있습니다:

```java
// community/service/PostRateLimitService.java — 5분 내 게시글 3개 제한
```

**같은 방식을 재사용하면 됩니다.**

---

# 4. `verifyEmailCode` — 코드 읽기

```java
public ResponseDTO<EmailVerifiedResponse> verifyEmailCode(String email, String code) {
    String storedCode = verificationCodes.get(email);
    EmailVerifiedResponse response = null;

    if (storedCode != null && storedCode.equals(code)) {
        response = new EmailVerifiedResponse(true);
        return ResponseDTO.okWithData(response, "이메일 인증에 성공 했습니다.");
    } else {
        new EmailVerifiedResponse(false);        // 🟢 만들어놓고 버린다 (죽은 코드)
        throw new InvalidEmailCodeException();
    }
}
```

## 4-1. 🟢 `new EmailVerifiedResponse(false);` — 객체를 만들고 버림

변수에 대입하지도, 반환하지도 않습니다. **아무 효과 없는 문장**입니다.
`response = new EmailVerifiedResponse(false);`로 쓰려다 만 흔적으로 보입니다.

바로 다음 줄에서 예외를 던지므로 이 객체는 필요 없습니다. **삭제 대상입니다.**

## 4-2. 🟠 `storedCode.equals(code)` — 타이밍 공격과 순서

```java
if (storedCode != null && storedCode.equals(code))
```

`storedCode != null`을 먼저 검사한 것은 **NPE 방어로 올바릅니다.**
(`&&`는 왼쪽이 `false`면 오른쪽을 평가하지 않습니다 — **단축 평가(short-circuit)**)

더 안전한 관용구는 **상수를 앞에 두는 것**입니다:

```java
"expected".equals(입력값)     // 입력값이 null이어도 NPE가 나지 않는다
```

> **참고 — 타이밍 공격(timing attack)**
> `String.equals`는 첫 글자가 다르면 즉시 `false`를 반환합니다.
> 즉 **일치하는 접두사가 길수록 비교 시간이 미세하게 길어집니다.**
> 이론적으로 응답 시간을 측정해 한 글자씩 알아낼 수 있습니다.
>
> 비밀 값 비교는 **길이에 무관하게 항상 같은 시간이 걸리는** 함수를 씁니다:
> ```java
> java.security.MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8));
> ```
> 네트워크 지터 때문에 8자리 코드에 실제로 성공하기는 어렵지만,
> **비밀 비교에는 이 함수를 쓴다**는 습관을 들이는 것이 좋습니다.

## 4-3. 🟠 서비스가 `ResponseDTO`를 반환한다

```java
public ResponseDTO<EmailVerifiedResponse> verifyEmailCode(String email, String code) {
```

`MemberService.getMemberInfo`와 같은 계층 위반입니다.
→ [member.md 3-8](member.md#3-8-서비스가-responsedto를-반환하는-문제)

## 4-4. 🟠 컨트롤러의 `throws Exception`

```java
@PostMapping("/confirmation")
public ResponseEntity<ResponseDTO<Void>> mailConfirm(@RequestBody EmailRequest emailRequest)
        throws Exception {                                              // ⚠️
```

`sendVerificationEmail`이 `throws Exception`을 선언했기 때문에 전파된 것입니다.

**`throws Exception`은 "무슨 예외가 날지 모른다"는 선언**이라 정보가 없습니다.
근본 원인은 `createMessage`가 던지는 `MessagingException`, `UnsupportedEncodingException`입니다.
서비스 안에서 잡아 도메인 예외로 감싸면 컨트롤러가 깨끗해집니다.

```java
private MimeMessage createMessage(String to, String authCode) {
    try {
        ...
        helper.setFrom(new InternetAddress(setFrom, "개발자의 방주", "UTF-8"));
        ...
        return message;
    } catch (MessagingException | UnsupportedEncodingException e) {
        log.error("메일 메시지 생성 실패 - 수신자: {}", to, e);
        throw new EmailSendingException();          // ApplicationException 계열 → 전역 핸들러가 처리
    }
}
```

## 4-5. 🟠 `@Valid`가 빠져 있다

```java
public ResponseEntity<...> mailConfirm(@RequestBody EmailRequest emailRequest)      // @Valid 없음
public ResponseEntity<...> verifyEmail(@RequestBody EmailVerifyCodeRequest req)      // @Valid 없음
```

DTO에는 검증 어노테이션이 **잘 붙어 있습니다**:

```java
public record EmailRequest(
        @NotBlank(message = "이메일은 필수 항목입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        String email
) {}
```

**그런데 `@Valid`가 없으므로 이 검증이 실행되지 않습니다.**
빈 문자열이나 `"asdf"` 같은 값으로도 메일 발송이 시도됩니다.
→ [기초개념 5-3](../00-기초개념.md#5-3-valid--검증은-dto에-선언한다)

```java
public ResponseEntity<...> mailConfirm(@Valid @RequestBody EmailRequest emailRequest) { ... }
```

> 이 프로젝트에서 `@Valid` 누락은 `EmailController`, `BoardController`,
> `ConversationController` 등 여러 곳에 있습니다. 반대로 `MemberController`는 잘 붙였습니다.

---

# 5. `createMessage` / `loadEmailTemplate` — 자원 관리

```java
private String loadEmailTemplate(String templateName) {
    try {
        Resource resource = new ClassPathResource("templates/" + templateName);
        InputStream inputStream = resource.getInputStream();        // ⚠️ 닫지 않는다
        byte[] templateBytes = inputStream.readAllBytes();
        return new String(templateBytes, "UTF-8");
    } catch (IOException ex) {
        throw new EmailTemplateLoadException();
    }
}
```

## 5-1. 🟠 `InputStream`을 닫지 않는다 — 자원 누수

`close()`를 호출하지 않습니다. `ClassPathResource`는 JAR 안의 파일을 열므로
**파일 디스크립터가 반환되지 않습니다.**

메일을 보낼 때마다 하나씩 새며, OS 한도(보통 프로세스당 1024개)에 도달하면
`Too many open files`로 애플리케이션 전체가 마비됩니다.

**try-with-resources를 쓰세요.**

```java
private String loadEmailTemplate(String templateName) {
    try (InputStream is = new ClassPathResource("templates/" + templateName).getInputStream()) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
        log.error("메일 템플릿 로드 실패 - {}", templateName, ex);
        throw new EmailTemplateLoadException();
    }
}
```

`try (...)` 괄호 안에서 만든 자원은 **블록을 벗어날 때 자동으로 `close()`** 됩니다.
예외가 나도 닫히고, `close()` 자체에서 예외가 나도 원래 예외를 덮지 않습니다.
(`AutoCloseable`을 구현한 모든 객체에 쓸 수 있습니다.)

## 5-2. 🟢 매번 파일을 읽는다

템플릿 HTML은 **절대 바뀌지 않습니다**(JAR 안에 들어 있으므로).
메일 발송마다 파일을 읽을 이유가 없습니다.

```java
@Service
public class EmailService {

    /** 템플릿은 기동 시 한 번만 읽는다 */
    private final String emailTemplate;

    public EmailService(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
        this.emailTemplate = loadEmailTemplate("email_template.html");
    }
}
```

**이것은 안전한 인스턴스 필드입니다.** `final`이고 불변(`String`)이므로
[1-1](#1-1-먼저-원리)의 싱글턴 문제가 발생하지 않습니다.
**"빈은 상태를 갖지 말라"는 원칙은 정확히는 "가변 상태를 갖지 말라"입니다.**

## 5-3. `{{authCode}}` 치환 방식

```java
emailTemplate = emailTemplate.replace("{{authCode}}", authCode);
```

간단한 문자열 치환입니다. 값이 서버가 만든 영숫자 코드뿐이라 **지금은 안전합니다.**

> 만약 사용자 입력(닉네임 등)을 템플릿에 넣게 되면 **HTML 인젝션** 위험이 생깁니다.
> 그때는 Thymeleaf 같은 템플릿 엔진을 쓰거나 HTML 이스케이프를 해야 합니다.
> (Thymeleaf는 기본적으로 이스케이프합니다.)

## 5-4. 🟢 설정 값을 읽는 방식이 두 갈래다

```java
// EmailService — Spring의 @Value 사용 ✅
@Value("${TEST_ID}")
private String TEST_ID_EMAIL;

// EmailConfig — Dotenv 직접 호출 ⚠️
private static Dotenv dotenv = Dotenv.load();
private static final String TEST_ID = dotenv.get("TEST_ID");
```

**같은 값을 두 가지 방법으로 읽고 있습니다.** `@Value`가 동작한다는 것은
`.env`가 이미 스프링 프로퍼티로 로드되어 있다는 증거이므로,
`EmailConfig`의 `Dotenv.load()`는 불필요합니다.
→ [global 1-7](../global.md#1-7-공통-문제--dotenvload를-static-필드에서-호출)

> 참고로 `TEST_ID`라는 이름도 문제입니다. 실제로는 **서비스 발신 계정**인데
> 이름은 "테스트 아이디"입니다. `MAIL_USERNAME`, `MAIL_PASSWORD`가 적절합니다.
> `@Value` 필드명도 자바 관례상 `mailFromAddress`처럼 camelCase여야 합니다.

---

# 6. 🔴 이 도메인이 지키지 못한 것 — 비밀번호 재설정과의 연결

**이 부분이 이 문서에서 가장 중요합니다.**

## 6-1. 인증 성공이 아무것도 남기지 않는다

```java
public ResponseDTO<EmailVerifiedResponse> verifyEmailCode(String email, String code) {
    if (storedCode != null && storedCode.equals(code)) {
        return ResponseDTO.okWithData(new EmailVerifiedResponse(true), "이메일 인증에 성공 했습니다.");
        //                                                   ↑ boolean 하나
    }
}
```

응답은 `{ "emailVerified": true }`입니다. **`true`라는 사실 말고는 아무것도 없습니다.**

서버는 "누가 언제 어떤 이메일을 인증했다"는 사실을 **기록하지 않습니다.**
`verificationCodes` 맵에서 코드를 지우지도 않습니다.

## 6-2. 그래서 다음 단계를 막을 수 없다

프론트엔드가 이렇게 호출하도록 설계됐습니다:

```
① POST /api/email/confirmation   { email }             → 인증번호 발송
② POST /api/email/verify         { email, code }       → { emailVerified: true }
③ GET  /api/member/id?email=...                        → { userId: 42 }
④ PATCH /api/member/password/user/42  { newPassword }  → 비밀번호 변경
```

**④는 ②가 성공했는지 확인할 방법이 없습니다.**
서버 어디에도 "이 사람은 이메일 인증을 통과했다"는 기록이 없으니까요.

```bash
# ①②를 건너뛰고 ③④만 호출 → 계정 탈취
curl "https://gaebang.site/api/member/id?email=victim@gmail.com"
curl -X PATCH "https://gaebang.site/api/member/password/user/42" \
     -d '{"newPassword":"attacker1234"}'
```

전체 시나리오와 상세 대응은 [member.md 7장](member.md#7--치명적-누구나-남의-비밀번호를-바꿀-수-있다)에 있습니다.

## 6-3. 이 도메인 쪽에서의 해결 — 재설정 토큰 발급

인증 성공 시 **서버가 증거를 발급하고 보관**해야 합니다.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CHARSET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final Duration CODE_TTL        = Duration.ofMinutes(5);
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(10);
    private static final int MAX_SEND_PER_HOUR    = 5;
    private static final int MAX_VERIFY_ATTEMPTS  = 5;

    private final JavaMailSender javaMailSender;
    private final StringRedisTemplate redis;          // ★ HashMap 대신 Redis

    @Value("${MAIL_USERNAME}")
    private String fromAddress;

    private final String emailTemplate;

    public EmailService(JavaMailSender javaMailSender, StringRedisTemplate redis) {
        this.javaMailSender = javaMailSender;
        this.redis = redis;
        this.emailTemplate = loadEmailTemplate("email_template.html");   // 기동 시 1회
    }

    /** 인증번호 발송 — 시간당 5회 제한, 5분 TTL */
    public void sendVerificationEmail(String to) {
        checkSendRateLimit(to);

        String authCode = generateAuthCode();
        javaMailSender.send(createMessage(to, authCode));

        redis.opsForValue().set(codeKey(to), authCode, CODE_TTL);        // TTL 자동 만료 ✅
        redis.delete(attemptKey(to));                                    // 시도 횟수 초기화
        log.info("인증번호 발송 완료 - 수신자: {}", to);
    }

    /** 인증번호 확인 — 성공 시 일회용 재설정 토큰 발급 */
    public EmailVerifiedResponse verifyEmailCode(String email, String code) {
        checkVerifyAttempts(email);

        String stored = redis.opsForValue().get(codeKey(email));
        if (stored == null || !MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidEmailCodeException();
        }

        redis.delete(codeKey(email));          // ★ 일회용 — 즉시 폐기 ✅
        redis.delete(attemptKey(email));

        String resetToken = UUID.randomUUID().toString();
        redis.opsForValue().set(resetTokenKey(resetToken), email, RESET_TOKEN_TTL);   // ★ 증거 발급 ✅

        return new EmailVerifiedResponse(true, resetToken);
    }

    /** MemberService가 호출 — 토큰이 유효하면 대상 이메일을 반환하고 토큰을 폐기한다 */
    public String consumeResetToken(String resetToken) {
        String key = resetTokenKey(resetToken);
        String email = redis.opsForValue().get(key);
        if (email == null) {
            throw new InvalidResetTokenException();
        }
        redis.delete(key);                     // ★ 일회용 ✅
        return email;
    }

    // ---------- 내부 ----------

    private void checkSendRateLimit(String email) {
        String key = "email:sendRate:" + email;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > MAX_SEND_PER_HOUR) {
            throw new EmailRateLimitExceededException();
        }
    }

    private void checkVerifyAttempts(String email) {
        Long attempts = redis.opsForValue().increment(attemptKey(email));
        if (attempts != null && attempts == 1L) {
            redis.expire(attemptKey(email), CODE_TTL);
        }
        if (attempts != null && attempts > MAX_VERIFY_ATTEMPTS) {
            redis.delete(codeKey(email));      // 무차별 대입 감지 → 코드 자체를 무효화
            throw new TooManyVerifyAttemptsException();
        }
    }

    private String generateAuthCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CHARSET.charAt(SECURE_RANDOM.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }

    private String codeKey(String email)        { return "email:code:" + email; }
    private String attemptKey(String email)     { return "email:attempt:" + email; }
    private String resetTokenKey(String token)  { return "pwreset:" + token; }
}
```

```java
// 응답 DTO에 토큰 추가
public record EmailVerifiedResponse(
        Boolean emailVerified,
        String resetToken            // 비밀번호 재설정 시 이 값을 제출해야 한다
) {}
```

```java
// MemberService — 이제 userId가 아니라 토큰으로 대상을 정한다
@Transactional
public void resetPassword(ResetPasswordRequestDto dto) {
    String email = emailService.consumeResetToken(dto.resetToken());   // ★ 서버가 대상 결정 ✅

    Member member = memberRepository.findByMemberBaseEmail(email)
            .orElseThrow(UserNotFoundException::new);
    member.getMemberBase().changePassword(passwordEncoder.encode(dto.newPassword()));
    // save() 불필요 — 영속 상태이므로 더티 체킹이 처리
}
```

**핵심은 "클라이언트가 대상을 지정하지 못하게" 만드는 것입니다.**
`userId`를 URL로 받는 대신, 서버가 자기 저장소에서 "이 토큰의 주인은 누구"인지 확인합니다.

## 6-4. 회원가입 쪽도 마찬가지다

지금 `MemberService.signup`은 **이메일 인증을 확인하지 않습니다.**
즉 남의 이메일로 아무나 가입할 수 있습니다.

```java
// 개선 — 가입 시에도 인증 토큰을 요구
public record SignUpRequestDto(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank String emailVerifiedToken          // ★ 추가
) {}

public SignUpResponseDto signup(SignUpRequestDto dto) {
    String verifiedEmail = emailService.consumeResetToken(dto.emailVerifiedToken());
    if (!verifiedEmail.equals(dto.email())) {
        throw new EmailNotMatchException();           // 인증한 이메일과 가입 이메일이 다름
    }
    ...
}
```

> `ErrorCode`에 `EMAIL_NOT_MATCH("이메일이 일치하지 않습니다")`가 이미 있고
> `EmailNotMatchException`도 있습니다. **이런 검증을 하려던 흔적**으로 보이는데,
> 실제로 이 예외를 던지는 코드는 프로젝트 어디에도 없습니다.

---

# 7. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | 인증 성공이 서버에 아무 증거를 남기지 않음 → 비밀번호 재설정 우회 가능 | [6장](#6--이-도메인이-지키지-못한-것--비밀번호-재설정과의-연결) |
| 🔴 2 | `HashMap`이 스레드 안전하지 않음 | [2-1](#2-1--스레드-안전하지-않다) |
| 🔴 3 | 인증번호가 만료되지 않고 재사용 가능 | [2-2](#2-2--인증번호가-영원히-만료되지-않는다) |
| 🔴 4 | 메모리 누수 (제거 정책 없음) | [2-3](#2-3--메모리-누수) |
| 🔴 5 | 발송 횟수 제한 없음 → 메일 폭탄 + SMTP 한도 소진 | [3-4](#3-4--발송-횟수-제한도-없다) |
| 🔴 6 | 서버 2대 이상에서 원리적으로 동작 불가 | [2-5](#2-5--서버를-2대로-늘리면-동작하지-않는다) |
| 🟠 7 | `Random` → `SecureRandom`으로 교체 | [3-2](#3-2--random은-암호학적으로-안전하지-않다) |
| 🟠 8 | 확인 시도 횟수 제한 없음 | [3-3](#3-3--시도-횟수-제한이-없다) |
| 🟠 9 | 회원가입이 이메일 인증을 요구하지 않음 | [6-4](#6-4-회원가입-쪽도-마찬가지다) |
| 🟠 10 | `InputStream`을 닫지 않음 → 파일 디스크립터 누수 | [5-1](#5-1--inputstream을-닫지-않는다--자원-누수) |
| 🟠 11 | `@Transactional`이 불필요 (DB 미사용) + 메일 발송 중 커넥션 점유 | [2-6](#2-6--transactional이-무의미하다) |
| 🟠 12 | `@Valid` 누락 → DTO 검증이 실행되지 않음 | [4-5](#4-5--valid가-빠져-있다) |
| 🟡 13 | 서비스가 `ResponseDTO` 반환 (계층 위반) | [4-3](#4-3--서비스가-responsedto를-반환한다) |
| 🟡 14 | 컨트롤러 `throws Exception` | [4-4](#4-4--컨트롤러의-throws-exception) |
| 🟡 15 | 템플릿을 매번 파일에서 읽음 | [5-2](#5-2--매번-파일을-읽는다) |
| 🟢 16 | `new EmailVerifiedResponse(false);` 죽은 코드 | [4-1](#4-1--new-emailverifiedresponsefalse--객체를-만들고-버림) |
| 🟢 17 | `TEST_ID` 네이밍 + `Dotenv`/`@Value` 이중 경로 | [5-4](#5-4--설정-값을-읽는-방식이-두-갈래다) |
| 🟢 18 | 인증번호에 혼동 문자(0/O, 1/l) 포함 | [3-1](#3-1-문자-코드-계산-읽기) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| DTO 검증 어노테이션 (`@NotBlank`, `@Email`) | 선언은 정확함 (다만 `@Valid`가 없어 미작동) |
| 예외 3종을 도메인별로 분리 | `EmailSendingException`, `EmailTemplateLoadException`, `InvalidEmailCodeException` |
| HTML 템플릿 외부화 | 메일 디자인을 코드에서 분리 |
| `setDefaultEncoding("utf-8")` + `MimeMessageHelper(…, "UTF-8")` | 한글 제목·본문 깨짐 방지 |
| `storedCode != null &&` 순서 | 단축 평가로 NPE 방어 |
| `MailException`을 잡아 도메인 예외로 변환 | 발송 실패를 의미 있는 예외로 감쌈 |

**이 도메인은 "동작하는 프로토타입"과 "운영 가능한 코드"의 차이를 가장 잘 보여줍니다.**
로컬에서 한 명이 테스트하면 완벽하게 동작하지만, 동시 사용자·재기동·서버 2대·악의적 사용자
중 하나만 나타나도 무너집니다.

---

## 다음 문서

- [member.md](member.md) — 이 도메인과 짝을 이루는 계정 탈취 취약점의 나머지 절반
- [../global.md](../global.md) — `EmailConfig`, Redis 설정
- [community.md](community.md) — `PostRateLimitService`가 Redis로 횟수를 세는 방식 (여기서 재사용할 패턴)
