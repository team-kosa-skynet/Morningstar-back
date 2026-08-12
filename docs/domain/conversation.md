# conversation — AI 대화방 (ChatGPT식 채팅 이력)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [global.md](../global.md)
>
> 파일 20개. **ChatGPT의 사이드바 + 대화 이력**을 구현한 도메인입니다.
> `question` 도메인(LLM 호출)이 여기에 질문/답변을 저장합니다.
> 주석이 이 프로젝트에서 가장 잘 달린 도메인이고, **동시성과 계층 규약에서 실수**가 있습니다.

---

## 파일 지도

```
domain/conversation/
├── controller/ConversationController.java      /api/conversations (5개)
├── dto/
│   ├── request/  CreateConversationRequestDto, UpdateConversationTitleRequestDto,
│   │             AddQuestionRequestDto, AddAnswerRequestDto,
│   │             ConversationSearchRequestDto, FileAttachmentDto
│   └── response/ CreateConversationResponseDto, ConversationListResponseDto,
│                 ConversationSummaryDto, ConversationDetailResponseDto,
│                 ConversationHistoryDto, MessageResponseDto
├── entity/
│   ├── Conversation.java           대화방 (사용자당 N개)
│   ├── ConversationMessage.java    메시지 (대화방당 N개)
│   └── MessageRole.java            USER | ASSISTANT
├── exception/ConversationNotFoundException.java
├── repository/ConversationRepository.java, ConversationMessageRepository.java
└── service/ConversationService.java
```

**역할 분담**

```
[conversation 도메인]           대화방 CRUD + 메시지 저장/조회  ← 이 문서
[question 도메인]               LLM API 호출 + SSE 스트리밍
        │
        └─ 질문 전송 시   → conversationService.addQuestion(...)
           답변 완료 시   → conversationService.addAnswer(...)
```

**즉 이 도메인은 "저장소" 역할이고, LLM은 전혀 모릅니다.** 관심사 분리가 잘 되어 있습니다.

---

# 1. 엔티티 — 1:N 양방향 관계

## 1-1. `Conversation`

```java
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(nullable = false, length = 200)          // ★ NOT NULL — 1-3 참조
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConversationMessage> messages = new ArrayList<>();

    @Column(nullable = false)
    private Boolean isActive = true;                  // soft delete 플래그

    @Builder                                          // ★ 클래스가 아니라 생성자에 붙었다
    public Conversation(String title, Member member) {
        this.title = title;
        this.member = member;
        this.isActive = true;
    }

    public void updateTitle(String title) { this.title = title; }
    public void deactivate() { this.isActive = false; }

    public void addMessage(ConversationMessage message) {
        this.messages.add(message);
        message.setConversation(this);
    }
}
```

### `@Builder`가 생성자에 붙으면 `@Builder.Default`가 필요 없다

