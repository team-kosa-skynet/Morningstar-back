# interview — AI 모의면접 (세션 상태 관리 · TTS · 배치 평가)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [llm.md](llm.md) · [point.md](point.md)
>
> 파일 36개. **이 프로젝트에서 도메인 설계가 가장 정교한 곳**입니다.
> UUID 기반 세션, 순서 검증, 중복 답변 방지, 소유권 검증이 모두 제대로 들어가 있습니다.
>
> 반면 **긴 트랜잭션 안에서 LLM과 TTS를 호출**하고,
> **AI 평가가 실패하면 가짜 점수를 진짜처럼 반환**합니다.

---

## 파일 지도

```
domain/interview/
├── config/
│   ├── QuestionCatalog.java          질문 후보 카탈로그 (LLM 실패 시 폴백용)
│   └── WebClientConfig.java          WebClient.Builder 빈
├── controller/InterviewController.java   /api/interview (6개)
├── dto/
│   ├── internal/  InterviewPlanDto, PlanQuestionDto, AiTurnFeedbackDto
│   ├── request/   StartSessionRequestDto, TurnRequestDto, FinalizeReportRequestDto,
│   │              UpsertContextRequestDto, FileUploadRequestDto, TtsRequestDto
│   └── response/  StartSessionResponseDto, NextTurnResponseDto, TurnResponseDto,
│                  FinalizeReportResponseDto, ScoresDto, TtsPayloadDto, StartResponseDto
├── entity/
│   ├── InterviewSession.java     ★ UUID PK, 상태·진행 포인터·플랜 보관
│   ├── InterviewAnswer.java      ★ 유니크 제약으로 중복 답변 차단
│   └── UploadedDocument.java     이력서 파싱 결과
├── enums/  InterviewMode, InterviewStatus, JobRole
├── repository/  InterviewSessionRepository, InterviewAnswerRepository, UploadedDocumentRepository
├── service/
│   ├── InterviewService.java              613줄 ★ 핵심 오케스트레이션
│   ├── InterviewScoreService.java         점수 조회
│   ├── DocumentParsingService.java        업로드 문서 파싱 저장
│   ├── DocumentContentExtractor.java      246줄 PDF/DOCX 텍스트 추출
│   ├── TtsService.java                    TTS 인터페이스
│   ├── GoogleCloudTtsService.java         133줄
│   └── GeminiTtsService.java              140줄
└── util/PlanParser.java                   planJson 파싱
```

**흐름**

```
① POST /api/interview/documents/parse  (이력서 PDF 업로드)
      └─ DocumentContentExtractor → UploadedDocument 저장 → documentId 반환

② POST /api/interview/session  { jobRole, displayName, documentId }
      ├─ 포인트 -50
      ├─ 문서 조회 + 소유권 검증 → profileSnapshotJson 생성
      ├─ QuestionCatalog.candidates(role, skills, 10)      ← 폴백 후보
      ├─ LLM generatePlan(role, profile, candidates)       → 질문 10개 + 의도 + 가이드
      ├─ InterviewSession 저장 (status=READY, questionIndex=0)
      ├─ LLM generateGreeting(displayName)
      └─ TTS 합성 (greeting + 첫 질문)
      → { sessionId, greeting, firstQuestion, questionIntent, answerGuides, totalQuestions, tts }

③ POST /api/interview/turn  { sessionId, questionIndex, transcript }   ← 질문 수만큼 반복
      ├─ 소유권 검증 + FINISHED 검사 + 순서 검사 + 중복 답변 검사
      ├─ LLM nextTurn(plan, qidx, transcript, prevResponseId)  → 코칭 피드백
      ├─ InterviewAnswer 저장 (유니크 제약)
      ├─ session.advance() 또는 session.finishNow()
      └─ TTS 합성 (다음 질문)
      → { nextQuestion, questionIntent, answerGuides, coachingTips, nextIndex, done, tts }

④ POST /api/interview/report/finalize  { sessionId }
      ├─ 답변 전체 조회
      ├─ LLM generateBatchEvaluation → 5지표 점수
      ├─ 상위 2 / 하위 2 지표 추출 + Q/A 발췌(220자)
      └─ LLM finalizeReport(facts, prevResponseId) → 강점·개선점·다음단계
      → { overallScore, subscores, strengths, areasToImprove, nextSteps }
```

---

# 1. `InterviewSession` — UUID 기본키

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "interview_session")
@Entity
public class InterviewSession extends BaseTimeEntity {

    @Id
    @Column(columnDefinition = "BINARY(16)")     // ★ UUID를 16바이트 바이너리로
    private UUID id;                              // 애플리케이션에서 UUID 할당

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    private String displayName;
    private String role;                          // ⚠️ String (JobRole enum이 있는데)

    @Enumerated(EnumType.STRING) private InterviewMode mode;
    @Enumerated(EnumType.STRING) private InterviewStatus status;

    private int questionIndex;                    // 진행 포인터(0-base)

    @Lob private String planJson;                 // 질문 10개 + 의도 + 가이드
    @Lob private String profileSnapshotJson;      // 이력서 요약

    @Column(name = "last_response_id", length = 128)
    private String lastResponseId;                // LLM 컨텍스트 체인 anchor

    private OffsetDateTime finishedAt;

    public static InterviewSession create(UUID id, Member member, String displayName, String role,
                                          InterviewMode mode, String profileSnapshotJson, String planJson) {
        InterviewSession session = new InterviewSession();
        session.id = id;
        ...
        session.status = InterviewStatus.READY;
        session.questionIndex = 0;
        return session;
    }

