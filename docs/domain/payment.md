# payment — 카카오페이 결제 (비관적 락 · 멱등성 · 금액 검증)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [point.md](point.md)
>
> 파일 17개. **이 프로젝트에서 동시성·정합성을 가장 진지하게 다룬 도메인**입니다.
> 비관적 락, 멱등성 처리, 금액 검증, 타임아웃 정리가 모두 들어가 있습니다.
>
> **그런데 비관적 락을 잡은 채로 외부 API를 호출합니다.**
> `RestTemplate`에 타임아웃이 없어서, 카카오페이가 응답하지 않으면 **행 잠금이 무한히 유지됩니다.**

---

## 파일 지도

```
domain/payment/
├── controller/
│   ├── PaymentController.java          /api/payment  (ready, cancel, fail, status)
│   └── PaymentRedirectController.java  /api/payment/redirect  (success, fail, cancel)
├── dto/
│   ├── request/PaymentReadyRequestDto.java
│   └── response/  PaymentReadyResponseDto, PaymentApproveResponseDto,
│                  PaymentStatusResponseDto, Amount
├── entity/
│   ├── Payment.java                    결제 1건
│   └── PaymentStatus.java              READY | SUCCESS | FAIL | CANCEL | TIMEOUT
├── exception/  PaymentNotFoundException, PaymentAlreadyProcessedException,
│               PaymentAmountMismatchException, PaymentExternalApiException
├── repository/PaymentRepository.java   ★ 비관적 락 쿼리
├── service/
│   ├── PaymentService.java             263줄 ★ 핵심
│   └── PaymentSchedulerService.java    타임아웃 정리
└── util/PaymentProperties.java         카카오페이 설정
```

## 결제 흐름 (카카오페이 준비-승인 2단계)

```
① 사용자: "1만원 후원"
   POST /api/payment/ready  { amount: 10000 }
        │
        ├─ 기존 READY 결제 확인 → 같은 금액이면 재활용 (재사용)
        ├─ 카카오페이 /ready API 호출 → tid + 결제 페이지 URL 획득
        ├─ 기존 READY 결제가 금액이 다르면 CANCEL로 변경
        └─ Payment 저장 (status = READY)
   → { paymentId, tid, next_redirect_pc_url, ... }

② 사용자가 next_redirect_pc_url로 이동 → 카카오페이 앱/웹에서 결제

③ 카카오페이가 approval_url로 사용자 브라우저를 리다이렉트
   GET /api/payment/redirect/success?pg_token=xxx&partner_order_id=ORDER_yyy
        │
        └─ paymentApproveByPgToken(pgToken, partnerOrderId)
              ├─ 결제 조회 (상태 무관)
              ├─ 이미 SUCCESS면 → 기존 결과 반환 (멱등성)
              ├─ READY가 아니면 → PaymentAlreadyProcessedException
              ├─ READY만 비관적 락으로 재조회 (SELECT ... FOR UPDATE)
              ├─ 카카오페이 /approve API 호출
              ├─ 금액 검증 (요청 금액 == 응답 금액)
              ├─ status = SUCCESS
              └─ 포인트 적립 (실패해도 결제는 SUCCESS 유지)
   → 프론트 성공 페이지로 리다이렉트

[1시간마다]  PaymentSchedulerService
   READY 상태로 3분 넘은 결제를 TIMEOUT으로 변경
```

---

# 1. `Payment` 엔티티

```java
@Entity
@Getter
@NoArgsConstructor              // ⚠️ public
@AllArgsConstructor
@Builder
@Table(name = "payment")
public class Payment extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(unique = true)                          // ★ 카카오페이 거래 ID — 유니크
    private String tid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentStatus status;

    @Column(nullable = false) private Integer amount;
    @Column(nullable = false) private String partnerOrderId;     // ⚠️ 유니크 제약 없음
    @Column(nullable = false) private String itemName;

    // 카카오페이가 준 리다이렉트 URL 5종
    @Column(name = "redirect_url")        private String redirectUrl;
    @Column(name = "app_redirect_url")    private String appRedirectUrl;
    @Column(name = "mobile_redirect_url") private String mobileRedirectUrl;
    @Column(name = "android_app_scheme")  private String androidAppScheme;
    @Column(name = "ios_app_scheme")      private String iosAppScheme;

    public void updateStatus(PaymentStatus status) { this.status = status; }
}
```

## 1-1. 🟢 `tid`에 유니크 제약이 있다

```java
@Column(unique = true)
private String tid;
```

`tid`는 **카카오페이가 발급하는 거래 고유 ID**입니다.
같은 `tid`가 두 행에 있으면 **한 거래가 두 번 기록**된 것이므로 반드시 막아야 합니다.

**이 프로젝트에서 `unique = true`를 제대로 쓴 몇 곳 중 하나입니다.**
`link`(뉴스·채용공고), `email`/`nickname`(회원), `(board_id, member_id)`(좋아요)에는 없습니다.

## 1-2. 🟠 `partnerOrderId`에는 유니크 제약이 없다

```java
@Column(nullable = false)
private String partnerOrderId;
```

**그런데 승인 단계에서 이 값으로 결제를 찾습니다.**

```java
Payment payment = paymentRepository.findByPartnerOrderId(partnerOrderId)   // Optional 반환
        .orElseThrow(() -> new PaymentNotFoundException());
```

`Optional<Payment>`를 기대하는데 **같은 `partnerOrderId`가 2건이면
`IncorrectResultSizeDataAccessException`** 이 발생합니다.

`partnerOrderId`는 `"ORDER_" + UUID.randomUUID()`로 만들므로 충돌 확률은 사실상 0입니다.
**하지만 조회 키에는 제약을 두는 것이 원칙입니다.**

```java
@Column(nullable = false, unique = true, length = 64)
private String partnerOrderId;
```

## 1-3. `PaymentStatus` — 5단 상태

```java
public enum PaymentStatus {
    READY,      // 결제 준비
    SUCCESS,    // 결제 성공
    FAIL,       // 결제 실패
    CANCEL,     // 결제 취소
    TIMEOUT     // 결제 타임아웃
}
```

**상태 전이도**

```
        ┌──────────────────────────────────► CANCEL   (사용자 취소 / 새 결제로 대체)
        │
     READY ──────┬──────► SUCCESS   (승인 성공 + 금액 일치)
        │        │
        │        └──────► FAIL      (금액 불일치)
        │
        └───────────────► TIMEOUT   (3분 경과, 스케줄러)
```

**`TIMEOUT`을 `FAIL`과 구분한 것이 좋습니다.**
"결제를 시도했다가 실패"와 "결제 창을 열어놓고 이탈"은 **분석 관점에서 다른 사건**입니다.
이탈률이 높으면 UX 문제, 실패율이 높으면 결제 연동 문제입니다.

**`updateStatus(status)` 하나로 모든 전이를 처리합니다.**

`aibot`/`interview`처럼 **의미 있는 이름의 메서드**로 나누면 더 안전합니다.