[community.md 2-1](community.md#2-1-builderdefault--없으면-null이-들어간다)에서
`@Builder.Default`가 없으면 컬렉션이 `null`이 된다고 했습니다. **여기서는 문제가 없습니다.**

| | `@Builder`가 **클래스**에 | `@Builder`가 **생성자**에 |
|---|---|---|
| 동작 | Lombok이 모든 필드를 대상으로 빌더 생성. **필드 초기화식을 무시** | 그 생성자를 호출하는 빌더만 생성. **필드 초기화식이 정상 실행** |
| `@Builder.Default` | **필요** | 불필요 |
| 빌더로 설정 가능한 필드 | 전부 | 생성자 파라미터만 (`title`, `member`) |

```java
Conversation c = Conversation.builder().title("제목").member(member).build();
c.getMessages();     // → 빈 ArrayList ✅ (생성자 실행 시 필드 초기화식이 동작)
c.getIsActive();     // → true ✅
```

**게다가 생성자 파라미터를 `title`, `member`로 제한했으므로
`isActive`나 `messages`를 외부에서 임의로 지정할 수 없습니다.** 더 안전한 설계입니다.

> `Board`(클래스 레벨 `@Builder` + `@Builder.Default`)와 `Conversation`(생성자 레벨 `@Builder`)이
> **서로 다른 방식**을 쓰고 있습니다. 후자가 더 좋으니 통일하면 좋겠습니다.

### 🟠 `cascade = CascadeType.ALL, orphanRemoval = true`가 실제로는 쓰이지 않는다

```java
@OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
private List<ConversationMessage> messages = new ArrayList<>();
```

**`cascade`란**: 부모 엔티티에 대한 작업을 자식에게 전파합니다.

| 옵션 | 전파되는 작업 |
|---|---|
| `PERSIST` | `save()` → 자식도 함께 INSERT |
| `MERGE` | `merge()` → 자식도 함께 |
| `REMOVE` | `delete()` → 자식도 함께 DELETE |
| `ALL` | 전부 |
| `orphanRemoval = true` | **컬렉션에서 빼면** 자식이 DELETE (`ALL`과 별개) |

그런데 이 프로젝트는 **`addMessage()`를 한 번도 호출하지 않습니다.**

```java
// ConversationService.addQuestion — 컬렉션을 쓰지 않고 리포지토리로 직접 저장
ConversationMessage message = requestDto.toEntity(conversation, ..., nextOrder, attachmentsJson);
messageRepository.save(message);
```

즉 `cascade = PERSIST`가 개입할 기회가 없습니다.
`deleteConversation`도 `conversation.deactivate()`(soft delete)이므로 `REMOVE`도 발동하지 않습니다.

**쓰이지 않는 `cascade = ALL`은 위험합니다.**
누군가 나중에 `conversationRepository.delete(conversation)`을 호출하면
**메시지가 전부 물리 삭제**됩니다. 그런데 이 도메인은 soft delete 정책이므로 의도와 모순됩니다.

```java
// 실제 사용에 맞춘 형태 — cascade 제거, 읽기 전용 컬렉션으로
@OneToMany(mappedBy = "conversation")
private List<ConversationMessage> messages = new ArrayList<>();

// 또는 컬렉션 자체를 지운다 (어차피 messageRepository로만 조회하므로)
```

### `Boolean isActive` vs `boolean isActive`

```java
@Column(nullable = false)
private Boolean isActive = true;         // 래퍼 타입
```

`Boolean`(래퍼)은 `null`이 될 수 있고, `boolean`(원시)은 될 수 없습니다.
`nullable = false`인 컬럼이면 **원시 타입 `boolean`이 더 안전합니다.**

```java
@Column(nullable = false)
private boolean active = true;           // null 불가능. getter는 isActive()
```

> 필드명을 `isActive`로 하면 Lombok이 `getIsActive()`를 만들고,
> Jackson이 JSON 키를 `isActive`로 씁니다. `active`로 하면 `isActive()` / `"active"`가 됩니다.
> **`boolean` 필드에 `is` 접두사를 붙이지 않는 것이 자바 관례**입니다.

## 1-2. `ConversationMessage`

```java
@Entity
@Table(name = "conversation_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationMessage extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MessageRole role;                         // USER | ASSISTANT

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;                            // LLM 답변은 길 수 있으므로 TEXT

    @Column(length = 50)
    private String aiModel;                            // "gpt-4o-mini" 등 (답변만)

    @Column(nullable = false)
    private Integer messageOrder;                      // ★ 1-4 참조

    @Column(columnDefinition = "JSON")                 // ★ 1-5 참조
    private String attachments;

    public void setConversation(Conversation conversation) { this.conversation = conversation; }
}
```

### `MessageRole` — LLM API 규격과 맞춘 enum

```java
public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String value;
    public String getValue() { return value; }        // LLM API에 넘길 문자열
}
```

OpenAI·Anthropic·Gemini의 채팅 API는 모두 `{"role": "user", "content": "..."}` 형식을 씁니다.
**DB에는 `USER`(자바 상수명)를 저장하고, API에는 `user`(소문자)를 보냅니다.**
enum이 그 변환을 담당합니다. 깔끔한 처리입니다.

## 1-3. 🔴 제목이 `null`이면 500 에러가 난다

```java
// Conversation 엔티티
@Column(nullable = false, length = 200)
private String title;
```

```java
// CreateConversationRequestDto — 주석에 "선택사항이므로 @NotBlank 없음"이라고 명시
public record CreateConversationRequestDto(
        /**
         * 대화방 제목
         * 사용자가 직접 입력하거나, 비어있으면 첫 질문으로 자동 생성
         * 선택사항이므로 @NotBlank 없음
         */
        @Size(max = 200, message = "제목은 200자 이하로 입력해주세요")
        String title
) {}
```

```java
// ConversationService.createConversation — 자동 생성 로직이 없다!
Conversation conversation = Conversation.builder()
        .member(member)
        .title(requestDto.title())        // ⚠️ null이 그대로 들어간다
        .build();
conversationRepository.save(conversation);
```

**주석이 약속한 "비어있으면 첫 질문으로 자동 생성"이 구현되지 않았습니다.**

```
POST /api/conversations  { }              ← 제목 없이 "새 채팅"
  → title = null
  → INSERT INTO conversations (title, ...) VALUES (NULL, ...)
  → MySQL: Column 'title' cannot be null
  → DataIntegrityViolationException
  → GlobalExceptionRestAdvice.dbException() → 500 "서버 에러!"
```

**"새 채팅" 버튼이 동작하지 않습니다.** 프론트엔드가 항상 제목을 채워 보내야만 동작합니다.

**고치는 방법 2가지**

```java
// ① 기본 제목을 서버에서 정한다 (가장 간단)
@Transactional
public CreateConversationResponseDto createConversation(Long memberId, CreateConversationRequestDto dto) {
    Member member = memberRepository.findById(memberId).orElseThrow(UserNotFoundException::new);

    String title = (dto.title() == null || dto.title().isBlank()) ? "새 대화" : dto.title().trim();

    Conversation conversation = Conversation.builder().member(member).title(title).build();
    return CreateConversationResponseDto.from(conversationRepository.save(conversation));
}

// ② 주석의 약속대로 첫 질문에서 제목을 만든다
@Transactional
public void addQuestion(Long conversationId, Long memberId, AddQuestionRequestDto dto) {
    Conversation conversation = conversationRepository
            .findActiveConversationByIdAndMemberId(conversationId, memberId)
            .orElseThrow(ConversationNotFoundException::new);

    // 첫 질문이면 제목을 자동 생성
    if (DEFAULT_TITLE.equals(conversation.getTitle())) {
        conversation.updateTitle(summarize(dto.content(), 30));   // 앞 30자 + "..."
    }
    ...
}
```

> `ConversationMessageRepository`에 `findUserQuestionsByConversationId`가 있고
> 주석에 **"첫 번째 질문으로 대화방 제목을 자동 생성할 때 사용"** 이라고 적혀 있습니다.
> **만들어놓고 호출하지 않는 메서드**입니다. ②를 구현하려던 흔적입니다.

## 1-4. 🟠 `messageOrder`의 경쟁 조건

```java
@Query("SELECT COALESCE(MAX(cm.messageOrder), 0) + 1 FROM ConversationMessage cm " +
       "WHERE cm.conversation.conversationId = :conversationId")
Integer findNextMessageOrder(@Param("conversationId") Long conversationId);
```

```java
Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);   // 읽고
...
messageRepository.save(message);                                               // 쓴다
```

**"MAX 읽고 +1해서 INSERT"는 전형적인 경쟁 조건입니다.**

```
       요청 A (질문)                     요청 B (질문)
t1  MAX+1 = 4
t2                                   MAX+1 = 4       ← 같은 값
t3  INSERT order=4 ✅
t4                                   INSERT order=4 ✅   ← 중복!
```

**유니크 제약이 없어서 그대로 저장됩니다.**

**`COALESCE`란**: `COALESCE(a, b)`는 "a가 `NULL`이면 b"입니다.
메시지가 하나도 없으면 `MAX()`가 `NULL`을 반환하므로 `0`으로 대체해 `0 + 1 = 1`이 됩니다.
**`NULL + 1 = NULL`이 되는 것을 막는 올바른 처리입니다.**

**동시 요청이 실제로 일어나는가?** 채팅은 사용자가 한 번에 하나씩 보내므로 드뭅니다.
하지만 **질문 저장 직후 답변 저장이 이어지는 구조**라서, 여러 LLM에 동시 질문하는 UI
(이 프로젝트는 "3개 답변 중 하나 선택" 기능이 있습니다)에서는 충분히 발생합니다.

```java
// ① DB 제약으로 막고 재시도 (권장)
@Table(name = "conversation_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conv_order", columnNames = {"conversation_id", "message_order"})
})

// ② 순서를 아예 없애고 messageId(AUTO_INCREMENT)로 정렬
//    → 같은 대화방 안에서는 messageId 순서 = 삽입 순서
ORDER BY cm.messageId ASC
```

**②가 더 단순합니다.** `messageOrder`는 사실 `messageId`와 항상 같은 순서이고,
`createdAt`도 이미 있으므로 **중복 정보**입니다.

## 1-5. 🟠 첨부파일을 JSON 문자열로 수동 관리

```java
@Column(columnDefinition = "JSON")
private String attachments;      // '[{"fileName":"a.pdf","fileType":"pdf",...}]'
```

```java
// 저장 시 — 서비스가 손으로 직렬화
private String convertAttachmentsToJson(List<FileAttachmentDto> attachments) {
    if (attachments == null || attachments.isEmpty()) return null;
    try {
        return objectMapper.writeValueAsString(attachments);
    } catch (JsonProcessingException e) {
        log.error("첨부파일 JSON 변환 실패", e);
        return null;                                    // ⚠️ 실패를 삼키고 null 저장
    }
}

// 조회 시 — DTO가 손으로 역직렬화
private static List<FileAttachmentDto> parseAttachments(String attachmentsJson) {
    if (attachmentsJson == null || attachmentsJson.trim().isEmpty()) return Collections.emptyList();
    try {
        return objectMapper.readValue(attachmentsJson, new TypeReference<List<FileAttachmentDto>>() {});
    } catch (Exception e) {
        log.warn("첨부파일 JSON 파싱 실패: {}", attachmentsJson, e);
        return Collections.emptyList();
    }
}
```

**동작하지만 문제가 있습니다.**

1. **직렬화 실패를 `null`로 삼킵니다** → 첨부파일이 조용히 사라집니다
2. **`new TypeReference<List<FileAttachmentDto>>() {}`** — 제네릭 타입 소거를 우회하는 관용구입니다.
   익명 클래스를 만들어 타입 정보를 런타임에 남깁니다. 필요하지만 장황합니다.
3. **`MessageResponseDto`가 자체 `ObjectMapper`를 만듭니다**
   ```java
   private static final ObjectMapper objectMapper = new ObjectMapper();
   ```
   `AppConfig`에 이미 빈이 있는데 쓰지 않습니다. DTO는 빈이 아니라 주입받을 수 없으니
   **변환 책임을 서비스로 올리는 것이 맞습니다.**

**Hibernate 6의 표준 방법 — 어노테이션 하나로 자동 변환**

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "json")
private List<FileAttachmentDto> attachments;      // ★ 문자열이 아니라 리스트 그대로
```

Hibernate가 저장·조회 시 JSON 변환을 자동으로 처리합니다.
수동 직렬화 코드 두 곳이 사라지고, 실패가 조용히 묻히는 문제도 없어집니다.

> **또 다른 선택 — 별도 테이블**
> 첨부파일을 `message_attachments` 테이블로 정규화하면 "PDF가 첨부된 메시지 검색" 같은
> 쿼리가 가능해집니다. JSON 컬럼은 그런 조회가 어렵습니다.
> **조회 요구사항이 없다면 JSON이 간단하고 충분합니다.**

## 1-6. 🟡 `toLlmApiFormat`이 프롬프트를 오염시킨다

```java
public Map<String, Object> toLlmApiFormat() {
    Map<String, Object> apiMessage = new HashMap<>();
    apiMessage.put("role", this.role);

    // content에 메시지 순서 정보 포함하여 LLM이 대화 흐름을 더 잘 이해할 수 있도록 함
    String contentWithOrder = String.format("[메시지 %d] %s", this.messageOrder, this.content);
    apiMessage.put("content", contentWithOrder);
    apiMessage.put("attachments", this.attachments);
    return apiMessage;
}
```

**의도**: LLM에게 대화 순서를 알려주려는 것.
**실제**: LLM API는 **배열의 순서로 이미 대화 흐름을 이해합니다.**
`[메시지 3]` 같은 접두사는 불필요하고, 오히려:

- **토큰을 낭비합니다** (메시지마다 5~7 토큰 × 대화 길이)
- **모델이 그 형식을 모방할 수 있습니다** (답변에 `[메시지 4]`를 붙이는 현상)
- **`attachments` 키는 LLM API 표준이 아닙니다** — 무시되거나 400 에러의 원인이 됩니다

```java
// 표준 형식만 보내는 것이 정답
public Map<String, Object> toLlmApiFormat() {
    return Map.of("role", this.role, "content", this.content);
}
```

> 참고로 이 메서드도 **호출처가 없습니다.** `question` 도메인이 자체적으로 히스토리를 조립합니다.

---

# 2. 리포지토리 — 좋은 최적화와 나쁜 타입

## 2-1. 🟢 N+1을 의식한 요약 조회

```java
@Query("SELECT c, COUNT(m), " +
       "(SELECT m2.content FROM ConversationMessage m2 " +
       " WHERE m2.conversation.conversationId = c.conversationId " +
       " ORDER BY m2.messageOrder DESC, m2.createdAt DESC LIMIT 1) as lastMessageContent " +
       "FROM Conversation c " +
       "LEFT JOIN ConversationMessage m ON c.conversationId = m.conversation.conversationId " +
       "WHERE c.member.id = :memberId AND c.isActive = true " +
       "GROUP BY c.conversationId " +
       "ORDER BY MAX(m.createdAt) DESC NULLS LAST")