    public void advance() { this.questionIndex += 1; this.status = InterviewStatus.RUNNING; }
    public void finishNow(OffsetDateTime finishedAt) { this.status = InterviewStatus.FINISHED; this.finishedAt = finishedAt; }
    public void updateLastResponseId(String id) { this.lastResponseId = id; }
    public void updateProfileSnapshotJson(String json) { this.profileSnapshotJson = json; }
}
```

## 1-1. 🟢 UUID PK를 쓴 이유

**이 프로젝트의 다른 모든 엔티티는 `IDENTITY`(AUTO_INCREMENT)를 씁니다.**
면접 세션만 UUID입니다. **의도적인 선택이고 올바릅니다.**

| | AUTO_INCREMENT (`Long`) | UUID |
|---|---|---|
| 값 생성 | DB가 INSERT 시점에 | **애플리케이션이 미리** |
| 추측 가능성 | **높음** (1, 2, 3...) | 사실상 불가 |
| 인덱스 성능 | 좋음 (순차 삽입) | 나쁨 (무작위 삽입 → 페이지 분할) |
| 크기 | 8바이트 | 16바이트 |

**면접 세션에 UUID가 맞는 이유 2가지**

1. **URL에 노출됩니다.** `GET /api/interview/{sessionId}/scores`
   순차 ID라면 `1, 2, 3...`을 돌려 **남의 면접 결과를 훔쳐볼 수 있습니다**(IDOR).
   UUID는 추측이 불가능합니다. (물론 소유권 검증도 함께 하고 있습니다 — [3-1](#3-1--소유권-검증이-모든-경로에-있다))
2. **ID를 미리 알아야 합니다.** `start()`에서 `UUID.randomUUID()`로 만든 뒤
   그 ID로 엔티티를 조립하고 응답에도 담습니다. AUTO_INCREMENT면 INSERT 후에야 알 수 있습니다.

```java
UUID sessionId = UUID.randomUUID();               // ★ 먼저 생성
InterviewSession session = InterviewSession.create(sessionId, member, ...);
interviewSessionRepository.save(session);
```

## 1-2. `@Column(columnDefinition = "BINARY(16)")`

UUID를 저장하는 방법은 세 가지입니다.

| 방법 | 크기 | 특징 |
|---|---|---|
| `VARCHAR(36)` | 36바이트 | `"550e8400-e29b-41d4-a716-446655440000"` 사람이 읽을 수 있음 |
| `CHAR(36)` | 36바이트 | 같음 |
| **`BINARY(16)`** | **16바이트** | 저장 효율 최고. `redis-cli`/SQL에서 읽기 어려움 |

`BINARY(16)`은 **인덱스 크기가 절반 이상 줄어들어** 조회 성능에 유리합니다.
16바이트 × 100만 행 = 16MB vs 36MB.

**Hibernate 6은 `UUID` 필드를 자동으로 `binary(16)`으로 매핑**하므로
`columnDefinition`이 사실 불필요하지만, 명시해서 나쁠 것은 없습니다.

> `columnDefinition`은 **DB 방언에 묶입니다.** PostgreSQL은 네이티브 `uuid` 타입이 있어
> `BINARY(16)`이 동작하지 않습니다. 이식성이 필요하면 지우는 편이 낫습니다.

## 1-3. `@Lob` — 큰 텍스트 저장

```java
@Lob private String planJson;             // 질문 10개 + 의도 + 가이드 → 수 KB
@Lob private String profileSnapshotJson;  // 이력서 전문 → 수십 KB
```

**`String` 기본 매핑은 `VARCHAR(255)`** 입니다. 그대로 두면 데이터가 잘립니다.

| 방법 | MySQL 컬럼 타입 |
|---|---|
| (기본) | `VARCHAR(255)` |
| `@Column(length = 5000)` | `VARCHAR(5000)` |
| `@Column(columnDefinition = "TEXT")` | `TEXT` (64KB) |
| **`@Lob`** | **`LONGTEXT`** (4GB) |

`@Lob` + `String`은 문자 LOB(CLOB)을 뜻하고, MySQL 방언에서 `LONGTEXT`가 됩니다.
**`columnDefinition`보다 이식성이 좋습니다** (Hibernate가 방언별로 알맞은 타입을 선택).

> `community` 도메인은 `@Column(columnDefinition = "TEXT")`를 씁니다.
> **같은 목적에 두 방식이 섞여 있습니다.** `@Lob`으로 통일하는 편이 낫습니다.

## 1-4. 🟠 `role`이 `String`이다

```java
private String role;              // BACKEND / FRONTEND / UNKNOWN
```

**`JobRole` enum이 이미 있습니다.**

```java
public enum JobRole {
    FULLSTACK("풀스택"), FRONTEND("프론트엔드"), BACKEND("백엔드");
}
```

그런데 엔티티는 `String`으로 저장하고, 서비스에서 이렇게 변환합니다.

```java
String role = req.jobRole().name();      // enum → String
```

**문제**

1. **주석의 `UNKNOWN`은 enum에 없습니다.** 주석과 실제가 다릅니다.
2. **DB에 아무 문자열이나 들어갈 수 있습니다.** 타입 안전성이 없습니다.
3. 조회 시 `JobRole.valueOf(session.getRole())`로 되돌려야 하고,
   **잘못된 값이면 `IllegalArgumentException` → 500**입니다.

```java
// 고치기
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private JobRole role;
```

`InterviewMode`와 `InterviewStatus`는 제대로 `@Enumerated(EnumType.STRING)`을 쓰는데
`role`만 `String`입니다. **일관성이 없습니다.**

## 1-5. 🟢 상태 전이 메서드

```java
public void advance()    { this.questionIndex += 1; this.status = InterviewStatus.RUNNING; }
public void finishNow(OffsetDateTime finishedAt) { this.status = FINISHED; this.finishedAt = finishedAt; }
```

`aibot`의 `markAsPosted`와 같은 패턴입니다. → [aibot.md 1-2](aibot.md#1-2-엔티티가-상태-전이를-통제한다)

**`advance()`가 "포인터 증가 + 상태 변경"을 한 덩어리로** 묶어, 호출자가 둘 중 하나를 잊을 수 없습니다.
`finishNow`도 "상태 + 종료시각"을 함께 처리합니다.

**`OffsetDateTime`을 쓴 것도 눈에 띕니다.**

| 타입 | 타임존 정보 |
|---|---|
| `LocalDateTime` | **없음** (프로젝트 대부분) |
| `OffsetDateTime` | **있음** (`2026-08-11T14:30+09:00`) |
| `Instant` | UTC 절대 시각 |

**타임존을 포함하는 것이 더 정확합니다.**
`LocalDateTime`은 서버 타임존이 바뀌면 의미가 달라집니다
([recruitmentNotice.md 1-2](recruitmentNotice.md#-zoneidsystemdefault는-환경에-따라-달라진다)).
다만 **이 프로젝트에서 유일하게 `OffsetDateTime`을 쓰는 필드**라 일관성은 없습니다.

## 1-6. `lastResponseId` — LLM 컨텍스트 체인

```java
@Column(name = "last_response_id", length = 128)
private String lastResponseId;
```

```java
// nextTurn
String prevResponseId = session.getLastResponseId();

AiTurnFeedbackDto feedback = getAiGateway().nextTurn(
        session.getPlanJson(), req.questionIndex(), req.transcript(),
        "{}", prevResponseId);                       // ★ 이전 응답 ID를 넘긴다

String llmResponseId = feedback.responseId();
if (llmResponseId != null && !llmResponseId.isBlank()) {
    session.updateLastResponseId(llmResponseId);      // ★ 새 ID로 갱신
}
```

**OpenAI의 Responses API 기능입니다.**
`previous_response_id`를 넘기면 **서버가 이전 대화를 기억**하므로,
클라이언트가 전체 히스토리를 매번 보내지 않아도 됩니다.

```
[히스토리 전송 방식]                    [응답 체인 방식]
매 턴마다 이전 대화 전체를 전송          이전 응답 ID만 전송
→ 토큰 비용이 턴마다 누적                → 토큰 비용 절감
→ 10턴이면 10번째에 9턴 분량 전송        → 서버가 컨텍스트 보관
```

**면접처럼 10턴 이상 이어지는 대화에 매우 효과적입니다.**
`InterviewAnswer`에 `llmResponseId`와 `prevResponseId`를 **둘 다 저장**해
체인을 추적할 수 있게 한 것도 세심합니다.

> ⚠️ 이 기능은 **OpenAI 고유**입니다. Gemini에는 대응 개념이 없습니다.
> `application-core.yml`이 `ai.provider: gemini`이므로
> **현재 운영에서는 `lastResponseId`가 항상 `null`일 가능성이 높습니다.**
> 즉 컨텍스트 체인 이점을 못 얻고 있습니다. 로그(`[AI][turn] ... prev={} -> new={}`)로 확인 가능합니다.

---

# 2. `InterviewAnswer` — 이 프로젝트에서 동시성을 가장 잘 처리한 곳

```java
@Table(name = "interview_answer",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_answer_session_qidx",
                                  columnNames = {"session_id", "question_index"})     // ★
        })
