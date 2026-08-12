# Morningstar-back 학습 문서

> 이 프로젝트(**개발자의 방주**, gaebang.site) 백엔드의 **모든 폴더를 코드 단위로 읽고 정리한 문서**입니다.
>
> 목적은 두 가지입니다.
> 1. **Java·Spring·JPA를 이 코드로 다시 배우기** — 문법·어노테이션·동작 원리를 실제 코드에 붙여 설명
> 2. **무엇이 잘못됐는지 알기** — 각 문서 끝에 우선순위가 붙은 수정 목록
>
> 문서 17개, 약 13,600줄. 코드 18,216줄에 대응합니다.

---

# 1. 읽는 순서

## 처음 읽는다면

```
① 00-기초개념.md        ← 문법·어노테이션·JPA·트랜잭션. 반드시 먼저.
② global.md             ← 인증·예외·응답 규약. 모든 도메인이 여기에 의존
③ domain/member.md      ← 가장 기본적인 CRUD + JPA 저장 방식 3가지
④ domain/point.md       ← 트랜잭션·동시성의 교과서 사례
⑤ domain/community.md   ← JPA 연관관계·캐시·이벤트가 모두 등장
```

**여기까지 읽으면 나머지는 어느 순서로든 읽힙니다.**

## 주제별로 골라 읽는다면