List<Object[]> findConversationSummariesByMemberId(@Param("memberId") Long memberId);
```

주석에 **"N+1 문제 해결을 위한 최적화된 쿼리"** 라고 적혀 있습니다. 정확한 인식입니다.

**한 번의 쿼리로 3가지를 가져옵니다.**

```
① 대화방 엔티티
② 각 대화방의 메시지 개수 (COUNT + GROUP BY)
③ 각 대화방의 마지막 메시지 내용 (상관 서브쿼리)
```

이걸 안 했다면 대화방 20개에 대해 `count` 20회 + `마지막 메시지` 20회 = **41회 쿼리**입니다.

**`ORDER BY MAX(m.createdAt) DESC NULLS LAST`**
"가장 최근 메시지가 있는 대화방부터, 메시지가 없는 대화방은 맨 뒤로"입니다.
ChatGPT 사이드바의 정렬과 정확히 같습니다. 세심합니다.

> `NULLS LAST`는 MySQL이 직접 지원하지 않는 SQL 표준 구문입니다.
> Hibernate 6이 `ORDER BY MAX(created_at) IS NULL, MAX(created_at) DESC`처럼 변환해줍니다.
> **JPQL이 방언 차이를 흡수하는 좋은 예**입니다.
>
> 서브쿼리의 `LIMIT 1`도 JPQL 표준이 아니라 Hibernate 6 확장입니다.
> → [point.md 2-2](point.md#2-2-query를-쓴-이유--jpql의-limit)

## 2-2. 🟠 `List<Object[]>` 반환 타입의 문제

```java
List<Object[]> conversationSummaries = conversationRepository.findConversationSummariesByMemberId(memberId);