@Entity
public class InterviewAnswer extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private InterviewSession session;

    private int questionIndex;
    private String questionType;
    @Lob private String questionText;
    @Lob private String transcript;
    @Lob private String metricsJson;

    @Column(name = "llm_response_id", length = 128)  private String llmResponseId;
    @Column(name = "prev_response_id", length = 128) private String prevResponseId;
}
```

## 2-1. 🟢 "선제 검사 + DB 제약 + 예외 처리" 3중 방어

```java
// ① 선제 검사 — 대부분의 중복을 여기서 걸러 사용자에게 빠르게 알림
if (interviewAnswerRepository.existsBySession_IdAndQuestionIndex(req.sessionId(), req.questionIndex())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "already answered this question");
}

... LLM 호출 ...

// ② DB 제약 + ③ 예외 처리 — 동시 요청으로 ①을 통과한 경우
try {
    interviewAnswerRepository.save(answer);
    log.info("[AI][save] session={} qidx={} saved llmId={} prevId={}", ...);
} catch (DataIntegrityViolationException e) {
    log.warn("[AI][conflict] session={} qidx={} already answered", ...);
    throw new ResponseStatusException(HttpStatus.CONFLICT, "already answered this question");
}
```

**이것이 정석입니다.** 프로젝트 다른 곳과 비교해보세요.

| 도메인 | 선제 검사 | DB 유니크 제약 | 예외 처리 |
|---|---|---|---|
| **interview** | ✅ | ✅ | ✅ |
| attendance | ✅ | ✅ | ⚠️ (같은 트랜잭션에서 복구 시도 → 실패) |
| point | — | ✅ | ⚠️ (재시도가 무효) |
| community(좋아요) | ✅ | ❌ | ❌ |
| member(이메일/닉네임) | ✅ | ❌ | ❌ |
| email | ❌ | — | ❌ |

**왜 3중이 필요한가**

- **선제 검사만**: 동시 요청에 뚫립니다(TOCTOU: Time-of-check to time-of-use)
- **DB 제약만**: 뚫리지는 않지만 `500 서버 에러`가 나갑니다
- **둘 다 + 예외 변환**: 정상 경로는 빠르고, 경쟁 상황에서도 **의미 있는 409**를 반환합니다

## 2-2. 여기서는 왜 `catch` 후 `throw`가 올바른가

[attendance.md 3-3](attendance.md#결과--포인트-지급이-실패하면-출석도-롤백된다--의도와-정반대)에서
"`@Transactional` 안에서 `DataIntegrityViolationException`을 잡아도 트랜잭션은 rollback-only가 된다"고 했습니다.

**여기서는 문제가 되지 않습니다.** 잡은 뒤 **계속 진행하지 않고 다시 던지기** 때문입니다.

```java
} catch (DataIntegrityViolationException e) {
    log.warn(...);
    throw new ResponseStatusException(...);      // ★ 트랜잭션을 롤백시키고 종료
}
```

**규칙**: 트랜잭션 안에서 JPA 예외를 잡았다면
- **다시 던진다** → 정상 (롤백 + 의미 있는 예외 변환) ✅
- **삼키고 계속한다** → `UnexpectedRollbackException` ❌

## 2-3. 🟠 `existsBySession_IdAndQuestionIndex` — 언더스코어 표기

```java
boolean existsBySession_IdAndQuestionIndex(UUID sessionId, int questionIndex);
List<InterviewAnswer> findBySession_IdOrderByQuestionIndexAsc(UUID sessionId);
```

**`_`로 중첩 프로퍼티 경계를 명시했습니다.** → [member.md 2-2](member.md#2-2-언더스코어_는-왜-붙였다-말았다-하는가)

`session.id`를 뜻하며, `findBySessionId`로 써도 동작하지만
**`_`가 있으면 추측 없이 명확합니다.** 이 프로젝트에서 가장 일관되게 쓴 리포지토리입니다.

## 2-4. 🟢 `UploadedDocument`에 명시적 인덱스가 있다

```java
@Table(name = "uploaded_document",
        indexes = { @Index(name = "idx_document_member", columnList = "member_id") })
```

**이 프로젝트에서 `@Index`를 명시한 유일한 엔티티입니다.**

> ⚠️ 다만 `member_id`는 `@JoinColumn`이므로 **MySQL InnoDB가 FK 인덱스를 자동 생성**합니다.
> 즉 이 인덱스는 중복일 가능성이 있습니다.
> 정작 인덱스가 필요한 곳은 `board(member_id, created_at)`(도배 방지 쿼리),
> `points(member_id, version)`(유니크 제약으로 존재), `recruitment(expiration_date, pub_date)` 등입니다.
> → [recruitmentNotice.md 2-8](recruitmentNotice.md#2-8--조회에-트랜잭션페이징이-없다)

---

# 3. `InterviewService` — 검증과 오케스트레이션

## 3-1. 🟢 소유권 검증이 모든 경로에 있다

```java
// nextTurn
if (!session.getMember().getId().equals(memberId)) {
    throw new AccessDeniedException("forbidden: not your session");
}

// finalizeReport
if (!session.getMember().getId().equals(memberId)) {
    throw new AccessDeniedException("forbidden: not your session");
}

// start — 업로드 문서에 대해서도
if (!document.getMember().getId().equals(memberId)) {
    throw new AccessDeniedException("해당 문서에 접근할 수 없습니다.");
}
```

**IDOR 방어가 제대로 되어 있습니다.**
[member.md 7장](member.md#7--치명적-누구나-남의-비밀번호를-바꿀-수-있다)의 취약점과 정반대입니다.

특히 **업로드 문서의 소유권까지 검증**하는 것이 좋습니다.
`documentId`만 알면 남의 이력서 내용으로 면접을 시작할 수 있었을 텐데, 그것을 막았습니다.

> `conversation` 도메인은 이것을 **쿼리 조건**으로 처리합니다
> (`findActiveConversationByIdAndMemberId`). → [conversation.md 3-3](conversation.md#3-3--잘-만든-것--소유권-검증을-쿼리에-넣었다)
> **쿼리 방식이 조금 더 안전합니다** — 검증 코드를 잊을 수 없으니까요.

## 3-2. 🟢 상태 검증 4단

```java
// ① 존재 확인
InterviewSession session = interviewSessionRepository.findById(req.sessionId())
        .orElseThrow(() -> new IllegalArgumentException("session not found: " + req.sessionId()));

// ② 소유권
if (!session.getMember().getId().equals(memberId)) throw new AccessDeniedException(...);

// ③ 종료 여부
if (session.getStatus() == InterviewStatus.FINISHED) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "session already finished");
}

// ④ 순서 검증 — 클라이언트가 임의의 질문 번호를 보내는 것을 차단
if (req.questionIndex() != session.getQuestionIndex()) {
    throw new IllegalStateException(
            "out-of-order turn: expected " + session.getQuestionIndex() + ", got " + req.questionIndex());
}