```java
public void markAsSuccess() { this.status = PaymentStatus.SUCCESS; }
public void markAsFailed()  { this.status = PaymentStatus.FAIL; }
public void markAsTimeout() { this.status = PaymentStatus.TIMEOUT; }
public void markAsCancelled() { this.status = PaymentStatus.CANCEL; }
```

**`updateStatus(PaymentStatus.READY)`처럼 되돌리는 호출을 막을 수 없다**는 것이
현재 방식의 약점입니다. → [aibot.md 1-2](aibot.md#1-2-엔티티가-상태-전이를-통제한다)

## 1-4. 🟡 리다이렉트 URL 5개를 저장한다

```java
private String redirectUrl;         // PC
private String appRedirectUrl;      // 앱
private String mobileRedirectUrl;   // 모바일 웹
private String androidAppScheme;    // 안드로이드 딥링크
private String iosAppScheme;        // iOS 딥링크
```

**카카오페이 `/ready` 응답을 그대로 보관합니다.**

**이 값들은 일회성입니다.** 결제가 끝나면 무효가 되고, 다시 쓸 수 없습니다.
**"기존 READY 결제 재활용" 기능([2-1](#2-1--기존-ready-결제-재활용))을 위해 필요**하지만,
`SUCCESS` 이후에는 죽은 데이터입니다.

**결제 이력 테이블에 URL 5개가 영구히 남습니다.** 결제 10만 건이면 URL 50만 개입니다.

> 결제 준비 정보는 **Redis에 TTL과 함께** 두고, `payment` 테이블에는
> `tid`, `amount`, `status`, `partnerOrderId`만 남기는 것이 깔끔합니다.
> (이 프로젝트에는 Redis가 이미 있습니다.)

---

# 2. `PaymentService.paymentReady` — 준비 단계

## 2-1. 🟢 기존 READY 결제 재활용

```java
@Transactional
public PaymentReadyResponseDto paymentReady(PaymentReadyRequestDto dto, PrincipalDetails principalDetails) {
    ...
    // 기존 READY 상태 결제 확인 (먼저 확인만, 아직 변경하지 않음)
    Optional<Payment> existingPayment = paymentRepository
            .findByMemberIdAndStatus(member.getId(), PaymentStatus.READY);

    // 기존 결제가 있고 금액이 같으면 재활용
    if (existingPayment.isPresent()) {
        Payment payment = existingPayment.get();
        if (payment.getAmount().equals(dto.amount())) {
            log.info("기존 결제 재활용 - 회원ID: {}, TID: {}, 금액: {}", ...);
            return PaymentReadyResponseDto.fromEntity(payment);       // ★ API 호출 없이 반환
        }
    }
    ...
}
```

**해결하는 문제**: 사용자가 결제 버튼을 두 번 누르거나 뒤로가기 후 다시 시도하면
**READY 결제가 계속 쌓입니다.** 그러면:

- 카카오페이 API를 불필요하게 호출합니다
- `payment` 테이블에 미완결 행이 늘어납니다
- 어느 결제가 진행 중인지 알 수 없습니다

**금액이 같으면 기존 것을 그대로 주는 것이 정확한 판단입니다.**

## 2-2. 🟢 외부 API 성공을 확인한 뒤에 기존 결제를 정리한다

```java
try {
    // 먼저 카카오페이 API 호출 (새 결제 생성 가능한지 확인)
    PaymentReadyResponseDto paymentReadyResponseDto = restTemplate.postForObject(
            paymentProperties.getReadyUrl(), requestEntity, PaymentReadyResponseDto.class);

    // API 성공했으니 이제 기존 결제 정리 (금액이 다른 경우만)
    if (existingPayment.isPresent()) {
        Payment payment = existingPayment.get();
        if (!payment.getAmount().equals(dto.amount())) {
            payment.updateStatus(PaymentStatus.CANCEL);
            log.info("새 결제 성공으로 기존 결제 취소 - ...");
        }
    }

    Payment newPayment = dto.toEntity(member, paymentReadyResponseDto, partnerOrderId);
    paymentRepository.save(newPayment);
    return paymentReadyResponseDto;

} catch (RestClientException e) {
    // API 실패시 기존 결제는 그대로 유지 (사용자가 기존 결제로 계속 진행 가능)
    log.error("카카오페이 결제 준비 API 호출 실패 - 회원ID: {}, 기존 결제 유지, 오류: {}", ...);
    throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다");
}
```

**순서가 매우 정확합니다.** 주석에도 이유가 적혀 있습니다.

```
[잘못된 순서]                          [이 프로젝트의 순서]
① 기존 결제 CANCEL                     ① 카카오페이 API 호출
② 카카오페이 API 호출 → 실패!           ② 성공했으면 기존 결제 CANCEL
③ 기존 결제는 이미 취소됨               ③ 새 결제 저장
→ 사용자가 진행할 결제가 하나도 없음     → 실패 시 기존 결제로 계속 진행 가능
```

**"되돌릴 수 없는 외부 작업을 먼저, 되돌릴 수 있는 내부 작업을 나중에"** 라는 원칙입니다.

> 엄밀히는 `@Transactional`이므로 예외 발생 시 `payment.updateStatus(CANCEL)`도 롤백됩니다.
> 즉 순서를 바꿔도 결과는 같습니다. **하지만 트랜잭션에 의존하지 않고
> 코드 순서로도 안전하게 만든 것**은 좋은 습관입니다
> (나중에 트랜잭션을 쪼개도 동작이 유지됩니다).

## 2-3. 🟠 `findByMemberIdAndStatus`가 `Optional`을 반환한다

```java
Optional<Payment> findByMemberIdAndStatus(Long memberId, PaymentStatus status);
```

**한 회원이 `READY` 결제를 2건 이상 가질 수 있습니다.**

```
동시에 두 번 결제 요청 (더블 클릭, 두 탭)
  ↓
둘 다 existingPayment 없음 → 둘 다 카카오페이 API 호출 → 둘 다 save
  ↓
READY 결제 2건 생성
  ↓
다음 요청부터 findByMemberIdAndStatus가 2건을 만나
  → IncorrectResultSizeDataAccessException → 500
  → 해당 회원은 READY 결제를 정리할 때까지 결제 불가
```

**한 번 이 상태가 되면 그 회원은 결제를 할 수 없습니다.**

**해결 방향**

```java
// ① 여러 건을 허용하고 최신 것만 사용
Optional<Payment> findFirstByMemberIdAndStatusOrderByCreatedAtDesc(Long memberId, PaymentStatus status);

// ② 또는 DB 제약 — 회원당 READY는 하나만
//    MySQL은 부분 유니크 인덱스를 지원하지 않으므로 애플리케이션 락이 필요
//    (PostgreSQL: CREATE UNIQUE INDEX ... WHERE status = 'READY')
```

**②를 MySQL에서 하려면** 회원 단위 락(비관적 락 또는 Redis 락)이 필요합니다.
승인 단계에서는 비관적 락을 쓰는데 **준비 단계에는 없습니다.**

## 2-4. 🟡 VAT 계산

```java
int vatAmount = (int) (paymentReadyRequestDto.amount() * 0.1);
parameters.put("vat_amount", String.valueOf(vatAmount));
parameters.put("tax_free_amount", "0");
```

**`amount`가 부가세 포함 금액이면 계산식이 다릅니다.**

```
총액 11,000원 (VAT 포함)
  → 공급가액 = 11,000 / 1.1 = 10,000
  → 부가세   = 11,000 / 11  =  1,000       ← 총액 × 1/11

이 코드: 11,000 × 0.1 = 1,100             ← 100원 과다
```

**카카오페이의 `vat_amount`는 "총액에 포함된 부가세"** 이므로
`amount / 11`이 맞습니다.

> 후원(기부)이라면 부가세 대상이 아닐 수도 있어 `tax_free_amount`에 전액을 넣는 것이
> 맞을 수 있습니다. **회계 요구사항에 따라 달라지므로 확인이 필요합니다.**
> `(int)` 캐스팅으로 소수점이 버려지는 것도 함께 검토해야 합니다.

## 2-5. 🟡 승인/실패/취소 URL이 하드코딩되어 있다

```java
parameters.put("approval_url", "https://gaebang.site/api/payment/redirect/success"
        + "?partner_order_id=" + partnerOrderId);
parameters.put("fail_url",   "https://gaebang.site/api/payment/redirect/fail");
parameters.put("cancel_url", "https://gaebang.site/api/payment/redirect/cancel");
```

**로컬에서 결제를 테스트할 수 없습니다.** 카카오페이가 항상 운영 서버로 리다이렉트합니다.

```yaml
# application-dev.yml
payment:
  base-url: http://localhost:8080
# application-prod.yml
payment:
  base-url: https://gaebang.site
```

```java
@Value("${payment.base-url}")
private String baseUrl;

parameters.put("approval_url", baseUrl + "/api/payment/redirect/success?partner_order_id=" + partnerOrderId);
```

**`PaymentRedirectController`의 프론트 리다이렉트 URL도 같습니다.**

```java
return "redirect:https://www.gaebang.site/#/payment/success";      // 하드코딩
```

`OAuth2LoginSuccessHandler`와 같은 문제입니다.
→ [global 6-5](../global.md#6-5-oauth2loginsuccesshandler--소셜-로그인-성공-후)

---

# 3. `paymentApproveByPgToken` — 승인 단계

## 3-1. 🟢 멱등성 처리

```java
// 1. 먼저 결제 조회 (상태 무관)
Payment payment = paymentRepository.findByPartnerOrderId(partnerOrderId)
        .orElseThrow(() -> new PaymentNotFoundException());

// 2. 멱등성 처리: 이미 성공한 경우 기존 결과 반환
if (payment.getStatus() == PaymentStatus.SUCCESS) {
    log.info("이미 처리된 결제 재요청 - 회원ID: {}, TID: {}, 상태: SUCCESS, 주문ID: {}", ...);
    return createSuccessResponseFromPayment(payment);          // ★ 다시 승인하지 않는다
}

// 3. READY가 아닌 상태면 예외 발생
if (payment.getStatus() != PaymentStatus.READY) {
    throw new PaymentAlreadyProcessedException();
}
```

**멱등성(idempotency)이란**: 같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같은 성질입니다.

**결제에서 왜 중요한가**

```
승인 URL은 사용자 브라우저가 GET으로 접근합니다.
  → 사용자가 새로고침(F5)을 누르면?
  → 브라우저 뒤로가기 후 다시 앞으로 가면?
  → 카카오페이가 네트워크 오류로 재시도하면?
```

**멱등성이 없으면 같은 결제가 두 번 승인되고 포인트가 두 번 적립됩니다.**

```
[멱등성 없음]  새로고침 → 승인 API 재호출 → 포인트 또 적립 ❌
[이 프로젝트]  새로고침 → SUCCESS 감지 → 기존 결과 반환 ✅
```

**`GET`으로 상태를 바꾸는 엔드포인트에는 멱등성이 필수입니다.**

## 3-2. 🟢 비관적 락 (`PESSIMISTIC_WRITE`)

```java
// PaymentRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.partnerOrderId = :partnerOrderId AND p.status = :status")
Optional<Payment> findByPartnerOrderIdAndStatusWithLock(@Param("partnerOrderId") String partnerOrderId,
                                                        @Param("status") PaymentStatus status);
```

```java
// 4. READY 상태 결제에 대해서만 락 획득 후 승인 진행
Payment lockedPayment = paymentRepository
        .findByPartnerOrderIdAndStatusWithLock(partnerOrderId, PaymentStatus.READY)
        .orElseThrow(() -> new PaymentAlreadyProcessedException());   // 락 획득 실패시 이미 처리됨
```

**`@Lock(LockModeType.PESSIMISTIC_WRITE)`는 `SELECT ... FOR UPDATE`를 실행합니다.**

```sql
SELECT * FROM payment WHERE partner_order_id = ? AND status = 'READY' FOR UPDATE
```

**이 프로젝트에서 비관적 락을 쓰는 유일한 곳입니다.**

### 낙관적 락 vs 비관적 락

| | 낙관적 (Optimistic) | 비관적 (Pessimistic) |
|---|---|---|
| 방식 | 충돌을 **나중에** 감지 (`@Version`, 유니크 제약) | 충돌을 **미리** 차단 (행 잠금) |
| 구현 | 버전 비교 → 실패 시 재시도 | `SELECT ... FOR UPDATE` |
| 유리한 상황 | 충돌이 **드묾** | 충돌이 **잦거나 재시도가 위험함** |
| 대가 | 재시도 로직 필요 | 대기·데드락 위험 |
| 이 프로젝트 | `Point`(version 유니크), `Attendance`, `InterviewAnswer` | `Payment` |

**결제에 비관적 락이 맞는 이유**

1. **재시도가 위험합니다.** 포인트는 실패하면 다시 시도하면 되지만,
   결제는 **잘못하면 이중 승인**입니다. "일단 해보고 충돌하면 재시도"가 부적절합니다.
2. **금전이 걸려 있어 순서 보장이 필요합니다.**

**`AND status = :status`를 조건에 넣은 것도 영리합니다.**

```java
.orElseThrow(() -> new PaymentAlreadyProcessedException());   // 락 획득 실패시 이미 처리됨
```

락을 기다린 뒤 다시 조회했을 때 **`READY`가 아니면 결과가 비어 있습니다.**
즉 "다른 스레드가 먼저 승인했다"를 자연스럽게 감지합니다.

```
      요청 A (승인)                     요청 B (새로고침)
t1  FOR UPDATE로 락 획득 (READY)
t2                                  FOR UPDATE 시도 → 대기...
t3  카카오페이 승인 → status=SUCCESS
t4  커밋 → 락 해제
t5                                  락 획득 → 하지만 status='READY' 조건 불일치
                                    → Optional.empty()
                                    → PaymentAlreadyProcessedException ✅
```

**이중 승인이 원리적으로 막힙니다.**

## 3-3. 🔴 그런데 락을 잡은 채로 외부 API를 호출한다

**이 도메인의 가장 큰 문제입니다.**

```java
@Transactional
public PaymentApproveResponseDto paymentApproveByPgToken(String pgToken, String partnerOrderId) {
    ...
    Payment lockedPayment = paymentRepository
            .findByPartnerOrderIdAndStatusWithLock(partnerOrderId, PaymentStatus.READY)   // ★ 락 획득
            .orElseThrow(...);
    ...
    try {
        // 카카오페이 외부 API 호출
        PaymentApproveResponseDto response = restTemplate.postForObject(
                paymentProperties.getApproveUrl(), requestEntity, PaymentApproveResponseDto.class);
        //  ↑ ★ 락을 잡은 상태로 외부 HTTP 호출
        ...
    }
    // 트랜잭션 커밋 시점에 락 해제
}
```

**행 잠금(`FOR UPDATE`)이 트랜잭션 끝까지 유지됩니다.**
그 사이에 카카오페이 API를 호출하므로 **락 보유 시간 = API 응답 시간**입니다.

### 왜 심각한가 — `RestTemplate`에 타임아웃이 없다

```java
// global/config/HttpClientConfig.java
@Bean
public RestTemplate restTemplate() {  // Payment 도메인에서 사용
    return new RestTemplate();         // ⚠️ 타임아웃 미설정 → 무한 대기
}
```

→ [llm.md 3장](llm.md#3--타임아웃-없는-resttemplate)에서 같은 문제를 다뤘습니다.

```
카카오페이가 응답하지 않으면
  → RestTemplate이 영원히 대기
  → 트랜잭션이 끝나지 않음
  → 행 잠금이 무한히 유지됨
  → DB 커넥션도 무한히 점유됨
```

**그리고 InnoDB의 `innodb_lock_wait_timeout`(기본 50초)에 걸린 다른 요청들이
`LockAcquisitionException`으로 실패합니다.**

### 해결 — 락 구간에서 외부 호출을 빼낸다

**결제는 "외부 승인"과 "내부 상태 변경"을 원자적으로 묶을 수 없습니다**
(외부 시스템은 우리 트랜잭션에 참여하지 않으므로). 그래서 이런 구조가 정석입니다.

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTxService txService;
    private final RestTemplate restTemplate;

    /** 트랜잭션 없음 */
    public PaymentApproveResponseDto approve(String pgToken, String partnerOrderId) {

        // ── 트랜잭션 1: 상태를 APPROVING으로 선점 (짧은 락) ──
        PaymentSnapshot snapshot = txService.claimForApproval(partnerOrderId);
        if (snapshot.alreadySucceeded()) {
            return snapshot.toSuccessResponse();          // 멱등성
        }

        // ── 트랜잭션 없음: 외부 API (김) ──
        PaymentApproveResponseDto response;
        try {
            response = restTemplate.postForObject(approveUrl, requestEntity,
                                                  PaymentApproveResponseDto.class);
        } catch (RestClientException e) {
            txService.markFailed(snapshot.paymentId(), "카카오페이 승인 실패");
            throw new PaymentExternalApiException("결제 승인 중 오류가 발생했습니다");
        }

        // ── 트랜잭션 2: 금액 검증 + 상태 확정 + 포인트 (짧은 락) ──
        return txService.finalizeApproval(snapshot.paymentId(), response);
    }
}
```

**`APPROVING` 상태를 추가하는 것이 핵심입니다.**

```
READY ──(락 짧게)──► APPROVING ──(외부 API)──► SUCCESS / FAIL
                        │
                        └─ 이 상태의 결제는 다른 요청이 건드리지 않는다
                        └─ 스케줄러가 오래된 APPROVING을 조사해 카카오페이에 조회 후 정리
```

**락 보유 시간이 API 응답 시간 → 수 ms로 줄어듭니다.**

> **최소한 타임아웃은 반드시 설정해야 합니다.**
> ```java
> @Bean("paymentRestTemplate")
> public RestTemplate paymentRestTemplate(RestTemplateBuilder builder) {
>     return builder
>             .connectTimeout(Duration.ofSeconds(5))
>             .readTimeout(Duration.ofSeconds(15))       // 카카오페이 권장 타임아웃 확인 필요
>             .build();
> }
> ```
> 이 한 줄이 "무한 락"을 "최대 20초 락"으로 바꿉니다.

## 3-4. 🟢 금액 검증

```java
// 금액 검증
if (!lockedPayment.getAmount().equals(paymentApproveResponseDto.amount().getTotal())) {
    lockedPayment.updateStatus(PaymentStatus.FAIL);
    log.error("결제 금액 불일치 - 요청: {}, 응답: {}, 회원ID: {}, 주문ID: {}", ...);
    throw new PaymentAmountMismatchException("결제 금액이 일치하지 않습니다");
}
```

**결제 보안의 기본입니다.**

```
공격 시나리오: 클라이언트가 결제 금액을 조작
  → 100만원 상품을 1,000원으로 결제 시도
  → 서버가 검증하지 않으면 1,000원 결제로 100만원 상품 획득
```

이 프로젝트는 **서버가 DB에 저장한 금액**과 **카카오페이가 승인한 금액**을 비교합니다.
불일치하면 `FAIL`로 기록하고 예외를 던집니다.

> **`.equals()`를 쓴 것도 중요합니다.** `Integer`는 래퍼 타입이므로 `==`로 비교하면
> **-128~127 범위 밖에서는 참조 비교**가 되어 실패합니다.
> ```java
> Integer a = 10000, b = 10000;
> a == b;        // false! (캐시 범위 밖)
> a.equals(b);   // true ✅
> ```
> 이 프로젝트는 `.equals()`를 일관되게 쓰고 있습니다.

**`FAIL`로 기록하고 던지는 순서도 맞습니다.**
`@Transactional`이라 예외 발생 시 `updateStatus(FAIL)`도 롤백되지만,
**로그에 불일치 내역이 남으므로 사후 추적이 가능합니다.**

> ⚠️ 다만 **롤백되므로 DB에는 `READY`로 남습니다.**
> 그러면 스케줄러가 나중에 `TIMEOUT`으로 바꿉니다. **의도한 `FAIL`이 기록되지 않습니다.**
>
> 상태 기록을 남기려면 별도 트랜잭션이 필요합니다.
> ```java
> @Transactional(propagation = Propagation.REQUIRES_NEW)
> public void markFailed(Long paymentId, String reason) { ... }
> ```
> (단, [기초개념 4-4](../00-기초개념.md#4-4-프록시--spring-마법의-정체)에 따라 별도 빈이어야 합니다.)

## 3-5. 🟢 포인트 적립 실패를 결제 성공과 분리

```java
// 결제 성공 처리
lockedPayment.updateStatus(PaymentStatus.SUCCESS);
log.info("결제 승인 성공 - 회원ID: {}, TID: {}, 금액: {}, 주문ID: {}, AID: {}", ...);

// 포인트 적립 (재시도 로직 포함)
try {
    PrincipalDetails principalDetails = new PrincipalDetails(member);
    PointRequestDto pointRequestDto = PointRequestDto.builder()
            .amount(lockedPayment.getAmount())
            .type(PointType.SPONSORSHIP)
            .build();
    pointService.createPoint(pointRequestDto, principalDetails);
    log.info("포인트 적립 성공 - ...");

} catch (PointCreationRetryExhaustedException e) {
    // 3번 재시도 후에도 실패한 경우 - 결제는 성공 유지
    log.error("포인트 적립 완전 실패 (3회 재시도 후) - 회원ID: {}, 금액: {}, 주문ID: {}, 오류: {}", ...);
    // 고객 서비스팀에 알림 등의 후속 처리 가능
}
```

**비즈니스 판단이 정확합니다.**

> **"돈은 이미 빠져나갔다. 포인트 적립이 실패해도 결제를 취소해서는 안 된다."**

카카오페이 승인이 완료되면 **사용자 계좌에서 실제로 돈이 나갔습니다.**
포인트 적립 실패로 롤백하면 **결제 기록이 사라지고 돈만 나간 상태**가 됩니다.
그러면 사후 보상이 훨씬 어렵습니다.

**로그에 회원ID·금액·주문ID를 모두 남긴 것도 좋습니다.**
운영자가 이 로그로 수동 보정할 수 있습니다.

### ⚠️ 하지만 실제로는 롤백됩니다

`attendance` 도메인과 **같은 문제**입니다.
→ [attendance.md 3-3](attendance.md#결과--포인트-지급이-실패하면-출석도-롤백된다--의도와-정반대)

```
paymentApproveByPgToken의 @Transactional (트랜잭션 T)
  ↓
pointService.createPoint() → 내부에서 예외 발생
  → ★ 트랜잭션 T가 rollback-only로 마킹됨
  ↓
catch로 예외를 삼킴 (하지만 마킹은 지워지지 않음)
  ↓
메서드 정상 종료 → 커밋 시도 → UnexpectedRollbackException
  ↓
전체 롤백 → status = SUCCESS도 사라짐 ❌
```

**의도("결제는 성공 유지")와 정반대로, 결제 성공 기록이 사라집니다.**

그런데 `PointService`의 트랜잭션이 무효라는 점([point.md 3-1](point.md#3-1--버그--private-메서드의-transactional은-무시된다))
때문에 실제 동작은 더 복잡합니다.

```java
// PointService.createPoint — private @Transactional이라 무효
// → createPointInternal이 호출자(T)의 트랜잭션에 그냥 얹혀서 실행됨
// → 여기서 DataIntegrityViolationException이 나면 T가 rollback-only
// → 재시도 3회도 모두 같은 T에서 실패
// → PointCreationRetryExhaustedException 던짐
// → 여기 catch가 잡지만 T는 이미 rollback-only
```

**결론: 포인트 적립이 실패하면 결제 성공도 함께 사라집니다.**

**해결** — 포인트 적립을 커밋 후 이벤트로 분리합니다.

```java
// 결제 승인 트랜잭션에서
lockedPayment.updateStatus(PaymentStatus.SUCCESS);
eventPublisher.publishEvent(new PaymentSucceededEvent(
        member.getId(), lockedPayment.getAmount(), lockedPayment.getPartnerOrderId()));

// 별도 리스너 — 커밋된 뒤에만 실행
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentPointListener {

    private final PointService pointService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentSucceededEvent event) {
        try {
            pointService.createPointByMemberId(event.memberId(), PointType.SPONSORSHIP, event.amount());
        } catch (Exception e) {
            log.error("결제 포인트 적립 실패 - 보정 필요. 회원ID: {}, 금액: {}, 주문ID: {}",
                      event.memberId(), event.amount(), event.orderId(), e);
        }
    }
}
```

**이러면 의도가 실제로 지켜집니다.**
- 결제 승인이 커밋된 **뒤에** 포인트를 적립합니다
- 포인트 적립이 실패해도 결제는 이미 커밋되어 있습니다 ✅

`community` 도메인이 이미 이 패턴을 씁니다.
→ [community.md 1장](community.md#1-전체-흐름--게시글-하나가-등록되면)

## 3-6. 🟡 `new PrincipalDetails(member)`를 직접 만든다

```java
PrincipalDetails principalDetails = new PrincipalDetails(member);
pointService.createPoint(pointRequestDto, principalDetails);
```

**승인은 카카오페이 리다이렉트로 들어오므로 로그인 세션이 없습니다.**
그래서 `PrincipalDetails`를 손으로 만들어 `PointService`에 넘깁니다.

**동작하지만 설계 냄새입니다.**

`PointService.createPoint`가 `PrincipalDetails`(**인증 객체**)를 받는 것이 문제입니다.
포인트 적립에 필요한 것은 **`memberId`뿐**입니다.

```java
// 현재 — 웹 인증 객체를 서비스가 요구한다
public PointResponseDto createPoint(PointRequestDto dto, PrincipalDetails principalDetails)

// 개선 — 필요한 값만 받는다
public PointResponseDto createPoint(PointRequestDto dto, Long memberId)
```

**그러면 배치·스케줄러·이벤트 리스너에서도 자연스럽게 쓸 수 있습니다.**
`PrincipalDetails`를 가짜로 만드는 코드가 사라집니다.

> **일반 원칙: 서비스 계층은 웹 계층 타입(`PrincipalDetails`, `HttpServletRequest`,
> `ResponseDTO`, `Pageable`은 경계)에 의존하지 않는 것이 좋습니다.**
> → [member.md 3-8](member.md#3-8-서비스가-responsedto를-반환하는-문제)의 반대 방향 위반입니다.

---

# 4. `PaymentSchedulerService` — 타임아웃 정리

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentSchedulerService {

    private final PaymentRepository paymentRepository;
    private static final int PAYMENT_TIMEOUT_MINUTES = 3;         // 3분 타임아웃

    @Scheduled(fixedRate = 3600000)                               // ★ 1시간마다
    public void processExpiredPayments() {
        LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(PAYMENT_TIMEOUT_MINUTES);
        List<Payment> expiredPayments = paymentRepository.findExpiredPayments(
                PaymentStatus.READY, expiredTime);

        if (!expiredPayments.isEmpty()) {
            expiredPayments.forEach(payment -> {
                payment.updateStatus(PaymentStatus.TIMEOUT);       // ★ 더티 체킹으로 UPDATE
                log.info("결제 타임아웃 처리 완료 - TID: {}, Member ID: {}, Amount: {}",
                        payment.getTid(), payment.getMember().getId(), payment.getAmount());
            });
            log.info("총 {}건의 결제 타임아웃 처리 완료", expiredPayments.size());
        }
    }
}
```

## 4-1. 🟢 더티 체킹을 올바르게 활용한다

```java
payment.updateStatus(PaymentStatus.TIMEOUT);
// save() 호출이 없다 — 그런데 UPDATE가 실행된다
```

**`@Transactional` 안에서 조회한 엔티티는 영속 상태**이므로
필드를 바꾸면 커밋 시점에 UPDATE가 자동 생성됩니다.
→ [기초개념 6-4](../00-기초개념.md#6-4-더티-체킹--save-없이-update가-되는-이유)

`aibot`이 같은 상황에서 `save()`를 6번 호출한 것과 대비됩니다.
→ [aibot.md 4장](aibot.md#4--save를-5번-호출한다--더티-체킹을-모르는-코드)

**이 프로젝트에서 더티 체킹을 가장 정확하게 쓴 코드입니다.**

## 4-2. 🔴 타임아웃 3분인데 1시간마다 검사한다

```java
private static final int PAYMENT_TIMEOUT_MINUTES = 3;      // 3분 타임아웃
@Scheduled(fixedRate = 3600000)                             // 1시간(3,600,000ms)마다
```

**"3분 지나면 타임아웃"인데 검사는 1시간에 한 번입니다.**

```
10:00:00  결제 시작 (READY)
10:03:00  타임아웃 기준 도달
10:59:00  스케줄러 실행 → 이제야 TIMEOUT으로 변경

→ 최대 57분간 READY 상태로 남습니다.
```

**실질적 문제**: `paymentReady`가 기존 `READY` 결제를 찾아 재활용합니다.

```java
Optional<Payment> existingPayment = paymentRepository
        .findByMemberIdAndStatus(member.getId(), PaymentStatus.READY);

if (payment.getAmount().equals(dto.amount())) {
    return PaymentReadyResponseDto.fromEntity(payment);      // ★ 만료된 URL을 반환!
}
```

**만료된 결제 URL을 사용자에게 다시 줍니다.**
카카오페이의 결제 페이지는 유효 시간이 지나면 동작하지 않으므로
**사용자가 결제를 할 수 없습니다.** 그리고 1시간 동안 계속 그 상태입니다.

**해결 방향 3가지**

```java
// ① 검사 주기를 타임아웃에 맞춘다
@Scheduled(fixedDelay = 60_000)                             // 1분마다
private static final int PAYMENT_TIMEOUT_MINUTES = 3;

// ② 재활용 조건에 시간을 넣는다 (더 확실)
if (payment.getAmount().equals(dto.amount())
        && payment.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(3))) {
    return PaymentReadyResponseDto.fromEntity(payment);
}

// ③ 조회 쿼리에서 만료된 것을 제외한다
@Query("SELECT p FROM Payment p WHERE p.member.id = :memberId AND p.status = 'READY' " +
       "AND p.createdAt > :validAfter ORDER BY p.createdAt DESC")
Optional<Payment> findValidReadyPayment(@Param("memberId") Long memberId,
                                        @Param("validAfter") LocalDateTime validAfter);
```

**②나 ③이 근본적입니다.** 스케줄러 주기에 의존하지 않고 조회 시점에 판단하기 때문입니다.

## 4-3. 🟠 페이징이 없고 `fixedRate`다

```java
List<Payment> expiredPayments = paymentRepository.findExpiredPayments(READY, expiredTime);
```

**만료된 결제를 전부 메모리에 올립니다.**
1시간 동안 쌓인 것이 수천 건이면 그만큼 엔티티가 생성됩니다.

그리고 `payment.getMember().getId()`가 **LAZY 프록시를 초기화**합니다.

```java
log.info("결제 타임아웃 처리 완료 - TID: {}, Member ID: {}, Amount: {}",
        payment.getTid(), payment.getMember().getId(), payment.getAmount());
//                       ↑ member_id는 FK라 추가 쿼리가 나가지 않는다 (운 좋게)
```

`getId()`는 FK 값이므로 프록시가 이미 알고 있어 **추가 SELECT가 없습니다.**
→ [point.md 4-3](point.md#4-3-getcurrentpoint와-getallpoint에-트랜잭션이-없다)와 같은 이유로 우연히 안전합니다.

**하지만 닉네임 등을 로그에 추가하면 즉시 N+1이 됩니다.**

**벌크 업데이트가 훨씬 적합합니다.**

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE Payment p SET p.status = 'TIMEOUT' WHERE p.status = 'READY' AND p.createdAt < :expiredTime")
int markExpiredAsTimeout(@Param("expiredTime") LocalDateTime expiredTime);
```

`newsData` 도메인이 이 방식을 씁니다.
→ [newsData.md 2-1](newsData.md#2-1--modifying--벌크-update)

**단, 벌크 업데이트는 개별 로그를 남길 수 없습니다.**
감사 로그가 중요하면 현재 방식 + 페이징이 맞습니다.

**`fixedRate` vs `fixedDelay`**
→ [aibot.md 5-2](aibot.md#5-2--fixedrate-vs-fixeddelay)

작업이 1시간을 넘기면 다음 실행이 겹칩니다. `fixedDelay`가 안전합니다.

---

# 5. 컨트롤러

## 5-1. 🔴 `GET`으로 상태를 바꾼다

```java
@GetMapping("/cancel")
public ResponseEntity<ResponseDTO<String>> cancel(@AuthenticationPrincipal PrincipalDetails principalDetails) {
    paymentService.cancelPayment(principalDetails);          // ★ 상태 변경
    ...
}

@GetMapping("/fail")
public ResponseEntity<ResponseDTO<String>> fail(@AuthenticationPrincipal PrincipalDetails principalDetails) {
    paymentService.failPayment(principalDetails);             // ★ 상태 변경
    ...
}
```

**HTTP 규약 위반입니다.**

| 메서드 | 안전(safe)? | 멱등? | 상태 변경 |
|---|---|---|---|
| `GET` | ✅ **부수효과 없어야 함** | ✅ | ❌ 하면 안 됨 |
| `POST` | ❌ | ❌ | ✅ |
| `PUT` | ❌ | ✅ | ✅ |
| `PATCH` | ❌ | ❌ | ✅ |
| `DELETE` | ❌ | ✅ | ✅ |

**`GET`이 안전해야 하는 이유 — 실제로 문제가 생깁니다.**

1. **브라우저·프록시·CDN이 `GET`을 캐시합니다.**
   두 번째 호출이 서버에 도달하지 않을 수 있습니다.
2. **브라우저가 링크를 미리 가져올(prefetch) 수 있습니다.**
   사용자가 클릭하지 않아도 결제가 취소됩니다.
3. **크롤러·봇이 링크를 따라갑니다.**
4. **사용자가 새로고침하면 반복 실행됩니다.**

```java
// 고치기
@PostMapping("/cancel")
public ResponseEntity<ResponseDTO<String>> cancel(...) { ... }

@PostMapping("/fail")
public ResponseEntity<ResponseDTO<String>> fail(...) { ... }
```

> **`PaymentRedirectController`의 `GET`은 어쩔 수 없습니다.**
> 카카오페이가 사용자 브라우저를 리다이렉트하므로 `GET`이어야 합니다.
> **그래서 멱등성 처리가 필수**입니다([3-1](#3-1--멱등성-처리)) — 실제로 잘 되어 있습니다.

## 5-2. 🟠 컨트롤러의 try/catch가 원인을 버린다

```java
@PostMapping("/ready")
public ResponseEntity<ResponseDTO<PaymentReadyResponseDto>> readyToPayment(...) {
    try {
        ...
    } catch (PaymentExternalApiException e) {
        log.error("결제 준비 중 외부 API 오류: {}", e.getMessage());
        throw e;                                             // ★ 로그만 남기고 다시 던짐
    } catch (Exception e) {
        log.error("결제 준비 중 예상치 못한 오류: {}", e.getMessage());     // ⚠️ 예외 객체 누락
        throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다");   // ⚠️ 원인 버림
    }
}
```

**문제 3가지**

### ① 스택트레이스가 사라진다

```java
log.error("결제 준비 중 예상치 못한 오류: {}", e.getMessage());
//                                          ↑ 메시지만. 예외 객체를 넘기지 않았다
```

`log.error(msg, e)`처럼 **마지막 인자로 예외를 넘겨야** 스택트레이스가 출력됩니다.
지금은 `"null"` 같은 메시지만 남고 **어디서 났는지 알 수 없습니다.**

```java
log.error("결제 준비 중 예상치 못한 오류", e);       // ✅ 스택트레이스 출력
```

### ② 원인 예외를 연결하지 않는다

```java
throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다");
//         ↑ 원본 e를 넘기지 않음 → 원인 체인이 끊김
```

```java
// ApplicationException에 cause를 받는 생성자를 추가하면
throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다", e);
```

**예외를 감쌀 때는 항상 원인(cause)을 함께 넘기세요.**
그러면 로그에 `Caused by: ...`가 붙어 근본 원인을 추적할 수 있습니다.

### ③ 첫 번째 catch는 아무 일도 하지 않는다

```java
} catch (PaymentExternalApiException e) {
    log.error("결제 준비 중 외부 API 오류: {}", e.getMessage());
    throw e;                       // 그대로 다시 던짐
}
```

**서비스에서 이미 같은 내용을 로그로 남깁니다.**

```java
// PaymentService
log.error("카카오페이 결제 준비 API 호출 실패 - 회원ID: {}, 기존 결제 유지, 오류: {}", ...);
throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다");
```

**같은 예외가 두 번 로깅됩니다.** 컨트롤러의 catch는 지워도 됩니다.

> **원칙: 예외는 처리할 수 있는 곳에서만 잡으세요.**
> 로그만 남기고 다시 던지는 catch는 **로그를 중복시키고 코드를 늘릴 뿐**입니다.
> 전역 핸들러(`GlobalExceptionRestAdvice`)가 이미 `ApplicationException`을 로깅합니다.
> → [global 3-3](../global.md#3-3-globalexceptionrestadvice--예외를-json으로)

## 5-3. 🟠 리다이렉트 컨트롤러가 모든 예외를 실패로 처리한다

```java
@GetMapping("/success")
public String handlePaymentSuccess(@RequestParam("pg_token") String pgToken,
                                   @RequestParam("partner_order_id") String partnerOrderId) {
    log.info("카카오페이 결제 성공 리디렉션 - pg_token: {}, partner_order_id: {}", pgToken, partnerOrderId);
    try {
        paymentService.paymentApproveByPgToken(pgToken, partnerOrderId);
        return "redirect:https://www.gaebang.site/#/payment/success";
    } catch (Exception e) {
        return "redirect:https://www.gaebang.site/#/payment/fail";      // ⚠️ 로그 없음
    }
}
```

**`catch`에 로그가 전혀 없습니다.**

```
결제 승인이 실패했는데 왜 실패했는지 서버에 기록이 없습니다.
  → 사용자: "결제했는데 실패라고 나와요"
  → 개발자: 조사할 단서가 없음
```

**결제는 금전이 걸린 기능이므로 실패 원인이 반드시 남아야 합니다.**

```java
} catch (Exception e) {
    log.error("결제 승인 실패 - 주문ID: {}, pgToken: {}", partnerOrderId, mask(pgToken), e);
    return "redirect:" + frontBaseUrl + "/#/payment/fail";
}
```

**`pg_token`을 로그에 그대로 남기는 것도 검토가 필요합니다.**
일회용 토큰이지만 **결제 승인 자격증명**이므로 마스킹하는 편이 안전합니다.

> 그리고 **`@Controller` + `"redirect:"`를 쓰고 있습니다.**
> `@RestController`가 아니므로 반환 문자열이 뷰 이름으로 해석되고,
> `redirect:` 접두사가 302 리다이렉트를 만듭니다. **올바른 사용입니다.**
>
> `application.yml`의 `spring.web.resources.add-mappings: false`는
> 정적 리소스 매핑만 끄므로 리다이렉트에는 영향이 없습니다.
> → [global 3-3](../global.md#nohandlerfoundexception이-동작하려면-설정이-필요합니다)

## 5-4. 🟢 시큐리티 설정이 올바르다

```java
// SpringSecurityConfig
.requestMatchers(new AntPathRequestMatcher("/api/payment/redirect/**")).permitAll()
.requestMatchers(new AntPathRequestMatcher("/payment/redirect/success")).permitAll()
```

**카카오페이가 리다이렉트하는 경로이므로 `permitAll`이 필수입니다.**
사용자의 JWT가 그 요청에 실려 오지 않습니다.

**그리고 `PaymentRedirectController`는 `principalDetails`를 쓰지 않습니다.**

```java
public String handlePaymentSuccess(@RequestParam("pg_token") String pgToken,
                                   @RequestParam("partner_order_id") String partnerOrderId) {
```

**대신 `partnerOrderId`로 결제를 찾아 그 결제의 `member`를 사용합니다.**

```java
Payment payment = paymentRepository.findByPartnerOrderId(partnerOrderId).orElseThrow(...);
Member member = payment.getMember();
```

**인증 없이도 동작하도록 정확히 설계되어 있습니다.**
`/api/interview/**`, `/api/boards/**`처럼 `permitAll`인데 `principalDetails`를 쓰는
NPE 문제가 **여기서는 없습니다.**
→ [global 6-1](../global.md#③-url별-권한--️-이-프로젝트-최대의-문제)

`/api/payment/ready`, `/cancel`, `/fail`, `/status`는 `permitAll` 목록에 없어
**인증이 필요합니다.** 올바릅니다.

> **`partnerOrderId`만으로 승인이 진행되는 것이 위험하지 않은가?**
>
> `pg_token`이 함께 필요하고, 그것은 **카카오페이가 정상 결제 흐름에서만 발급**합니다.
> 공격자가 `partnerOrderId`를 알아내도 유효한 `pg_token`이 없으면
> 카카오페이 `/approve` API가 거부합니다.
>
> 그리고 `partnerOrderId`는 `UUID` 기반이라 추측이 불가능합니다.
> **설계가 안전합니다.**

---

# 6. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | 비관적 락을 잡은 채 외부 API 호출 + `RestTemplate` 타임아웃 무한 | [3-3](#3-3--그런데-락을-잡은-채로-외부-api를-호출한다) |
| 🔴 2 | 포인트 적립 실패 시 결제 성공 기록도 롤백 (의도와 반대) | [3-5](#️-하지만-실제로는-롤백됩니다) |
| 🔴 3 | 타임아웃 3분 / 검사 1시간 → 만료된 결제 URL을 재사용 | [4-2](#4-2--타임아웃-3분인데-1시간마다-검사한다) |
| 🟠 4 | `GET /cancel`, `GET /fail`이 상태를 변경 | [5-1](#5-1--get으로-상태를-바꾼다) |
| 🟠 5 | 리다이렉트 실패 경로에 로그가 없음 | [5-3](#5-3--리다이렉트-컨트롤러가-모든-예외를-실패로-처리한다) |
| 🟠 6 | `findByMemberIdAndStatus`가 `Optional` — READY 2건이면 500 영구화 | [2-3](#2-3--findbymemberidandstatus가-optional을-반환한다) |
| 🟠 7 | 금액 불일치 시 `FAIL` 기록이 롤백되어 남지 않음 | [3-4](#3-4--금액-검증) |
| 🟠 8 | 컨트롤러 catch가 스택트레이스·원인 예외를 버림 + 중복 로깅 | [5-2](#5-2--컨트롤러의-trycatch가-원인을-버린다) |
| 🟠 9 | 스케줄러에 페이징 없음 + `fixedRate` | [4-3](#4-3--페이징이-없고-fixedrate다) |
| 🟡 10 | `partnerOrderId`에 유니크 제약 없음 | [1-2](#1-2--partnerorderid에는-유니크-제약이-없다) |
| 🟡 11 | VAT 계산식 (`× 0.1` vs `/ 11`) 검토 필요 | [2-4](#2-4--vat-계산) |
| 🟡 12 | 승인/실패/취소 URL 하드코딩 → 로컬 테스트 불가 | [2-5](#2-5--승인실패취소-url이-하드코딩되어-있다) |
| 🟡 13 | `PointService`가 `PrincipalDetails`를 요구 → 가짜 객체 생성 | [3-6](#3-6--new-principaldetailsmember를-직접-만든다) |
| 🟡 14 | `pg_token`을 로그에 그대로 기록 | [5-3](#5-3--리다이렉트-컨트롤러가-모든-예외를-실패로-처리한다) |
| 🟢 15 | 리다이렉트 URL 5개를 영구 저장 → Redis TTL 검토 | [1-4](#1-4--리다이렉트-url-5개를-저장한다) |
| 🟢 16 | `updateStatus(status)` 하나로 모든 전이 → 의미 있는 메서드로 분리 | [1-3](#1-3-paymentstatus--5단-상태) |
| 🟢 17 | `@NoArgsConstructor`가 `public` | [1장](#1-payment-엔티티) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **멱등성 처리 (`SUCCESS`면 기존 결과 반환)** | `GET` 리다이렉트 승인에 필수. 이중 승인·이중 적립 차단 |
| **비관적 락 (`PESSIMISTIC_WRITE`)** | 결제는 재시도가 위험하므로 낙관적 락보다 적합. 프로젝트 유일 |
| **락 쿼리에 `status = READY` 조건 포함** | 락 획득 후 상태가 바뀌었으면 자연히 빈 결과 → 경쟁 감지 |
| **금액 검증 (DB 금액 vs 카카오페이 응답)** | 결제 금액 조작 공격 차단. 결제 보안의 기본 |
| `Integer.equals()` 비교 | `==` 참조 비교 함정 회피 |
| **외부 API 성공 후에 기존 결제 정리** | 실패 시 사용자가 기존 결제로 계속 진행 가능. 주석에 이유 명시 |
| **기존 READY 결제 재활용** | 더블 클릭·뒤로가기로 미완결 결제가 쌓이는 것을 방지 |
| **포인트 실패를 결제 성공과 분리하려는 의도** | "돈은 이미 나갔다"는 비즈니스 판단이 정확 (구현은 미완) |
| **보정용 로그 (회원ID·금액·주문ID)** | 운영자가 수동 보정할 수 있는 정보를 전부 남김 |
| `TIMEOUT`을 `FAIL`과 구분 | "이탈"과 "실패"는 분석상 다른 사건 |
| **더티 체킹 활용 (스케줄러)** | `save()` 없이 상태 변경. 프로젝트에서 가장 정확한 사용 |
| **도메인 예외 4종 분리** | `NotFound`/`AlreadyProcessed`/`AmountMismatch`/`ExternalApi` |
| `ErrorCode`에 적절한 HTTP 상태 | `CONFLICT(409)`, `NOT_FOUND(404)` 등 의미에 맞게 |
| **`tid`에 `unique = true`** | 카카오페이 거래 ID 중복 방지 |
| **리다이렉트 경로가 인증에 의존하지 않음** | `partnerOrderId`로 결제를 찾아 `member`를 얻음. NPE 문제 없음 |
| `partnerOrderId`를 UUID 기반으로 | 순차 ID가 아니라 추측 불가 |
| `@Controller` + `redirect:` 사용 | 리다이렉트에 맞는 정확한 선택 |
| `@Min`/`@Max` 금액 검증 | 1,000원 ~ 1,000,000원 범위 제한 |

**핵심 평가**: **동시성·정합성 설계가 이 프로젝트에서 가장 성숙합니다.**

멱등성, 비관적 락, 금액 검증, 상태 기계, 타임아웃 정리, 보정용 로그 —
**결제 시스템에서 필요한 요소를 거의 다 알고 있습니다.**
주석에 "왜 이 순서인가"를 적어둔 것도 좋습니다.

문제는 **모두 "경계"에 있습니다.**

1. **트랜잭션 경계** — 락과 외부 API가 같은 트랜잭션에 있습니다.
   `RestTemplate` 타임아웃이 무한이라 **행 잠금이 무한히 유지될 수 있습니다.**
2. **트랜잭션 참여 경계** — 포인트 적립을 `catch`로 감싸 분리하려 했지만,
   같은 트랜잭션이라 rollback-only가 되어 **의도와 반대로 동작합니다.**
3. **HTTP 의미론 경계** — `GET`이 상태를 바꿉니다.

가장 먼저 할 일은 **`RestTemplate`에 타임아웃을 주는 것**입니다.
한 줄이면 "무한 락"이 "최대 20초 락"이 됩니다.
그다음이 **포인트 적립을 `@TransactionalEventListener(AFTER_COMMIT)`으로 옮기는 것**입니다.

---

## 다음 문서

- [point.md](point.md) — 포인트 적립의 구조적 문제
- [attendance.md](attendance.md) — 같은 "부분 성공을 만들려다 전체 롤백" 사례
- [community.md](community.md) — `@TransactionalEventListener(AFTER_COMMIT)`의 올바른 사용례
- [../global.md](../global.md) — `RestTemplate` 설정과 시큐리티 경로