List<ConversationSummaryDto> summaryDtos = conversationSummaries.stream()
        .map(result -> {
            Conversation conversation = (Conversation) result[0];     // ⚠️ 인덱스 + 캐스팅
            Long messageCount = (Long) result[1];
            String lastMessageContent = (String) result[2];
            ...
        })
```

**문제**

1. **컴파일러가 아무것도 검사하지 않습니다.** `result[1]`이 `Long`인지 `Integer`인지 확인할 방법이 없고,
   틀리면 **런타임에 `ClassCastException`** 이 납니다.
2. **쿼리의 `SELECT` 절 순서를 바꾸면 조용히 깨집니다.**
3. **`result[3]`처럼 범위를 넘으면 `ArrayIndexOutOfBoundsException`.**

**해결 — 생성자 프로젝션 또는 인터페이스 프로젝션**

```java
// ① 생성자 프로젝션 (community 도메인이 이미 쓰는 방식)
@Query("SELECT new com.gaebang.backend.domain.conversation.dto.response.ConversationSummaryDto(" +
       "c.conversationId, c.title, c.updatedAt, COUNT(m), " +
       "(SELECT m2.content FROM ConversationMessage m2 WHERE ... LIMIT 1)) " +
       "FROM Conversation c LEFT JOIN ... GROUP BY c.conversationId ORDER BY MAX(m.createdAt) DESC")