// ⑤ 중복 답변
if (interviewAnswerRepository.existsBySession_IdAndQuestionIndex(...)) { ... }
```

**④의 순서 검증이 특히 좋습니다.**
서버가 진행 포인터(`session.questionIndex`)를 갖고 있고, 클라이언트가 보낸 값과 일치해야만 진행합니다.

```
클라이언트가 questionIndex=9를 바로 보내면?
  → 서버 포인터는 0 → 불일치 → 거부
  → 질문 1~8을 건너뛰고 마지막만 답하는 것을 막는다
```

**"클라이언트를 신뢰하지 않는다"는 원칙이 지켜진 코드입니다.**

## 3-3. 🔴 상태 코드를 담은 예외가 전부 500이 된다

**위 검증들이 의도한 HTTP 상태가 나가지 않습니다.**

```java
throw new AccessDeniedException("forbidden: not your session");                   // 의도: 403
throw new ResponseStatusException(HttpStatus.CONFLICT, "session already finished"); // 의도: 409
throw new IllegalStateException("out-of-order turn: ...");                          // 의도: 400
throw new IllegalArgumentException("session not found: ...");                       // 의도: 404
```

**전부 `RuntimeException`의 자손이고, `GlobalExceptionRestAdvice`에 포괄 핸들러가 있습니다.**

```java
@ExceptionHandler
public ResponseEntity<ResponseDTO<Void>> serverException(RuntimeException e) {
    log.error(e.getMessage(), e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ResponseDTO.errorWithMessage(HttpStatus.INTERNAL_SERVER_ERROR, "서버 에러!"));
}
```

**예외 처리기 우선순위**

```
DispatcherServlet의 HandlerExceptionResolver 체인
  ① ExceptionHandlerExceptionResolver   ← @RestControllerAdvice가 여기서 처리 ★
  ② ResponseStatusExceptionResolver     ← ResponseStatusException을 처리할 담당자
  ③ DefaultHandlerExceptionResolver
```

**①이 먼저 실행되어 `RuntimeException` 핸들러가 잡아버립니다.**
그래서 `ResponseStatusException`의 409도, `AccessDeniedException`의 403도
**모두 500 "서버 에러!"** 로 나갑니다.

**클라이언트는 "이미 답변한 질문"과 "서버 장애"를 구분할 수 없습니다.**

**고치는 방법 — 구체적인 핸들러를 추가합니다** (구체적인 것이 우선 적용됩니다)

```java
// GlobalExceptionRestAdvice에 추가
@ExceptionHandler(ResponseStatusException.class)
public ResponseEntity<ResponseDTO<Void>> handleResponseStatus(ResponseStatusException e) {
    log.warn("상태 예외: {} - {}", e.getStatusCode(), e.getReason());
    return ResponseEntity.status(e.getStatusCode())
            .body(ResponseDTO.errorWithMessage(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason()));
}

@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ResponseDTO<Void>> handleAccessDenied(AccessDeniedException e) {
    log.warn("접근 거부: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ResponseDTO.errorWithMessage(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"));
}

@ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
public ResponseEntity<ResponseDTO<Void>> handleIllegal(RuntimeException e) {
    log.warn("잘못된 요청: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST, e.getMessage()));
}
```

> **더 나은 방향**: 이 프로젝트의 `ApplicationException` 체계를 쓰는 것입니다.
> `ErrorCode`에 `INTERVIEW_SESSION_NOT_FOUND`, `INTERVIEW_ALREADY_ANSWERED`,
> `INTERVIEW_OUT_OF_ORDER`를 추가하고 도메인 예외를 만들면 상태 코드가 자동으로 맞습니다.
> → [global 3장](../global.md#3-exception--예외-처리-3층-구조)
>
> 이 도메인만 유일하게 **스프링 표준 예외(`ResponseStatusException`, `AccessDeniedException`)** 를
> 쓰고 있어 프로젝트 규약에서 벗어나 있습니다.

## 3-4. 🔴 `@Transactional` 안에서 LLM + TTS를 호출한다

```java
@Transactional                                                    // ★ 트랜잭션 시작
public StartSessionResponseDto start(...) throws Exception {
    ...
    pointService.createPoint(pointRequestDto, principalDetails);   // DB

    Map<String, Object> planMap = getAiGateway().generatePlan(role, profileSnapshotJson, candidates);
    //  ↑ LLM 호출 — 질문 10개 생성. 수 초 ~ 수십 초

    interviewSessionRepository.save(session);                       // DB

    String greeting = getAiGateway().generateGreeting(displayName); // (로컬 문자열이라 빠름)

    tts = ttsService.synthesize(text, defaultTtsFormat);            // ★ TTS 호출 — 또 수 초
    ...
}
```

```java
@Transactional
public NextTurnResponseDto nextTurn(...) throws Exception {
    ...
    AiTurnFeedbackDto feedback = getAiGateway().nextTurn(...);      // ★ LLM
    interviewAnswerRepository.save(answer);                          // DB
    tts = ttsService.synthesize(nextQuestion, defaultTtsFormat);     // ★ TTS
    ...
}
```

**하나의 트랜잭션이 "DB → LLM(수 초) → DB → TTS(수 초) → DB"를 감쌉니다.**

**DB 커넥션 점유 시간이 10~40초입니다.**
`aibot`([aibot.md 3장](aibot.md#3--transactional-안에서-llm을-호출한다)),
`recruitmentNotice`([recruitmentNotice.md 2-3](recruitmentNotice.md#문제--트랜잭션-안에서-외부-api를-호출한다))와
**같은 문제이고 여기가 가장 심합니다** (외부 호출이 2종류).

HikariCP 풀이 30개인데, 동시 면접 30명이면 **다른 모든 요청이 커넥션을 기다립니다.**

**로그에 그 시간이 직접 찍혀 있습니다.**

```java
log.info("[PERF] nextTurn 메서드 완료 - 전체 실행 시간: {} ms (AI: {} ms, 최적화됨)", methodMs, elapsedMs);
```

**성능을 측정하고 있는데, 그 시간이 곧 트랜잭션 길이라는 점은 놓쳤습니다.**

**고치는 방법 — 트랜잭션을 쪼갠다**

```java
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewTxService txService;      // 트랜잭션 담당 별도 빈

    /** 트랜잭션 없음 — 외부 호출이 커넥션을 붙잡지 않도록 */
    public NextTurnResponseDto nextTurn(TurnRequestDto req, PrincipalDetails principal, boolean withAudio)
            throws Exception {

        // ── 트랜잭션 1: 검증 + 필요한 데이터만 꺼내기 (짧음) ──
        TurnContext ctx = txService.validateAndLoad(req, principal.getMember().getId());

        // ── 트랜잭션 없음: 외부 I/O (김) ──
        AiTurnFeedbackDto feedback = getAiGateway().nextTurn(
                ctx.planJson(), req.questionIndex(), req.transcript(), "{}", ctx.prevResponseId());

        // ── 트랜잭션 2: 결과 저장 + 상태 전이 (짧음) ──
        TurnResult result = txService.saveAnswerAndAdvance(req, ctx, feedback);

        // ── 트랜잭션 없음: TTS ──
        TtsPayloadDto tts = result.done() || !withAudio ? null
                : safeSynthesize(result.nextQuestion());

        return new NextTurnResponseDto(result.nextQuestion(), result.intent(), result.guides(),
                                       feedback.coachingTips(), result.nextIndex(), result.done(), tts);
    }
}
```

**커넥션 점유가 40초 → 수십 ms로 줄어듭니다.**

> ⚠️ **트래드오프**: 트랜잭션을 쪼개면 "포인트 차감 + 세션 생성"의 원자성이 약해집니다.
> `start()`는 지금 하나의 트랜잭션이라 **LLM이 실패하면 포인트도 롤백**됩니다(장점).
> 쪼갤 때는 **포인트 차감을 성공 후로 옮기거나** 보상 트랜잭션이 필요합니다.
> → [question.md 3-2](question.md#3-2--llm-실패-시-포인트를-환불하지-않는다)에서 다룬 문제와 같습니다.

## 3-5. 🔴 AI 평가 실패 시 가짜 점수를 반환한다

**이 도메인에서 가장 심각한 문제입니다.**

```java
Map<String, Integer> subscores;
try {
    subscores = generateBatchEvaluation(answers, session);       // LLM 종합 평가
} catch (Exception e) {
    log.warn("[BATCH] 배치 평가 실패, 폴백 점수 사용: {}", e.getMessage());
    // 폴백: 기본 점수 (면접 완료 기본선)
    subscores = Map.of(
        "clarity", 45,
        "structure_STAR", 40,
        "tech_depth", 50,
        "tradeoff", 42,
        "root_cause", 38
    );
}
```

```java
Map<String, Object> sum;
try {
    sum = getAiGateway().finalizeReport(sessionJson, previousResponseId);
} catch (Exception ex) {
    sum = Map.of(
            "strengths", "논리 전개가 명확하고 핵심을 빠르게 제시합니다.",
            "areasToImprove", "구체적 수치/사례 제시가 더 필요합니다.",
            "nextSteps", "최근 장애 사례를 STAR 구조로 1분 요약하는 연습을 권장합니다."
    );
}
```

**LLM이 실패하면 미리 정해둔 숫자와 칭찬 문구가 반환됩니다.**
그리고 **응답에는 "이것은 폴백입니다"라는 표시가 없습니다.**

```json
{
  "code": 200,
  "message": "면접 보고서가 성공적으로 생성되었습니다.",
  "data": {
    "overallScore": 43.0,
    "subscores": { "clarity": 45, "structure_STAR": 40, "tech_depth": 50, ... },
    "strengths": "논리 전개가 명확하고 핵심을 빠르게 제시합니다.",
    ...
  }
}
```

**사용자는 이것이 자신의 면접을 평가한 결과라고 믿습니다.**
포인트 50점을 낸 대가로 **자기 답변과 무관한 점수**를 받습니다.

**더 나쁜 것은 폴백 점수가 "그럴듯하다"는 점입니다.**
`45, 40, 50, 42, 38`은 우연히 나올 수 없는 특정 값이지만
사용자가 알아챌 수 없고, `overallScore: 43.0`도 자연스러워 보입니다.

`aibot`의 "검열하지 않고 `MODERATED`로 기록"([aibot.md 2-2](aibot.md#2-2--검열을-하지-않는데-검열-완료로-기록한다))과
**같은 종류의 문제** — **상태·데이터가 사실과 다릅니다.**

**고치는 방법**

```java
// ① 실패를 응답에 드러낸다
public record FinalizeReportResponseDto(
        Double overallScore,
        Map<String, Integer> subscores,
        String strengths,
        String areasToImprove,
        String nextSteps,
        boolean evaluated,           // ★ 실제로 AI가 평가했는가
        String unavailableReason     // ★ 평가하지 못한 이유
) {}
```

```java
// ② 또는 실패 시 재시도 대기 상태로 응답한다
catch (Exception e) {
    log.error("[BATCH] 배치 평가 실패 - session: {}", sessionId, e);
    throw new InterviewEvaluationUnavailableException();   // 503 + "잠시 후 다시 조회해주세요"
    // → 답변은 이미 저장되어 있으므로 나중에 다시 요청하면 평가 가능
}
```

**②가 더 정직합니다.** 면접 답변(`InterviewAnswer`)은 이미 DB에 있으니
**보고서 생성을 나중에 다시 시도할 수 있습니다.** 데이터를 잃지 않습니다.

> **원칙: 폴백은 "기능 저하"여야 하고 "거짓"이어서는 안 됩니다.**
>
> - TTS 실패 → `tts = null` (음성 없이 텍스트만) ✅ **올바른 폴백**
> - 검열 실패 → 차단 ([community.md 6-5](community.md#6-5--폴백-전략--primary--fallback--보수적-차단)) ✅ **올바른 폴백**
> - 평가 실패 → 가짜 점수 ❌ **거짓 데이터**

## 3-6. 🟢 TTS 실패 처리는 올바르다

```java
if (withAudio && firstQuestion != null && !firstQuestion.isBlank()) {
    try {
        String text = greeting + " " + firstQuestion;
        tts = ttsService.synthesize(text, defaultTtsFormat);
    } catch (Exception e) {
        log.warn("[TTS] start synthesize failed: {}", e.getMessage());
        // tts는 null로 남는다 → 응답에서 음성만 빠지고 면접은 진행됨
    }
}
```

**Graceful Degradation의 정확한 예입니다.**

- 음성은 **부가 기능**입니다. 없어도 텍스트로 면접을 볼 수 있습니다
- 그래서 실패를 삼키고 `null`로 응답합니다
- 프론트엔드는 `tts == null`이면 음성 재생을 건너뛰면 됩니다

**폴백 정책이 데이터 성격에 따라 달라야 한다**는 것을 이 코드가 스스로 보여줍니다.
평가 점수([3-5](#3-5--ai-평가-실패-시-가짜-점수를-반환한다))는 부가 기능이 아니라 **핵심 산출물**이므로
같은 방식(조용한 대체)을 쓰면 안 됩니다.

## 3-7. 🟢 `getAiGateway()` — 설정으로 제공자를 고르는 유일한 곳

```java
@Value("${ai.provider:openai}")
private String aiProvider;

private InterviewerAiGateway getAiGateway() {
    return "gemini".equalsIgnoreCase(aiProvider) ? geminiGateway : openAiGateway;
}
```

`application-core.yml`의 설정을 **실제로 읽어 반영하는 유일한 코드**입니다.

```yaml
ai:
  provider: gemini  # openai 또는 gemini 선택 가능
```

`aibot`은 `@Qualifier`로 Gemini를 고정하고 이 설정을 무시합니다.
→ [aibot.md 2-4](aibot.md#2-4--나머지-todo들)

**여기가 올바른 구현이니, 이 방식을 공용 설정으로 올리면 됩니다.**

```java
// global/config/LlmGatewayConfig.java
@Configuration
public class LlmGatewayConfig {