| 배우고 싶은 것 | 문서 |
|---|---|
| **프록시가 왜 중요한가** (`@Transactional`이 무시되는 이유) | [기초개념 4-4](00-기초개념.md#4-4-프록시--spring-마법의-정체) → [point](domain/point.md) → [attendance](domain/attendance.md) |
| **JPA 더티 체킹 / `save()`의 진짜 동작** | [기초개념 6-4](00-기초개념.md#6-4-더티-체킹--save-없이-update가-되는-이유) → [member 3-2~3-4](domain/member.md) → [aibot 4장](domain/aibot.md) |
| **N+1 문제와 해결** | [community 3장](domain/community.md) (프로젝션) → [conversation 2-1](domain/conversation.md) → [pointTier 5장](domain/pointTier.md) |
| **동시성 제어** | [point 1-4](domain/point.md) (낙관적) → [payment 3-2](domain/payment.md) (비관적) → [interview 2장](domain/interview.md) (3중 방어) |
| **트랜잭션 경계 설계** | [attendance](domain/attendance.md) → [aibot 3장](domain/aibot.md) → [payment 3-3](domain/payment.md) |
| **이벤트 기반 비동기** | [community 1장](domain/community.md) → [newsData 3-3](domain/newsData.md) → [aibot](domain/aibot.md) |
| **캐시 (Redis)** | [global 7장](global.md) → [community 4장](domain/community.md) |
| **외부 API 연동·장애 대응** | [community 6장](domain/community.md) (서킷브레이커) → [llm](domain/llm.md) → [newsData 4-2](domain/newsData.md) |
| **아키텍처 (포트-어댑터)** | [llm 1~2장](domain/llm.md) |
| **보안** | [member 7장](domain/member.md) → [email 6장](domain/email.md) → [global 6-1](global.md) |
| **테스트를 시작할 지점** | [ai 1-7](domain/ai.md) (순수 계산 클래스) |

---

# 2. 문서 목록

## 공통

| 문서 | 분량 | 내용 |
|---|---|---|
| [00-기초개념.md](00-기초개념.md) | 847줄 | Java 문법 · 어노테이션 원리 · Lombok · Spring DI/프록시 · MVC · **JPA** · **트랜잭션** · 이 프로젝트의 관용구 |
| [global.md](global.md) | 1,406줄 | `config`(빈·스레드풀·HTTP·메일) · `BaseTimeEntity` · **예외 3층 구조** · `ResponseDTO` · **JWT** · **Spring Security/OAuth2** · **Redis 캐시** · S3 |

## 도메인 (15개)

기능 규모 순입니다.

| 문서 | 파일 | 분량 | 이 도메인의 핵심 학습 주제 |
|---|---|---|---|
| [community.md](domain/community.md) | 60 | 1,224줄 | JPA 연관관계 · `@Builder.Default` · 생성자 프로젝션 · Redis 캐시 버전 무효화 · 이벤트 기반 검열 · 서킷브레이커 |
| [question.md](domain/question.md) | 44 | 915줄 | **SSE 스트리밍** · Tika/PDFBox 파일 처리 · 모델 카탈로그 · enum 설계 |
| [interview.md](domain/interview.md) | 36 | 895줄 | **UUID 기본키** · `@Lob` · **3중 동시성 방어** · 상태 검증 · LLM 컨텍스트 체인 |
| [member.md](domain/member.md) | 27 | 949줄 | `@Embedded` · **persist/더티체킹/merge 3가지 저장 방식** · 🔴 **계정 탈취 취약점** |
| [ai.md](domain/ai.md) | 25 | 860줄 | **Min-Max 정규화 알고리즘** · 자연키 · RSS 크롤링 → LLM → DALL·E 파이프라인 |
| [conversation.md](domain/conversation.md) | 20 | 652줄 | 1:N 양방향 · `cascade`/`orphanRemoval` · 생성자 `@Builder` · IDOR 방어 |
| [payment.md](domain/payment.md) | 17 | 870줄 | **멱등성** · **비관적 락** · 금액 검증 · 상태 기계 |
| [newsData.md](domain/newsData.md) | 14 | 691줄 | `@Modifying` 벌크 UPDATE · 배치 페이징 · 타임존 처리 · 서킷브레이커 |
| [aibot.md](domain/aibot.md) | 10 | 615줄 | **상태 기계 설계** · `@ConditionalOnProperty` · 복구 스케줄러 |
| [point.md](domain/point.md) | 10 | 793줄 | **원장(ledger) 설계** · 낙관적 동시성 · 🔴 **프록시 함정의 교과서** |
| [email.md](domain/email.md) | 8 | 610줄 | **싱글턴 빈의 가변 상태** · `SecureRandom` · try-with-resources |
| [pointTier.md](domain/pointTier.md) | 7 | 403줄 | 스칼라 프로젝션 · 메모리 캐싱 · N번 쿼리 문제 |
| [attendance.md](domain/attendance.md) | 6 | 443줄 | 🔴 **`REQUIRES_NEW` self-invocation 함정** · `LocalDate` vs `LocalDateTime` |
| [recruitmentNotice.md](domain/recruitmentNotice.md) | 5 | 649줄 | 수집 파이프라인 기본형 · `IN` 절 배치 조회 · 공용 유틸 |
| [llm.md](domain/llm.md) | 3 | 752줄 | **포트-어댑터(헥사고날)** · ISP 위반 · 구조화 출력 · fail-open/closed |

---

# 3. 전체 요약 — 이 프로젝트를 한눈에

## 3-1. 기술 스택

| 항목 | 내용 |
|---|---|
| 프레임워크 | Spring Boot 3.4.5 / Java 17 툴체인 / Gradle |
| 저장소 | MySQL 8 + Redis 7 |
| 인증 | 자체 JWT (3-키 로테이션) + OAuth2 (Google, Kakao) |
| 외부 연동 | OpenAI · Gemini · Claude · Google TTS · DALL·E · 네이버 뉴스 · 사람인 · 카카오페이 · AWS S3 |
| 장애 대응 | Resilience4j (서킷브레이커 3 인스턴스 · 리트라이 · 타임리미터) |
| 관측 | Actuator + Micrometer/Prometheus + Loki |
| 배포 | GitHub Actions → Docker Hub → EC2 |
| 규모 | 도메인 15 · 컨트롤러 25 · 엔티티 27 · 스케줄러 5 · 18,216줄 |
| 테스트 | **1개** (`contextLoads`), CI는 `-x test`로 스킵 |

## 3-2. 아키텍처 지도

```
                        ┌─────────────────────────────────────┐
                        │  global/                            │
                        │  JWT · Security · 예외 · 응답 · S3  │
                        │  Redis · 스레드풀 · HTTP 클라이언트  │
                        └──────────────┬──────────────────────┘
                                       │ 모든 도메인이 의존
   ┌───────────────────────────────────┼───────────────────────────────────┐
   │                                   │                                   │
┌──▼──────────┐  ┌──────────────┐  ┌──▼───────────┐  ┌─────────────────┐  │
│ member      │  │ community    │  │ question     │  │ interview       │  │
│ 회원·인증   │◄─┤ 게시판·댓글  │  │ LLM 질의응답 │  │ 모의면접        │  │
└──┬──────────┘  │ 좋아요·신고  │  └──┬───────────┘  └────┬────────────┘  │
   │             │ AI 검열      │     │                   │               │
   │             └──┬───────────┘     ▼                   │               │
   │                │            ┌────────────┐           │               │
   ▼                │            │conversation│           │               │
┌─────────────┐     │            │ 대화 이력  │           │               │
│ point       │◄────┼────────────┴────────────┘           │               │
│ 포인트 원장 │     │                                     │               │
└──┬──────────┘     │       ┌─────────────────────────────▼────────────┐  │
   │                │       │ llm/  (포트-어댑터)                       │  │
   ▼                ├──────►│ InterviewerAiGateway                     │◄─┘
┌─────────────┐     │       │  ├── GeminiAiAdapter  (1,540줄)          │
│ pointTier   │     │       │  └── OpenAiAiAdapter  (1,137줄)          │
│ 등급        │     │       └──────────────────────────────────────────┘
└─────────────┘     │              ▲
                    ▼              │
              ┌──────────┐         │
              │ aibot    │─────────┘
              │ AI 답변  │
              └──────────┘

  [독립 수집·부가 도메인]
  payment(결제) · attendance(출석) · email(인증메일)
  newsData(뉴스) · recruitmentNotice(채용) · ai(AI소식·리더보드·추천)
```

## 3-3. 이 프로젝트에서 배울 만한 것 (상위 15개)

| 항목 | 위치 | 왜 좋은가 |
|---|---|---|
| **커밋 후 이벤트로 비동기 분리** | [community 1장](domain/community.md) | "커밋 전에는 다른 스레드가 못 본다"를 정확히 인식. 주석에 이유까지 기록 |
| **버전 키 캐시 무효화** | [global 7-2](global.md) | `KEYS`+`DEL` 없이 원자적 전체 무효화. 실무 패턴 |
| **JPQL 생성자 프로젝션** | [community 3-1](domain/community.md) | N+1을 원리적으로 차단 |
| **3중 동시성 방어** | [interview 2-1](domain/interview.md) | 선제검사 + DB제약 + 예외변환. 프로젝트 유일하게 완전 |
| **결제 멱등성 + 비관적 락** | [payment 3-1, 3-2](domain/payment.md) | `GET` 리다이렉트 승인에 필수. 이중 승인 차단 |
| **원장(ledger) 포인트 설계** | [point 1장](domain/point.md) | 러닝 밸런스 + 유니크 제약. 금융 시스템 정석 |
| **상태 기계 + 전이 메서드** | [aibot 1장](domain/aibot.md) | `markAsPosted(id)`가 두 변경을 원자적으로 묶음 |
| **포트-어댑터로 LLM 추상화** | [llm 1장](domain/llm.md) | Primary/Fallback 이중화를 몇 줄로 구현 |
| **서킷브레이커 인스턴스별 튜닝** | [community 6-4](domain/community.md), [newsData 4-2](domain/newsData.md) | 작업 성격에 맞게 실패율·재시도를 다르게 |
| **Min-Max 정규화 + 방향 반전** | [ai 1-2](domain/ai.md) | 스케일·방향이 다른 지표를 공정하게 합성 |
| **Tika 3단 MIME 폴백** | [question 2-6](domain/question.md) | 내용 → Content-Type → 확장자. 신뢰도 순서가 정확 |
| **스캔 PDF → 이미지 렌더링** | [question 2-6](domain/question.md) | 텍스트 없는 PDF를 LLM 비전으로 읽게 |
| **소유권 조건을 쿼리에 포함** | [conversation 3-3](domain/conversation.md) | IDOR 방어의 정석. 검증을 잊을 수 없음 |
| **캐시 장애 시 DB 폴백** | [community 4-2](domain/community.md) | Graceful Degradation. 캐시가 필수가 아님을 이해 |
| **`@ConditionalOnProperty` 기능 스위치** | [aibot 5-1](domain/aibot.md) | 빈 등록 단계에서 기능 전체를 끔 |

---

# 4. 🔴 지금 당장 고쳐야 할 것 (전체 종합)

**배포 중인 서비스에 있는 문제를 위험도 순으로 모았습니다.**

## 4-1. 보안 — 즉시 조치

| # | 문제 | 위치 | 결과 |
|---|---|---|---|
| **S1** | **누구나 남의 비밀번호를 바꿀 수 있다** — `PATCH /api/member/password/user/{userId}`가 무인증·무검증 | [member 7장](domain/member.md#7--치명적-누구나-남의-비밀번호를-바꿀-수-있다) | **전 계정 탈취.** `userId`가 순차 ID라 1부터 돌리면 전원 |
| **S2** | 이메일 인증이 서버에 증거를 남기지 않음 → S1의 우회 경로 | [email 6장](domain/email.md#6--이-도메인이-지키지-못한-것--비밀번호-재설정과의-연결) | S1과 결합 |
| **S3** | `GET /api/member/id?email=` 무인증 → 이메일로 `userId` 조회 | [member 4장](domain/member.md#4-membercontroller--엔드포인트-11개) | 계정 열거 + S1의 1단계 |
| **S4** | `/api/boards/**` 등 **쓰기 API가 무인증** (`// 임시` 주석) | [global 6-1](global.md#③-url별-권한--️-이-프로젝트-최대의-문제) | 무인증 생성/수정/삭제 + NPE 500 |
| **S5** | `POST /api/point`로 **원하는 만큼 포인트 생성** | [point 6장](domain/point.md#6-pointcontroller--테스트용-api가-열려-있다) | 유료 기능 무한 사용 |
| **S6** | 피드백 무한 제출 → **무한 포인트** + 리더보드 조작 | [question 6-2](domain/question.md#6-2--무한-포인트-획득이-가능하다) | 같은 결과, 정상 기능으로 위장 |
| **S7** | 신고 목록 조회·삭제에 **관리자 권한 없음** | [community 5-2](domain/community.md#5-2--boardreportservice--관리자-api에-권한이-없다) | 프라이버시 + 신고 무력화 |
| **S8** | 메일 발송 횟수 제한 없음 | [email 3-4](domain/email.md#3-4--발송-횟수-제한도-없다) | 메일 폭탄 + SMTP 한도 소진 |
| **S9** | AI 답변을 검열하지 않음 (`MODERATED`로 거짓 기록) | [aibot 2-2](domain/aibot.md#2-2--검열을-하지-않는데-검열-완료로-기록한다) | 프롬프트 인젝션 노출 |
| **S10** | 검열 파싱 실패 시 **fail-open** (통과시킴) | [llm 4장](domain/llm.md#4--검열-폴백이-fail-open이다) | 검열 우회 |
| **S11** | 검열 전(`PENDING`) 게시글이 목록에 노출 + 검열 실패 재시도 없음 | [community 1-1](domain/community.md#1-1--그런데-검열되기-전에-이미-노출된다) | 부적절 콘텐츠가 영구 노출 가능 |
| **S12** | 게시글 검열에 서킷브레이커·폴백이 적용 안 됨 (self-invocation) | [community 6-6](domain/community.md#6-6--fallbackmethod의-시그니처-규칙) | S11의 원인 |

## 4-2. 정합성 — 데이터가 어긋난다

| # | 문제 | 위치 | 결과 |
|---|---|---|---|
| **C1** | `private @Transactional` **4곳** → 트랜잭션 완전 무시 | [point 3-1](domain/point.md#3-1--버그--private-메서드의-transactional은-무시된다) | 포인트/질문 원자성 없음, `Member.points` 미갱신 |
| **C2** | `REQUIRES_NEW` **3곳** self-invocation → 무효 | [attendance 3-2](domain/attendance.md#3-2--왜-무효인가--self-invocation) | 포인트 실패 시 출석도 롤백 (의도와 반대) |
| **C3** | 포인트 재시도가 원리적으로 불가 (rollback-only) | [point 3-2](domain/point.md#3-2--버그--재시도-로직이-원리적으로-동작할-수-없다) | 동시 요청 시 포인트 유실 |
| **C4** | 결제 포인트 적립 실패 시 **결제 성공 기록도 롤백** | [payment 3-5](domain/payment.md#️-하지만-실제로는-롤백됩니다) | 돈은 나갔는데 기록 없음 |
| **C5** | `readOnly = true`에서 조회수 증가 → 유실 | [community 4-6](domain/community.md#4-6--readonly--true-안에서-조회수를-올린다) | 조회수가 항상 0 |
| **C6** | LLM 실패 시 **포인트 환불 없음** | [question 3-2](domain/question.md#3-2--llm-실패-시-포인트를-환불하지-않는다) | 장애 시 사용자가 계속 손실 |
| **C7** | 좋아요 유니크 제약 없음 → 중복 좋아요 후 영구 불일치 | [community 5-1](domain/community.md#5-1--boardlikeservice--좋아요-중복-방지가-없다) | 좋아요 수 왜곡 |
| **C8** | 이메일·닉네임 유니크 제약 없음 | [member 1-2](domain/member.md#️-문제--유니크-제약이-없다) | 동시 가입 시 **로그인 영구 불가** |
| **C9** | 회원탈퇴가 FK 위반으로 실패 | [member 3-5](domain/member.md#3-5-deletemember--물리-삭제의-위험) | 탈퇴 기능 사실상 미동작 |
| **C10** | `point_tier`/`ai_models`/`ai_model_integrated` 초기 데이터 없음 | [pointTier 2-4](domain/pointTier.md#2-4-이-테이블은-누가-채우나--아무도-채우지-않는다) | **회원가입 실패** + 목록 빈칸 |

## 4-3. 거짓 데이터 — 실패를 성공으로 위장

**같은 종류의 문제가 4곳에 있습니다. 이 프로젝트의 가장 특징적인 결함입니다.**

| # | 문제 | 위치 | 사용자가 보는 것 |
|---|---|---|---|
| **F1** | AI 평가 실패 시 **가짜 점수** (45/40/50/42/38) + 가짜 칭찬 | [interview 3-5](domain/interview.md#3-5--ai-평가-실패-시-가짜-점수를-반환한다) | 50포인트 내고 자기 답변과 무관한 점수 |
| **F2** | LLM 실패 시 **폴백 사과문**을 댓글로 게시 → `POSTED`로 굳음 | [aibot 2-3](domain/aibot.md#2-3--폴백-답변이-댓글로-게시된다) | "AI 시스템에 문제가 발생했습니다" 댓글 |
| **F3** | 크롤링 실패 문구가 프롬프트로 → **LLM이 기사를 지어냄** | [ai 3-10](domain/ai.md#3-10--크롤링-실패-문구가-llm-프롬프트에-들어간다) | 사실이 아닌 뉴스 |
| **F4** | 검열 안 하고 `MODERATED` 기록 / 신뢰도 하드코딩 `0.90` | [aibot 2-1, 2-2](domain/aibot.md#2-1-신뢰도가-하드코딩되어-있다) | 상태를 신뢰할 수 없음 |

> **원칙: 폴백은 "기능 저하"여야 하고 "거짓"이어서는 안 됩니다.**
>
> 이 프로젝트에는 **올바른 폴백의 예도 있습니다.**
> - TTS 실패 → `tts = null` (음성 없이 텍스트만) ✅ [interview 3-6](domain/interview.md#3-6--tts-실패-처리는-올바르다)
> - 검열 실패 → 차단 (fail-closed) ✅ [community 6-5](domain/community.md#6-5--폴백-전략--primary--fallback--보수적-차단)
> - 캐시 실패 → DB 조회 ✅ [community 4-2](domain/community.md#4-2--잘-만든-것--캐시-장애-시-db-폴백)
>
> **같은 프로젝트에서 폴백 철학이 갈립니다.** 정직한 폴백으로 통일해야 합니다.

## 4-4. 운영 — 서비스가 멈출 수 있다

| # | 문제 | 위치 | 결과 |
|---|---|---|---|
| **O1** | **`RestTemplate` 타임아웃 무한** (빈 + `llm` 어댑터 직접 생성) | [llm 3장](domain/llm.md#3--타임아웃-없는-resttemplate) | LLM 응답 없으면 스레드 영구 점유 |
| **O2** | **결제 비관적 락을 잡고 외부 API 호출** + O1 | [payment 3-3](domain/payment.md#3-3--그런데-락을-잡은-채로-외부-api를-호출한다) | **행 잠금 무한 유지** |
| **O3** | `HttpClient`를 호출마다 생성 + 타임아웃 없음 | [ai 3-3](domain/ai.md#3-3--httpclient를-호출마다-새로-만든다) | 스케줄러 5개가 함께 멈춤 |
| **O4** | `@Transactional` 안에서 LLM/TTS/외부 API 호출 (**5개 도메인**) | [aibot 3장](domain/aibot.md), [interview 3-4](domain/interview.md), [question](domain/question.md), [newsData 3-4](domain/newsData.md), [recruitmentNotice 2-3](domain/recruitmentNotice.md) | DB 커넥션 10~40초 점유 |
| **O5** | `EmailService`가 `HashMap`에 인증번호 보관 | [email 2장](domain/email.md#2-hashmap을-쓴-것이-만드는-문제-6가지) | 스레드 비안전 + 메모리 누수 + **서버 2대 불가** |
| **O6** | `SSE`가 동기 실행 → 스트리밍 무효 + 요청 스레드 블로킹 | [question 1장](domain/question.md#1--sse-스트리밍이-스트리밍되지-않는다) | 동시 LLM 요청이 서버 전체를 막음 |
| **O7** | 같은 파일을 2회 처리 (PDF 300 DPI 렌더링 중복) | [question 2장](domain/question.md#2--같은-파일을-23번-처리한다) | 힙 70MB/건 |
| **O8** | `Dotenv.load()` static **7곳** | [global 1-7](global.md#1-7-공통-문제--dotenvload를-static-필드에서-호출) | `.env` 없으면 기동 실패, 테스트 불가 |
| **O9** | 만료 토큰이 **401 대신 500** | [global 5-6](global.md#5-6-jwtauthorizationfilter--요청마다-토큰-검사-인가-authorization) | 프런트가 재로그인 분기 불가 |
| **O10** | `409`/`403`/`400` 의도가 전부 **500**으로 나감 | [interview 3-3](domain/interview.md#3-3--상태-코드를-담은-예외가-전부-500이-된다) | 클라이언트가 원인 구분 불가 |
| **O11** | 목록 조회에 페이징 없음 (**4개 도메인**) | [newsData 3-8](domain/newsData.md), [recruitmentNotice 2-8](domain/recruitmentNotice.md), [point 2-3](domain/point.md), [conversation 3-6](domain/conversation.md) | 수만 건을 한 번에 응답 |
| **O12** | JSON을 문자열 연결로 조립 → 개행에서 깨짐 | [ai 3-2](domain/ai.md#3-2--json을-문자열로-직접-만든다--개행에서-깨진다) | AI 소식 생성 실패 |
| **O13** | 결제 타임아웃 3분 / 검사 1시간 | [payment 4-2](domain/payment.md#4-2--타임아웃-3분인데-1시간마다-검사한다) | 만료된 결제 URL 재사용 |
| **O14** | `ddl-auto: update` (prod) + Flyway 미도입 | [기초개념 6-9](00-기초개념.md#6-9-ddl-auto--스키마를-누가-만드나) | 스키마 드리프트 |
| **O15** | `GET`이 상태를 변경 (`/api/payment/cancel`, `/fail`) | [payment 5-1](domain/payment.md#5-1--get으로-상태를-바꾼다) | 프리페치·캐시로 의도치 않은 실행 |

---

# 5. 권장 작업 순서

**한 번에 다 고칠 수 없으므로 단계로 나눴습니다.**

## 1단계 — 보안 (하루)

배포 중인 서비스에 열려 있는 구멍을 먼저 닫습니다.

```
① S1 + S2 + S3   비밀번호 재설정을 일회용 토큰 방식으로 전환
                  → /api/member/password/user/{userId}, /api/member/id 삭제
                  → email 인증 성공 시 Redis에 resetToken 발급
② S4             /api/boards/** 등에 HTTP 메서드 제한 (GET만 permitAll)
③ S5 + S6        POST /api/point 삭제, 피드백 하루 1회 제한
④ S7             신고 API에 @PreAuthorize("hasRole('ADMIN')") — ADMIN 개념 신설 필요
⑤ S8            메일 발송 Redis rate limit
```

**이 단계만 끝내면 "누가 마음대로 할 수 있는" 상태가 사라집니다.**

## 2단계 — 타임아웃과 프록시 (하루)

**한 줄~수십 줄짜리 변경으로 가장 큰 위험을 줄이는 단계입니다.**

```
① O1 + O2 + O3   모든 HTTP 클라이언트에 타임아웃 설정
                  → RestTemplate 빈, GeminiAiAdapter/OpenAiAiAdapter,
                    WebClient(응답 타임아웃), AiUpdatesService(HttpClient 빈화)
② C1             private @Transactional 4곳 → 별도 빈으로 분리
③ C2             REQUIRES_NEW 3곳 → 별도 빈으로 분리
④ O9 + O10       JwtAuthorizationFilter 예외 처리(401),
                  GlobalExceptionRestAdvice에 ResponseStatusException/AccessDeniedException/
                  IllegalArgumentException/MaxUploadSizeExceededException 핸들러 추가
⑤ C5             getBoardDetail의 readOnly 제거 또는 벌크 UPDATE로 분리
```

> **③④를 검증하는 방법**: 트랜잭션 로그를 켜서 실제 경계를 확인하세요.
> ```yaml
> logging.level.org.springframework.transaction.interceptor: TRACE
> ```
> → [attendance 3-3](domain/attendance.md#결과--getexistingattendanceinnewtransaction의-이름이-거짓말이-된다)

## 3단계 — 정직한 폴백 (반나절)

```
① F1   면접 평가 실패 → 가짜 점수 대신 503 + "잠시 후 다시 조회"
        (답변은 이미 DB에 있으니 재시도 가능)
② F2   AI 답변 실패 → 폴백 사과문 게시하지 않고 예외 전파 → 복구 스케줄러가 재시도
③ F4   markAsModerated() 앞에 TextModerationService.moderateText() 호출
④ F3   크롤링 실패 항목은 프롬프트에서 제외
⑤ S10  검열 파싱 실패 시 예외를 던져 상위 fail-closed 폴백이 작동하게
```

## 4단계 — 데이터 정합성 (2~3일)

```
① C10  point_tier / ai_models / ai_model_integrated 초기화 컴포넌트
        (AiMemberInitializer 방식 재사용) — 로컬 세팅이 즉시 쉬워집니다
② C8   member(email, provider) / nickname 유니크 제약 + 기존 중복 정리
③ C7   board_like(board_id, member_id) 유니크 제약 + 충돌 처리
④ C3   포인트 재시도를 REQUIRES_NEW 별도 빈으로 (2단계 ②의 연장)
⑤ C4   결제 포인트 적립을 @TransactionalEventListener(AFTER_COMMIT)로 이동
⑥ C6   질문 포인트 차감을 LLM 성공 후로 이동
⑦ C9   회원탈퇴를 soft delete로 + PrincipalDetails.isEnabled() 연결
⑧ S11  목록 쿼리에서 PENDING 제외 + 검열 복구 스케줄러
⑨ S12  moderateTitleAndContent self-invocation 제거
```

## 5단계 — 성능·운영 (1주)

```
① O4    트랜잭션 안의 외부 호출을 5개 도메인에서 분리
② O6    SSE를 별도 스레드풀로 (question 도메인, 전용 llmExecutor)
③ O7    파일 처리 1회로 통합 + PDF DPI 150으로
④ O5    EmailService 인증번호를 Redis로 (TTL + 시도 제한 포함)
⑤ O11   4개 도메인에 페이징 도입
⑥ O12   AiUpdatesService의 JSON을 ObjectMapper로
⑦ O13   결제 타임아웃 조회 조건에 시간 포함
⑧ O15   GET → POST (payment cancel/fail)
⑨        pointTier 메모리 캐싱 (목록 조회 쿼리 N회 제거)
⑩        인덱스 추가 (board, news, recruitment, model_feedback)
```

## 6단계 — 테스트와 구조 (지속)

```
① 테스트 도입
   - 시작점: ModelScoreCalculator (순수 계산, 스프링 불필요)
   - 다음: PointTransactionService (testcontainers로 동시성 검증)
   - CI에서 -x test 제거
   - MorningstarBackApplicationTests의 패키지/디렉터리 불일치 수정

② O8    Dotenv 7곳을 @Value/@ConfigurationProperties로 통일
③ O14   Flyway 도입 (db/migration 폴더가 이미 있음)
④        llm 포트를 3개로 분리 (ISP) + 도메인 타입 의존 제거
⑤        question 3사 서비스 1,300줄 → ChatStreamGateway로 통합
⑥        Gemini/OpenAiAiAdapter 프롬프트를 resources/prompts로 분리
⑦        AWS SDK v1 → v2 (spring-cloud-starter-aws 2.2.6은 EOL)
⑧        System.out/printStackTrace 27건 → log
⑨        빈 파일·죽은 코드 삭제 (아래 목록)
```

### 삭제 대상 (죽은 코드)

| 파일/코드 | 문서 |
|---|---|
| `PointTierController` (전체 주석), `PointTierRequestDto`, `PointTierResponseDto` (빈 클래스) | [pointTier](domain/pointTier.md#파일-지도) |
| `AIAssistantService`, `AIAssistantEventListener` (빈 클래스) | [community 5-6](domain/community.md#5-6--죽은-클래스-2개) |
| `LikeRepository` (`BoardLikeRepository`와 중복) | [community 3-7](domain/community.md#3-7--중복-리포지토리--likerepository) |
| `RedisCacheConfig` (빈 파일) | [global 7-5](global.md#7-5-rediscacheconfig--빈-파일) |
| `/api/member/test/jwt` + `TestUserResponseDto` | [member 4-2](domain/member.md#4-2-apimembertestjwt--프로덕션에-남은-테스트-코드) |
| `generateImageWithStability`, `generateImageWithDeepAI` (미호출, 키도 없음) | [ai 3-4](domain/ai.md#3-4--쓰이지-않는-이미지-생성-메서드-2개--그리고-없는-api-키) |
| `TextModerationService.moderateOptimized` + 미사용 getter 3개 | [community 6-7](domain/community.md#6-7--죽은-코드--moderateoptimized) |
| `conversation` 리포지토리 미사용 메서드 7개 + `ConversationSearchRequestDto` | [conversation 2-3](domain/conversation.md#2-3--중복-메서드와-미사용-메서드) |
| `aibot` 리포지토리 미사용 메서드 6개 + 미사용 필드 3개 | [aibot 2-4](domain/aibot.md#2-4--나머지-todo들) |
| `InterviewMode.TURN_VOICE_SERVER`, `REALTIME_WEBRTC` + 미사용 설정 3개 + 죽은 시큐리티 경로 2개 | [interview 3-13](domain/interview.md#3-13--미구현-기능-설정이-남아-있다) |
| `MockMultipartFile` 사용 + `spring-test` implementation 의존성 | [ai 3-8](domain/ai.md#3-8--mockmultipartfile을-운영-코드에서-쓴다) |

---

# 6. 이 프로젝트를 읽으며 배운 큰 교훈 4가지

## 6-1. 프록시를 모르면 의도가 조용히 사라진다

**이 프로젝트 문제의 가장 큰 덩어리가 여기서 나옵니다.**

```java
@Transactional
private void createPointInternal(...) { ... }        // 무시됨

@Transactional(propagation = REQUIRES_NEW)
public void processPointReward(...) { ... }           // this.호출 → 무시됨

@CircuitBreaker(fallbackMethod = "...")
public ... moderateText(...) { ... }                  // self-invocation → 무시됨
```

**컴파일 에러도, 경고도, 로그도 없습니다.** 코드는 "그렇게 하겠다"고 선언했지만
프레임워크는 아무 일도 하지 않습니다.

> **`@Transactional`, `@Async`, `@Cacheable`, `@CircuitBreaker`, `@Retry`, `@PreAuthorize`는
> 모두 프록시 기반입니다. "public + 다른 빈에서 호출" 조건을 만족해야 동작합니다.**
>
> → [기초개념 4-4](00-기초개념.md#4-4-프록시--spring-마법의-정체)

## 6-2. 폴백은 기능 저하여야 하고 거짓이어서는 안 된다

| 올바른 폴백 | 거짓 폴백 |
|---|---|
| TTS 실패 → `null` (음성 없이 진행) | 평가 실패 → **가짜 점수 45/40/50** |
| 캐시 실패 → DB 조회 | LLM 실패 → **사과문을 답변으로 게시** |
| 검열 실패 → 차단 (fail-closed) | 크롤링 실패 → **LLM이 기사를 지어냄** |
| LLM Primary 실패 → Fallback 시도 | 검열 안 함 → **`MODERATED`로 기록** |

**판단 기준**: "이 폴백 결과를 사용자가 진짜라고 믿게 되는가?"
그렇다면 폴백이 아니라 **실패를 알려야** 합니다.

## 6-3. 경계는 지켜야 의미가 있다

이 프로젝트의 문제는 대부분 **경계 관리 실패**입니다.

| 경계 | 위반 사례 |
|---|---|
| **트랜잭션 경계** | 외부 API 호출이 트랜잭션 안 (5개 도메인) |
| **계층 경계** | 서비스가 `ResponseDTO` 반환 / `PrincipalDetails` 요구 / 엔티티에 `@JsonFormat` |
| **도메인 경계** | `llm` 포트가 `community`·`interview` 타입 import (순환) / 공용 유틸이 `newsData` 안 |
| **설정 경계** | `Dotenv.load()` 7곳이 Spring 설정 체계 우회 |
| **HTTP 의미론 경계** | `GET`이 상태 변경 |

**"어디까지가 내 책임인가"를 정하지 않으면 코드가 서로 스며듭니다.**

## 6-4. 같은 문제를 두 번 풀면 두 번째가 나아진다

`recruitmentNotice`(초기)와 `newsData`(개선)를 비교하면 성장이 보입니다.

| 항목 | recruitmentNotice | newsData |
|---|---|---|
| 스케줄러 예외 | 재던짐 ❌ | 삼킴 + 이유 주석 ✅ |
| 타임존 | `systemDefault()` ⚠️ | `ZoneId.of("Asia/Seoul")` ✅ |
| 환경별 스위치 | 없음 | `news.active` ✅ |
| 후속 작업 | 없음 | 이벤트로 분리 ✅ |
| 벌크 업데이트 | 없음 | `@Modifying` ✅ |
| 서킷브레이커 | 없음 | 있음 ✅ |

**두 도메인을 나란히 읽는 것이 이 프로젝트를 이해하는 가장 좋은 방법입니다.**
`community`의 이벤트 분리, `interview`의 3중 방어, `payment`의 멱등성도
**먼저 실패해보고 배운 흔적**으로 보입니다.

---

# 7. 문서 사용법

- 각 문서는 **독립적으로 읽을 수 있게** 썼습니다. 다른 문서 내용이 필요하면 링크가 있습니다.
- 코드 블록의 `★`는 **주목할 부분**, `⚠️`/`🔴`/`🟠`/`🟡`/`🟢`는 **문제 심각도**입니다.
- 각 문서 끝에 **"고쳐야 할 것"(우선순위 표)** 과 **"잘 만든 것"** 이 있습니다.
- 수정 작업을 할 때는 이 README의 [5장](#5-권장-작업-순서)을 체크리스트로 쓰세요.

## 문서에 없는 것

시간·분량 때문에 **구조와 역할만 정리하고 상세 분석을 하지 않은 파일**이 있습니다.

| 파일 | 문서 |
|---|---|
| `PopularNewsDataService` (318줄), `NewsImageService` (229줄), `ImageGenerationCircuitBreakerService` (271줄) | [newsData 4장](domain/newsData.md#4-나머지-서비스-3개--구조만) |
| `GeminiAiAdapter`/`OpenAiAiAdapter`의 면접 관련 메서드 상세 (프롬프트 본문) | [llm](domain/llm.md) |
| `DocumentContentExtractor` (246줄), `GeminiTtsService`, `GoogleCloudTtsService`, `QuestionCatalog`, `PlanParser` | [interview](domain/interview.md) |
| `AIAnalysisService`, `AiNewsService`, `RecommendationService` | [ai](domain/ai.md) |
| `PaymentProperties`, `getPaymentStatus`/`cancelPayment`/`failPayment` 구현부 | [payment](domain/payment.md) |

**[newsData 5장](domain/newsData.md#확인이-필요한-것-이-문서에서-다루지-않은-3개-서비스)에
확인해볼 지점을 목록으로 남겨두었습니다.**