List<ConversationSummaryDto> findSummaries(@Param("memberId") Long memberId);

// ② 인터페이스 프로젝션 (스프링이 프록시를 만들어줌)
public interface ConversationSummaryView {
    Long getConversationId();
    String getTitle();
    Long getMessageCount();
    String getLastMessageContent();
}
```

`community` 도메인은 ①을 잘 쓰고 있는데([community.md 3-1](community.md#3-1-생성자-프로젝션이란))
여기서는 `Object[]`를 씁니다. **같은 프로젝트 안에서 방식이 갈립니다.**

## 2-3. 🟡 중복 메서드와 미사용 메서드

```java
// 완전히 같은 일을 하는 두 메서드
@Query("SELECT c FROM Conversation c WHERE c.conversationId = :conversationId " +
       "AND c.member.id = :memberId AND c.isActive = true")
Optional<Conversation> findActiveConversationByIdAndMemberId(Long conversationId, Long memberId);

Optional<Conversation> findByConversationIdAndMemberIdAndIsActiveTrue(Long conversationId, Long memberId);
// ↑ 쿼리 메서드로 자동 생성. 사용처 없음.
```

**미사용 메서드 목록**

| 메서드 | 만든 목적(주석) | 실제 |
|---|---|---|
| `findActiveConversationsByMemberIdOrderByModifiedDateDesc` | 목록 조회 | `findConversationSummariesByMemberId`로 대체됨 |
| `countActiveConversationsByMemberId` | 개수 조회 | 서비스가 `summaryDtos.size()`를 씀 |
| `findActiveConversationsByMemberIdAndTitleContaining` | 제목 검색 | **검색 API 자체가 없음** |
| `findByConversationIdAndMemberIdAndIsActiveTrue` | — | 중복 |
| `findUserQuestionsByConversationId` | 제목 자동 생성 | [1-3](#1-3--제목이-null이면-500-에러가-난다)의 미구현 기능 |
| `countMessagesByConversationId` | 대화 길이 확인 | 요약 쿼리에 포함됨 |
| `findMaxMessageOrderByConversationId` | — | `findNextMessageOrder`와 중복 |

**리포지토리 메서드 15개 중 7개가 쓰이지 않습니다.**
`ConversationSearchRequestDto`도 사용처가 없습니다(검색 API 미구현).

> 미사용 코드가 나쁜 이유: **읽는 사람이 "이게 어디서 쓰이나" 찾느라 시간을 씁니다.**
> 그리고 리팩터링할 때 "혹시 쓰이나" 걱정하며 남겨두게 됩니다.
> Git이 이력을 보관하므로 **지우는 것이 맞습니다.**

---

# 3. `ConversationService` — 계층과 트랜잭션

## 3-1. 🟠 읽기 메서드에 트랜잭션이 없다

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {                    // ⚠️ 클래스 레벨 @Transactional 없음

    @Transactional
    public CreateConversationResponseDto createConversation(...) { ... }     // ✅

    @Transactional
    public void updateConversationTitle(...) { ... }                          // ✅

    @Transactional
    public void deleteConversation(...) { ... }                               // ✅

    public ConversationListResponseDto getConversationList(...) { ... }       // ⚠️ 없음
    public ConversationDetailResponseDto getConversationDetail(...) { ... }   // ⚠️ 없음
    public ConversationHistoryDto getConversationHistory(...) { ... }         // ⚠️ 없음

    @Transactional
    public void addQuestion(...) { ... }                                      // ✅
    @Transactional
    public void addAnswer(...) { ... }                                        // ✅
}
```

**쓰기 메서드에는 정확히 붙였습니다.** 읽기 메서드 3개가 빠져 있습니다.

**왜 문제인가**: `getConversationList`는 `Conversation` **엔티티**를 꺼냅니다.

```java
Conversation conversation = (Conversation) result[0];
return ConversationSummaryDto.from(conversation, messageCount, lastMessagePreview);
```