    @Bean
    @Primary
    public InterviewerAiGateway defaultAiGateway(
            @Value("${ai.provider:gemini}") String provider,
            @Qualifier("geminiInterviewerGateway") InterviewerAiGateway gemini,
            @Qualifier("openAiInterviewerGateway") InterviewerAiGateway openAi) {
        return "openai".equalsIgnoreCase(provider) ? openAi : gemini;
    }
}
```

> ⚠️ 코드 기본값은 `openai`인데 설정 파일은 `gemini`입니다.
> 설정이 이기므로 Gemini가 쓰입니다. **기본값을 맞춰두는 편이 혼란이 적습니다.**

## 3-8. 🟠 `QuestionCatalog`가 사실상 쓰이지 않는다

```java
List<String> skills = om.convertValue(
        snap.getOrDefault("skills", List.of()),        // ★ "skills" 키가 없다
        new TypeReference<List<String>>() {});

List<Map<String,Object>> candidates = questionCatalog.candidates(role, skills, 10);

Map<String, Object> planMap = getAiGateway().generatePlan(role, profileSnapshotJson, candidates);
```

**두 단계에서 무력화됩니다.**

### ① `skills`가 항상 빈 리스트다

`profileSnapshot`을 만드는 코드를 보면:

```java
Map<String, Object> profileSnapshot = new HashMap<>();
profileSnapshot.put("role", req.jobRole().name());
profileSnapshot.put("documentContent", document.getExtractedContent());
profileSnapshot.put("fileName", document.getFileName());
```

**`"skills"` 키를 넣는 코드가 없습니다.** 따라서 `getOrDefault("skills", List.of())`는
**항상 빈 리스트**를 반환하고, `questionCatalog.candidates(role, [], 10)`이 호출됩니다.

`llm` 포트에는 `extractDocumentInfo`가 있어 **문서에서 기술스택을 추출**할 수 있습니다.

```java
/**
 * 문서에서 구조화된 정보 추출
 * @return 구조화된 정보 (기술스택, 프로젝트, 경력 등)
 */
Map<String, Object> extractDocumentInfo(String rawText) throws Exception;
```

**이 메서드를 호출하는 코드가 없습니다.** 만들어놓고 쓰지 않습니다.

```java
// 이렇게 연결하면 됩니다
Map<String, Object> extracted = getAiGateway().extractDocumentInfo(document.getExtractedContent());
profileSnapshot.put("skills", extracted.getOrDefault("skills", List.of()));
profileSnapshot.put("projects", extracted.getOrDefault("projects", List.of()));
```

### ② `generatePlan`이 `candidates`를 폴백으로만 쓴다

```java
// GeminiAiAdapter
@Override
public Map<String, Object> generatePlan(String role, String profileSnapshotJson,
                                        List<Map<String, Object>> candidates) {
    try {
        return generateQuestionsWithGemini(role, profileSnapshotJson);   // ★ candidates를 안 쓴다
    } catch (Exception e) {
        ...
        if (candidates != null && !candidates.isEmpty()) {
            return createNormalizedQuestions(candidates);                 // 실패 시에만 사용
        }
        return getFallbackQuestions();
    }
}
```

**LLM이 성공하면 `candidates`는 버려집니다.**
즉 `QuestionCatalog`는 **LLM 장애 시의 예비 질문 은행**입니다. 그 자체로 나쁘지 않지만,
**이름과 위치(`config` 패키지)가 그 역할을 드러내지 않습니다.**

## 3-9. 🟡 폴백 가이드 문구가 두 곳에 다르게 복사되어 있다

```java
// start()
answerGuides = List.of(
    "구체적인 상황과 배경을 명확히 설명하고, 당시 직면한 과제를 구체적으로 제시하세요.",
    "문제 해결을 위해 취한 행동과 접근 방법을 단계별로 설명하고, 기술적 근거를 포함하세요.",
    "최종 결과와 비즈니스 임팩트를 수치나 구체적 사례로 보여주고, 얻은 인사이트를 언급하세요."
);

// nextTurn()
answerGuides = List.of(
    "구체적인 경험을 바탕으로 답변해주세요.",
    "STAR 방식(상황, 과제, 행동, 결과)을 활용하면 좋습니다.",
    "기술적 근거와 함께 설명해주세요."
);
```

**같은 목적의 폴백인데 내용이 다릅니다.** 그리고 `questionIntent` 폴백 문구
(`"이 질문을 통해 지원자의 역량을 평가합니다."`)는 두 곳에 **똑같이 복사**되어 있습니다.

```java
// 상수로 한 곳에
private static final String DEFAULT_INTENT = "이 질문을 통해 지원자의 역량을 평가합니다.";
private static final List<String> DEFAULT_GUIDES = List.of(
        "구체적인 상황과 배경을 명확히 설명하세요.",
        "STAR 방식(상황·과제·행동·결과)으로 구조화하세요.",
        "결과를 수치나 구체적 사례로 제시하세요.");

private PlanQuestionDto withFallback(PlanQuestionDto q) {
    String intent = (q.intent() == null || q.intent().isBlank()) ? DEFAULT_INTENT : q.intent();
    List<String> guides = (q.guides() == null || q.guides().isEmpty()) ? DEFAULT_GUIDES : q.guides();
    return new PlanQuestionDto(q.idx(), q.type(), q.text(), intent, guides);
}
```

**`start`와 `nextTurn`이 같은 메서드를 쓰면 문구가 갈릴 수 없습니다.**

## 3-10. 🟡 성능 로그가 과하다

```java
long methodStart = System.nanoTime();
log.info("[PERF] nextTurn 메서드 시작 - session: {}, question: {}, withAudio: {}", ...);
...
log.info("[AI][turn] session={} qidx={} prev={} -> new={} ({} ms)", ...);
log.info("[AI][save] session={} qidx={} saved llmId={} prevId={}", ...);
log.info("[TTS] starting synthesize for question: '{}'", nextQuestion);
log.info("[TTS] completed synthesize in {} ms", ttsMs);
log.info("[OPTIMIZED] 질문 데이터 준비 완료: {} ms (pre-generated 방식)", optimizedMs);
log.info("[PERF] nextTurn 메서드 완료 - 전체 실행 시간: {} ms (AI: {} ms, 최적화됨)", ...);
```

**한 번의 턴에 INFO 로그가 7줄** 나갑니다. 그리고 로그에 **질문 전문**이 들어갑니다.

**문제**

1. 운영 로그 레벨이 `INFO`이므로 전부 기록되고 Loki로 전송됩니다
2. `[OPTIMIZED] ... (pre-generated 방식)`, `(최적화됨)` 같은 **개발 중 자기 메모**가 남아 있습니다
3. **면접 질문 내용이 로그에 남습니다** — 개인화된 질문이면 프라이버시 문제입니다

```java
log.debug("[PERF] nextTurn 완료 - session: {}, {} ms (AI: {} ms)", req.sessionId(), methodMs, elapsedMs);
```

**성능 추적이 목적이라면 로그보다 메트릭이 맞습니다.**
이 프로젝트는 이미 Micrometer + Prometheus가 붙어 있습니다.

```java
private final MeterRegistry meterRegistry;