`ConversationSummaryDto.from`이 `conversation.getMember()`를 건드리는 순간
**영속성 컨텍스트가 없어서 `LazyInitializationException`** 이 납니다.
지금은 안 건드리므로 우연히 동작하지만, **언제든 터질 수 있는 상태**입니다.

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)          // ★ 기본을 읽기 전용으로
public class ConversationService {

    @Transactional                        // 쓰기 메서드만 덮어쓴다
    public CreateConversationResponseDto createConversation(...) { ... }
    ...
}
```

이것이 **권장 패턴**입니다. → [기초개념 7-3](../00-기초개념.md#7-3-readonly--true--단순-최적화가-아니다)

## 3-2. 🟠 예외 규약을 두 곳에서 어긴다

```java
// createConversation
Member member = memberRepository.findById(memberId)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

// getConversationHistory
conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
        .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));
```

같은 클래스의 다른 메서드는 도메인 예외를 잘 씁니다.

```java
.orElseThrow(() -> new ConversationNotFoundException());     // ✅ 나머지 4곳
```

`IllegalArgumentException`은 `RuntimeException` 포괄 핸들러에 걸려
**500 "서버 에러!"** 가 되고 원래 메시지도 사라집니다.
→ [global 3-3](../global.md#️-문제--runtimeexception-포괄-핸들러가-400을-500으로-만든다)

```java
// 고치기 — 이미 있는 예외를 쓰면 됩니다
.orElseThrow(UserNotFoundException::new);            // member 도메인의 것
.orElseThrow(ConversationNotFoundException::new);     // 이 도메인의 것
```

> `ConversationNotFoundException`은 메시지를 받는 생성자도 갖고 있습니다.
> ```java
> public ConversationNotFoundException(String message) { super(ErrorCode.CONVERSATION_NOT_FOUND, message); }
> ```
> 즉 `new ConversationNotFoundException("접근 권한이 없습니다")`가 가능한데 쓰지 않았습니다.

## 3-3. 🟢 잘 만든 것 — 소유권 검증을 쿼리에 넣었다

```java
@Query("SELECT c FROM Conversation c WHERE c.conversationId = :conversationId " +
       "AND c.member.id = :memberId AND c.isActive = true")
Optional<Conversation> findActiveConversationByIdAndMemberId(Long conversationId, Long memberId);
```

**`memberId` 조건이 쿼리에 들어 있습니다.**

이것이 [member.md 7장](member.md#7--치명적-누구나-남의-비밀번호를-바꿀-수-있다)의
IDOR 취약점을 막는 정석적인 방법입니다.

```java
// ❌ 취약한 패턴 — 남의 대화방도 조회된다
Conversation c = conversationRepository.findById(conversationId).orElseThrow(...);

// ✅ 안전한 패턴 — 내 것이 아니면 아예 결과가 없다
Conversation c = conversationRepository
        .findActiveConversationByIdAndMemberId(conversationId, memberId).orElseThrow(...);
```

조회·수정·삭제·메시지 추가 **모든 경로**에서 이 메서드를 씁니다.
**남의 대화 이력을 볼 수 없습니다.** 잘 지켜진 부분입니다.

> 부수 효과: "없는 대화방"과 "남의 대화방"이 **같은 응답(404)** 을 반환합니다.
> 이는 정보 노출을 막는 관점에서도 옳습니다. (`403`을 주면 "그 ID는 존재한다"를 알려줍니다.)

## 3-4. 🟢 잘 만든 것 — `getConversationHistory`의 최근 N개 조회

```java
if (maxMessages != null && maxMessages > 0) {
    Pageable pageable = PageRequest.of(0, maxMessages);
    messages = messageRepository.findRecentMessagesByConversationId(conversationId, pageable);
    messages = messages.stream()
            .sorted((m1, m2) -> m1.getMessageOrder().compareTo(m2.getMessageOrder()))   // ★ 재정렬
            .toList();
} else {
    messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);
}
```

**"최근 N개를 시간순으로"** 를 구현하는 표준 기법입니다.

```
① DB에서 DESC로 N개 조회   → [10, 9, 8]  (최근 3개)
② 자바에서 ASC로 재정렬     → [8, 9, 10]  (LLM에 넘길 순서)
```

`ORDER BY ASC LIMIT N`으로는 **가장 오래된 N개**가 나오므로 이 두 단계가 필요합니다.
**LLM 토큰 제한을 의식한 설계**이고, 주석에도 그렇게 적혀 있습니다.

> `PageRequest.of(0, maxMessages)`는 "0번 페이지, 크기 N"입니다.
> `Pageable`을 `@Query`와 함께 쓰면 스프링이 `LIMIT`을 자동으로 붙입니다.
> → [point.md 2-2](point.md#2-2-query를-쓴-이유--jpql의-limit)의 "방법 B"

## 3-5. 🟡 `getLastMessagePreview`의 문자열 자르기

```java
private String getLastMessagePreview(String lastMessageContent) {
    if (lastMessageContent == null || lastMessageContent.isEmpty()) {
        return "메시지가 없습니다.";
    }
    if (lastMessageContent.length() > 50) {
        return lastMessageContent.substring(0, 50) + "...";
    }
    return lastMessageContent;
}
```

동작합니다. 다만 두 가지 아쉬운 점:

1. **"메시지가 없습니다."는 화면 문구입니다.** 서버가 정하면 다국어 대응이 어렵습니다.
   `null`을 반환하고 프론트가 표시를 결정하는 편이 낫습니다.
2. **`substring`은 유니코드 서로게이트 페어를 쪼갤 수 있습니다.**
   이모지(😀)는 자바에서 `char` 2개(서로게이트 페어)로 표현되므로
   50번째가 그 중간이면 **깨진 문자(�)** 가 나옵니다.

```java
// 안전한 자르기
private String preview(String content, int maxChars) {
    if (content == null || content.isBlank()) return null;
    if (content.codePointCount(0, content.length()) <= maxChars) return content;
    int end = content.offsetByCodePoints(0, maxChars);      // 코드포인트 기준으로 경계 계산
    return content.substring(0, end) + "...";
}
```

> `String.length()`는 **`char` 개수**(UTF-16 단위)이고,
> `codePointCount()`는 **실제 문자 개수**입니다.
> 한글은 `char` 1개지만 이모지·일부 한자는 2개입니다.

## 3-6. 🟡 `totalCount`가 페이지 크기와 같다

```java
return ConversationListResponseDto.of(summaryDtos, (long) summaryDtos.size());
```

`countActiveConversationsByMemberId`를 만들어놓고 쓰지 않고, **리스트 크기를 그대로 넘깁니다.**
지금은 전체를 다 가져오므로 값이 맞지만, **페이징을 도입하면 즉시 틀립니다.**

> 그리고 **대화방 목록에 페이징이 없습니다.** 사용자가 대화방을 500개 만들면
> 사이드바 조회가 500행 + 상관 서브쿼리 500회가 됩니다.
> ChatGPT도 사이드바를 페이징(무한 스크롤)합니다. `Slice`를 쓰면 COUNT 없이 가능합니다.

---

# 4. `ConversationController`

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/conversations` | 대화방 생성 ("새 채팅") |
| GET | `/api/conversations` | 대화방 목록 (사이드바) |
| GET | `/api/conversations/{id}` | 대화 상세 (전체 메시지) |
| PATCH | `/api/conversations/{id}/title` | 제목 수정 |
| DELETE | `/api/conversations/{id}` | 대화방 삭제 (soft) |

## 4-1. 🔴 `permitAll`인데 인증을 전제한다

```java
// SpringSecurityConfig
.requestMatchers(new AntPathRequestMatcher("/api/conversations/**")).permitAll()
```

```java
// ConversationController — 모든 메서드가 이렇게 시작한다
log.info("대화방 생성 요청 - 사용자 ID: {}", principalDetails.getMember().getId());
//                                          ↑ 인증 없으면 null → NPE → 500
```

`question` 도메인의 LLM 질문 API도 `/api/conversations/**` 경로를 씁니다
(`ClaudeQuestionController`, `GeminiQuestionController`, `OpenaiQuestionController`).
**그래서 이 경로가 열려 있는 것으로 보입니다.**

하지만 그 컨트롤러들도 `principalDetails`를 쓰므로 결과는 같습니다.
**인증을 요구해야 합니다.**

```java
.requestMatchers("/api/conversations/**").authenticated()
```

## 4-2. 🟢 로그에 사용자 ID를 남기는 것

```java
log.info("대화방 생성 요청 - 사용자 ID: {}, 제목: {}", principalDetails.getMember().getId(), request.title());
log.info("대화방 목록 조회 요청 - 사용자 ID: {}", principalDetails.getMember().getId());
```

**추적 가능성(traceability)** 확보에 좋습니다. 장애 조사 시 "누가 언제 무엇을" 파악할 수 있습니다.

> 다만 **`request.title()`을 로그에 남기는 것**은 사용자 입력이 로그에 들어가는 것입니다.
> 제목은 민감하지 않지만, **개인정보나 대화 내용을 로그에 남기지 않는다**는 원칙은 지켜야 합니다.
> 이 프로젝트는 메시지 내용은 로그에 남기지 않고 있습니다. 잘 지켰습니다.
>
> 또 **목록 조회는 호출 빈도가 높으므로 `log.debug`가 적절**합니다.

## 4-3. 🟢 주석 품질

```java
/**
 * 사용자의 모든 대화방 목록을 조회합니다 (사이드바용)
 * ChatGPT처럼 왼쪽 사이드바에 표시할 채팅방 목록을 가져옴
 *
 * @param principalDetails 인증된 사용자 정보
 * @return 대화방 목록 (제목, 마지막 수정시간, 메시지 개수 포함)
 */
```

**이 프로젝트에서 주석이 가장 잘 달린 도메인입니다.**
"무엇을 하는지"가 아니라 **"왜 필요한지, 어떤 UI에 대응하는지"** 를 설명합니다.

```java
/**
 * 메시지 순서
 * 동일한 시간에 생성된 메시지들의 순서 보장
 */
Integer messageOrder;
```