Timer.Sample sample = Timer.start(meterRegistry);
AiTurnFeedbackDto feedback = getAiGateway().nextTurn(...);
sample.stop(meterRegistry.timer("interview.llm.nextTurn", "provider", getAiGateway().getProviderName()));
```

**메트릭이면 p50/p95/p99 백분위와 시계열 그래프를 얻습니다.** 로그로는 못 하는 일입니다.

## 3-11. 🟡 `finalizeReport`가 `readOnly`인데 LLM을 2번 호출한다

```java
@Transactional(readOnly = true)                            // 🟢 쓰기가 없으니 올바름
public FinalizeReportResponseDto finalizeReport(UUID sessionId, PrincipalDetails principalDetails) {
    ...
    subscores = generateBatchEvaluation(answers, session);      // LLM #1
    ...
    sum = getAiGateway().finalizeReport(sessionJson, previousResponseId);   // LLM #2
    ...
}
```

**`readOnly = true`는 올바릅니다** — 이 메서드는 아무것도 쓰지 않습니다.
(쓰기가 있었다면 [community.md 4-6](community.md#4-6--readonly--true-안에서-조회수를-올린다)처럼 유실됐을 것입니다.)

**하지만 LLM 2회 호출로 트랜잭션이 20~60초 유지됩니다.**
읽기 전용이어도 **커넥션은 점유**합니다.

**그리고 보고서를 저장하지 않습니다.**
같은 세션의 보고서를 다시 요청하면 **LLM을 또 호출**합니다.

```java
// 개선 — 결과를 저장해 재요청 시 캐시로 응답
@Entity
public class InterviewReport {
    @Id private UUID sessionId;
    private Double overallScore;
    @Lob private String subscoresJson;
    @Lob private String strengths;
    ...
}
```

**보고서는 한 번 만들면 바뀌지 않습니다.** 저장하면 비용과 응답 시간이 모두 개선됩니다.

## 3-12. 🟢 `finalizeReport`의 facts 구성

```java
// 상위 2개 / 하위 2개 지표 추출
Comparator<Map.Entry<String, Integer>> byVal = Map.Entry.comparingByValue();
List<String> topStrengthKeys = subscores.entrySet().stream()
        .sorted(byVal.reversed()).limit(2).map(Map.Entry::getKey).toList();
List<String> areasToImproveKeys = subscores.entrySet().stream()
        .sorted(byVal).limit(2).map(Map.Entry::getKey).toList();
```

```java
// transcript는 220자까지만 발췌
String excerpt = tr.replaceAll("\\s+", " ").trim();
if (excerpt.length() > 220) excerpt = excerpt.substring(0, 220) + "…";
```

**LLM 프롬프트 설계가 잘 되어 있습니다.**

1. **점수를 먼저 계산하고, 그 결과를 요약 생성 프롬프트에 넣습니다.**
   주석에 이유가 적혀 있습니다: `요약이 점수와 일치하도록 가이드`
   → **LLM이 점수와 모순되는 칭찬을 하는 것을 막습니다.**
2. **답변 전문이 아니라 220자 발췌를 넣습니다.**
   토큰 비용을 통제하면서 맥락은 유지합니다.
3. `replaceAll("\\s+", " ")`로 개행·중복 공백을 정리해 **토큰을 절약**합니다.

`Map.Entry.comparingByValue()`는 표준 라이브러리 비교자이고,
`.reversed()`로 내림차순을 만드는 것도 관용구입니다.

> [question.md 5-6](question.md#5-6--프롬프트를-사용자-콘텐츠에-섞는다)에서
> `question` 도메인이 파일 전문을 길이 제한 없이 프롬프트에 넣는다고 지적했습니다.
> **여기서는 제대로 하고 있습니다.** 같은 프로젝트에서 수준 차이가 큽니다.

## 3-13. 🟠 미구현 기능 설정이 남아 있다

```yaml
# application-core.yml
interview:
  defaults:
    with-audio: true          # ✅ 사용됨 (InterviewController)
  default-mode: TURN_TEXT     # ❌ 읽는 코드 없음
  features:
    realtime-enabled: false   # ❌ 읽는 코드 없음
    server-stt-enabled: false # ❌ 읽는 코드 없음
```

```java
public enum InterviewMode {
    TURN_TEXT,              // ✅ 사용됨
    TURN_VOICE_SERVER,      // ❌ 미구현 (서버 STT)
    REALTIME_WEBRTC         // ❌ 미구현 (실시간 음성)
}
```

```java
// start() — mode를 하드코딩
InterviewSession session = InterviewSession.create(
        sessionId, member, displayName, role,
        InterviewMode.TURN_TEXT,          // ★ 요청과 무관하게 고정
        profileSnapshotJson, planJson);
```

`SpringSecurityConfig`에도 미구현 경로가 열려 있습니다.

```java
.requestMatchers(new AntPathRequestMatcher("/api/realtime/**")).permitAll()   // 컨트롤러 없음
.requestMatchers(new AntPathRequestMatcher("/interviews/**")).permitAll()      // 컨트롤러 없음
```

**계획 단계의 흔적이 설정과 코드에 남아 있습니다.**
미구현 enum 상수와 설정은 **"이 기능이 있다"는 오해**를 만듭니다.
`README.md`의 "모의면접" 스크린샷을 보면 텍스트 기반이므로, 나머지는 정리 대상입니다.

## 3-14. 🟡 세션 목록 조회 API가 없다

```java
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    List<InterviewSession> findByMemberIdOrderByCreatedAtDesc(Long memberId);   // ⚠️ 미사용
}
```

**면접 세션이 DB에 쌓이는데 "내 면접 이력"을 볼 방법이 없습니다.**

`InterviewController`의 엔드포인트 6개는 모두 **진행 중인 면접**을 위한 것입니다.
`/{sessionId}/scores`로 점수를 볼 수 있지만 **sessionId를 기억해야** 합니다.

리포지토리 메서드는 이미 있으니 컨트롤러·서비스만 추가하면 됩니다.

```java
@GetMapping("/sessions")
public ResponseEntity<ResponseDTO<List<InterviewSessionSummaryDto>>> mySessions(
        @AuthenticationPrincipal PrincipalDetails principalDetails,
        Pageable pageable) { ... }
```

---

# 4. `WebClientConfig` — 응답 타임아웃이 없다

```java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))       // ★ 연결 타임아웃만
                .build();

        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(jdk));
    }
}
```

**연결 타임아웃 4초는 설정했지만 응답 타임아웃이 없습니다.**

```
연결은 4초 안에 되었지만 서버가 응답을 주지 않으면?
  → 영원히 대기
```

TTS는 음성 합성이라 수 초가 걸리고, 실패 시 오래 매달릴 수 있습니다.

```java
@Bean
public WebClient.Builder webClientBuilder() {
    HttpClient jdk = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    return WebClient.builder()
            .clientConnector(new JdkClientHttpConnector(jdk))
            .filter((request, next) -> next.exchange(request)
                    .timeout(Duration.ofSeconds(30)));       // ★ 응답 타임아웃
}
```

또는 호출 지점에서 지정합니다.

```java
webClient.post().uri(...).retrieve()
        .bodyToMono(String.class)
        .timeout(Duration.ofSeconds(30))                     // Reactor 연산자
        .block();