`messageOrder`가 존재하는 이유까지 적혀 있습니다.
**코드만 봐서는 알 수 없는 의도**를 적는 것이 좋은 주석입니다.

> 단, 주석과 코드가 어긋난 곳이 있습니다.
> "비어있으면 첫 질문으로 자동 생성"([1-3](#1-3--제목이-null이면-500-에러가-난다))은 구현되지 않았습니다.
> **틀린 주석은 없는 주석보다 나쁩니다.** 읽는 사람이 그 동작을 믿기 때문입니다.

---

# 5. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | 제목 없이 대화방 생성 시 500 (`title` NOT NULL + 자동 생성 미구현) | [1-3](#1-3--제목이-null이면-500-에러가-난다) |
| 🔴 2 | `/api/conversations/**` 무인증 → NPE 500 | [4-1](#4-1--permitall인데-인증을-전제한다) |
| 🟠 3 | 읽기 메서드 3개에 `@Transactional(readOnly = true)` 누락 | [3-1](#3-1--읽기-메서드에-트랜잭션이-없다) |
| 🟠 4 | `IllegalArgumentException` 2곳 → 도메인 예외로 | [3-2](#3-2--예외-규약을-두-곳에서-어긴다) |
| 🟠 5 | `messageOrder` 경쟁 조건 (MAX+1, 유니크 제약 없음) | [1-4](#1-4--messageorder의-경쟁-조건) |
| 🟠 6 | `List<Object[]>` 반환 → 프로젝션 DTO로 | [2-2](#2-2--listobject-반환-타입의-문제) |
| 🟠 7 | 쓰이지 않는 `cascade = ALL, orphanRemoval` (soft delete 정책과 모순) | [1-1](#-cascade--cascadetypeall-orphanremoval--true가-실제로는-쓰이지-않는다) |
| 🟡 8 | 첨부파일 수동 JSON 직렬화 → `@JdbcTypeCode(SqlTypes.JSON)` | [1-5](#1-5--첨부파일을-json-문자열로-수동-관리) |
| 🟡 9 | 직렬화 실패를 `null`로 삼킴 → 첨부파일 유실 | [1-5](#1-5--첨부파일을-json-문자열로-수동-관리) |
| 🟡 10 | `toLlmApiFormat`이 `[메시지 N]` 접두사로 프롬프트 오염 (미사용) | [1-6](#1-6--tollmapiformat이-프롬프트를-오염시킨다) |
| 🟡 11 | 대화방 목록에 페이징 없음 + `totalCount`가 리스트 크기 | [3-6](#3-6--totalcount가-페이지-크기와-같다) |
| 🟡 12 | `Boolean isActive` → `boolean active` | [1-1](#boolean-isactive-vs-boolean-isactive) |
| 🟢 13 | 미사용 리포지토리 메서드 7개 + 미사용 DTO 1개 | [2-3](#2-3--중복-메서드와-미사용-메서드) |
| 🟢 14 | `DTO`가 자체 `ObjectMapper` 생성 (빈 미사용) | [1-5](#1-5--첨부파일을-json-문자열로-수동-관리) |
| 🟢 15 | `substring`이 이모지를 쪼갤 수 있음 | [3-5](#3-5--getlastmessagepreview의-문자열-자르기) |
| 🟢 16 | 화면 문구("메시지가 없습니다.")를 서버가 결정 | [3-5](#3-5--getlastmessagepreview의-문자열-자르기) |
| 🟢 17 | 주석과 구현 불일치 (제목 자동 생성) | [4-3](#4-3--주석-품질) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **소유권 조건을 쿼리에 포함** | 모든 조회·수정 경로에서 `memberId`를 검증. IDOR 방어의 정석 |
| 요약 조회 1쿼리 최적화 | 대화방 20개에서 41회 → 1회. 주석에 의도까지 기록 |
| `NULLS LAST` 정렬 | ChatGPT 사이드바와 동일한 UX를 SQL로 정확히 표현 |
| 최근 N개 조회 후 재정렬 | LLM 토큰 제한을 의식한 표준 기법 |
| `@Builder`를 생성자에 부착 | `@Builder.Default` 함정 회피 + 생성 파라미터 제한 |
| `MessageRole` enum의 `value` | DB 저장값과 LLM API 규격을 분리 |
| `COALESCE(MAX(...), 0) + 1` | `NULL + 1 = NULL` 함정을 정확히 처리 |
| soft delete (`isActive`) | 대화 이력 보존. 도메인 특성에 맞는 선택 |
| LLM을 전혀 모르는 설계 | `question` 도메인과 관심사 분리가 명확 |
| 주석 품질 | "왜"와 "어떤 UI에 대응"을 설명. 이 프로젝트 최고 수준 |
| `TEXT` / `length` 지정 | 답변은 `TEXT`, 모델명은 `length=50`. 크기를 의식 |

---

## 다음 문서

- [question.md](question.md) — 이 도메인에 질문/답변을 저장하는 LLM 호출 계층
- [llm.md](llm.md) — LLM 게이트웨이 추상화
- [community.md](community.md) — 생성자 프로젝션을 제대로 쓴 사례