```

> **이 프로젝트에는 HTTP 클라이언트가 4종류 있습니다.**
>
> | 클라이언트 | 위치 | 타임아웃 |
> |---|---|---|
> | `RestTemplate` (빈) | `HttpClientConfig` | ❌ 없음 |
> | `RestClient` (빈) | `HttpClientConfig` | ✅ 연결 30s / 응답 600s |
> | `new RestTemplate()` | `Gemini/OpenAiAiAdapter` | ❌ 없음 ([llm.md 3장](llm.md#3--타임아웃-없는-resttemplate)) |
> | `WebClient.Builder` | `interview/config` | ⚠️ 연결만 4s |
> | `HttpURLConnection` | `newsData/util` | ✅ 연결 5s / 읽기 10s |
>
> **5종류입니다.** 타임아웃 정책이 제각각이고, 두 곳은 무한입니다.
> **하나로 통일하고 용도별 타임아웃을 명시하는 것이 정석입니다.**

---

# 5. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | AI 평가 실패 시 가짜 점수·칭찬을 진짜처럼 반환 | [3-5](#3-5--ai-평가-실패-시-가짜-점수를-반환한다) |
| 🔴 2 | `@Transactional` 안에서 LLM + TTS 호출 → 커넥션 10~40초 점유 | [3-4](#3-4--transactional-안에서-llm--tts를-호출한다) |
| 🔴 3 | `409`·`403`·`400` 의도가 전부 `500`으로 나감 | [3-3](#3-3--상태-코드를-담은-예외가-전부-500이-된다) |
| 🔴 4 | `/api/interview/**` 무인증 → `principalDetails` NPE 500 | [global 6-1](../global.md#③-url별-권한--️-이-프로젝트-최대의-문제) |
| 🟠 5 | `WebClient` 응답 타임아웃 없음 | [4장](#4-webclientconfig--응답-타임아웃이-없다) |
| 🟠 6 | `extractDocumentInfo` 미사용 → `skills`가 항상 비어 `QuestionCatalog` 무력화 | [3-8](#3-8--questioncatalog가-사실상-쓰이지-않는다) |
| 🟠 7 | `role`이 `String` (`JobRole` enum이 있는데) | [1-4](#1-4--role이-string이다) |
| 🟠 8 | 보고서를 저장하지 않아 재조회마다 LLM 2회 호출 | [3-11](#3-11--finalizereport가-readonly인데-llm을-2번-호출한다) |
| 🟠 9 | 성능 로그 7줄/턴 + 질문 전문 로깅 | [3-10](#3-10--성능-로그가-과하다) |
| 🟡 10 | 폴백 가이드 문구가 두 곳에 다르게 복사 | [3-9](#3-9--폴백-가이드-문구가-두-곳에-다르게-복사되어-있다) |
| 🟡 11 | 미구현 enum 상수 2개 + 미사용 설정 3개 + 죽은 시큐리티 경로 2개 | [3-13](#3-13--미구현-기능-설정이-남아-있다) |
| 🟡 12 | 세션 목록 API 없음 (리포지토리 메서드는 존재) | [3-14](#3-14--세션-목록-조회-api가-없다) |
| 🟡 13 | `ai.provider` 코드 기본값(openai) ≠ 설정(gemini) | [3-7](#3-7--getaigateway--설정으로-제공자를-고르는-유일한-곳) |
| 🟡 14 | 스프링 표준 예외 사용 → 프로젝트 `ApplicationException` 규약 이탈 | [3-3](#3-3--상태-코드를-담은-예외가-전부-500이-된다) |
| 🟢 15 | `@Lob` vs `columnDefinition = "TEXT"` 혼용 | [1-3](#1-3-lob--큰-텍스트-저장) |
| 🟢 16 | `OffsetDateTime`이 이 필드에만 (일관성) | [1-5](#1-5--상태-전이-메서드) |
| 🟢 17 | `columnDefinition = "BINARY(16)"` — Hibernate 6 기본값과 중복 | [1-2](#1-2-columncolumndefinition--binary16) |
| 🟢 18 | `UploadedDocument`의 `@Index`가 FK 자동 인덱스와 중복 | [2-4](#2-4--uploadeddocument에-명시적-인덱스가-있다) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **`InterviewAnswer` 3중 동시성 방어** | 선제 검사 + 유니크 제약 + 예외 변환. 프로젝트 유일하게 완전한 구현 |
| **UUID 기본키 (`BINARY(16)`)** | URL 노출 대비 + 애플리케이션 선할당 + 저장 효율 |
| **소유권 검증 전 경로 적용** | 세션·문서 모두. IDOR 방어 |
| **순서 검증 (`questionIndex` 대조)** | 서버가 진행 포인터를 소유. 클라이언트를 신뢰하지 않음 |
| 상태 전이 메서드 (`advance`, `finishNow`) | 관련 변경을 원자적으로 묶음 |
| `lastResponseId` 컨텍스트 체인 | OpenAI Responses API 활용. 토큰 비용 절감 |
| `llmResponseId` + `prevResponseId` 둘 다 저장 | 체인 추적 가능 |
| **TTS 실패를 `null`로 폴백** | 부가 기능은 없어도 진행. Graceful Degradation의 정확한 예 |
| **점수를 먼저 계산해 요약 프롬프트에 주입** | LLM 요약이 점수와 모순되지 않게. 주석에 의도 명시 |
| **transcript 220자 발췌** | 토큰 비용 통제 + 맥락 유지 |
| `replaceAll("\\s+", " ")` 정규화 | 개행·중복 공백 제거로 토큰 절약 |
| 상위/하위 2지표 추출 | `Map.Entry.comparingByValue().reversed()` 관용구 활용 |
| `getAiGateway()` 설정 기반 선택 | `ai.provider`를 실제로 반영하는 유일한 코드 |
| `finalizeReport`의 `readOnly = true` | 쓰기가 없음을 정확히 반영 |
| `_` 표기 일관성 (`existsBySession_Id...`) | 중첩 프로퍼티 경계 명시. 프로젝트 최고 |
| `@Lob` 사용 | `columnDefinition`보다 이식성 좋은 대용량 텍스트 매핑 |
| `@Index` 명시 | 프로젝트 유일 |

**핵심 평가**: **도메인 모델링과 검증 설계가 이 프로젝트 최고 수준입니다.**
동시성·권한·순서를 모두 서버가 통제하고, 엔티티가 자기 상태 전이를 소유합니다.
프롬프트 설계(점수 선계산, 발췌)도 실무 감각이 보입니다.

문제는 두 가지에 집중됩니다.

1. **폴백 철학의 실패** — TTS는 정직하게 `null`을 주는데, **평가 점수는 거짓을 줍니다.**
   포인트 50점을 낸 사용자가 자기 답변과 무관한 점수를 받습니다.
2. **트랜잭션 경계** — 외부 호출 2종이 트랜잭션 안에 있어 커넥션을 최대 40초 붙잡습니다.

가장 먼저 할 일은 **[3-5](#3-5--ai-평가-실패-시-가짜-점수를-반환한다)의 폴백을 없애는 것**입니다.
답변은 이미 DB에 있으니 **"평가 실패 → 나중에 재요청"으로 바꾸면 데이터 손실 없이 정직해집니다.**

---

## 다음 문서

- [llm.md](llm.md) — 이 도메인이 쓰는 포트와 어댑터
- [question.md](question.md) — 같은 파일 처리를 다르게 구현한 도메인
- [point.md](point.md) — 면접 시작 시 -50 포인트
- [aibot.md](aibot.md) — 같은 "상태가 거짓말하는" 문제
