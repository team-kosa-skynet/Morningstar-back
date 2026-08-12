# community — 커뮤니티 (게시판·댓글·좋아요·신고·AI 검열)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [global.md](../global.md) · [point.md](point.md)
>
> **60개 파일로 이 프로젝트에서 가장 큰 도메인**입니다.
> JPA 연관관계, 프로젝션, Redis 캐시, 이벤트 기반 비동기, 서킷브레이커가 **전부 여기 모여 있습니다.**
> 이 프로젝트의 가장 잘 만든 부분(이벤트 분리, 캐시 버전 무효화)과
> 가장 위험한 부분(무인증 쓰기 API, 검열 전 노출)이 함께 있습니다.

---

## 파일 지도

```
domain/community/
├── controller/                     4개
│   ├── BoardController.java            /api/boards (6개 엔드포인트)
│   ├── CommentController.java          /api/comments (3개)
│   ├── BoardLikeController.java        /api/boards/{id}/like (1개)
│   └── BoardReportController.java      /api/board-reports (3개)
├── dto/
│   ├── ModerationResult.java           검열 결과 (isInappropriate, reason)
│   ├── reqeust/                        ⚠️ 오타: request
│   │   ├── BoardCreateAndEditRequestDto.java
│   │   ├── CommentRequestDto.java
│   │   ├── BoardReportRequestDto.java
│   │   └── SearchConditionRequestDto.java
│   └── response/
│       ├── BoardListProjectionDto.java  ★ JPQL 생성자 프로젝션용
│       ├── BoardListResponseDto.java
│       ├── BoardDetailResponseDto.java
│       ├── CommentResponseDto.java
│       ├── BoardLikeResponseDto.java
│       └── BoardReportResponseDto.java
├── entity/                         9개
│   ├── Board.java / Comment.java        본체 (soft delete + 검열 상태)
│   ├── BoardBackup.java / CommentBackup.java   검열 전 원문 보관
│   ├── BoardLike.java                  좋아요
│   ├── BoardReport.java / CommentReport.java   신고
│   ├── Image.java                      첨부 이미지
│   ├── BoardCategory.java              GENERAL | QUESTION
│   └── ModerationStatus.java           PENDING | APPROVED | REJECTED
├── event/                          6개 (이벤트 5 + 리스너 1)
├── listener/                       3개
│   ├── ModerationEventListener.java    커밋 후 검열 트리거
│   ├── AiBotEventListener.java         검열 통과 후 AI 답변 트리거
│   └── AIAssistantEventListener.java   ⚠️ 빈 클래스
├── repository/                     9개 (LikeRepository는 중복)
├── service/                        10개
│   ├── BoardService.java               ★ 캐시 + 이벤트
│   ├── CommentService.java
│   ├── BoardLikeService.java
│   ├── BoardReportService.java
│   ├── ModerationService.java          ★ 검열 오케스트레이션
│   ├── TextModerationService.java      ★ 서킷브레이커 + LLM 폴백
│   ├── ImageModerationService.java
│   ├── ContentBackupService.java
│   ├── PostRateLimitService.java       도배 방지
│   └── AIAssistantService.java         ⚠️ 빈 클래스
└── util/TimeUtil.java                  "3분 전" 상대 시간
```

---

# 1. 전체 흐름 — 게시글 하나가 등록되면

이 도메인의 설계를 이해하는 가장 좋은 방법은 **한 번의 요청을 끝까지 따라가는 것**입니다.

```
POST /api/boards  { title, content, category, imageUrl[] }
   │
   ▼ ── BoardService.createBoard  @Transactional 시작 (T1) ──────────────
   │
   │  ① postRateLimitService.validatePostRateLimit(memberId)
   │       → 최근 5분간 게시글 3개 이상이면 예외 (도배 방지)
   │
   │  ② boardRepository.save(board)            INSERT board
   │       → moderationStatus = PENDING (기본값)
   │
   │  ③ images.forEach(url -> imageRepository.save(...))    INSERT image × N
   │
   │  ④ pointService.createPoint(+10, BOARD)   포인트 적립
   │
   │  ⑤ eventPublisher.publishEvent(new BoardCreatedEvent(id))     ← 아직 실행 안 됨
   │  ⑥ eventPublisher.publishEvent(new BoardChangedEvent(id, CREATED))
   │
   ▼ ── T1 COMMIT ────────────────────────────────────────────────────
   │       ★ 여기서 사용자에게 200 응답이 나갑니다
   │       ★ 게시글은 이미 목록에 보입니다 (검열 전!)
   │
   ├──▶ BoardChangedListener.onChanged        @TransactionalEventListener(AFTER_COMMIT)
   │      cacheVersion.bump()  → Redis 버전 +1 → 목록 캐시 전체 무효화
   │
   └──▶ ModerationEventListener.handleBoardCreated   @TransactionalEventListener(AFTER_COMMIT)
          │
          ▼ moderationService.moderateBoardAsync(id)   @Async("moderationExecutor")
            │   ★ 별도 스레드로 넘어감. 응답은 이미 나갔음.
            │
            │  ⓐ textModerationService.moderateTitleAndContent(title, content)
            │       → Gemini 호출 (실패 시 OpenAI 폴백, 둘 다 실패 시 차단)
            │  ⓑ 이미지가 있으면 imageModerationService.moderateImage(url)
            │
            ├── 부적절 판정 →  contentBackupService.createBoardBackup()  원문 백업
            │                  board.censorContent()  제목/내용을 검열 메시지로 교체
            │                  moderationStatus = REJECTED
            │                  (이미지가 문제면 S3 + DB에서 전부 삭제)
            │
            └── 통과 판정   →  board.approveModerationContent()
                               moderationStatus = APPROVED
                               eventPublisher.publishEvent(new ModerationCompletedEvent(id))
                                  │
                                  ▼ AiBotEventListener.handleModerationCompleted
                                      카테고리가 QUESTION이고 20자 이상이면
                                      → aiBotService.generateAnswerAsync(id)
                                      → AI가 댓글로 답변 작성
```

**이 흐름의 설계 의도**

| 결정 | 이유 |
|---|---|
| 검열을 **커밋 후**에 실행 | 트랜잭션 안에서 실행하면 다른 스레드가 아직 커밋 안 된 게시글을 조회 못 함 |
| 검열을 **비동기**로 실행 | LLM 호출이 수 초 걸림. 동기면 사용자가 그만큼 기다림 |
| 원문을 **백업** 후 교체 | 오탐(false positive) 시 복구 가능 |
| AI 답변을 **검열 통과 후**에 | 부적절한 질문에 AI가 답하는 것을 막음 |

**모두 올바른 판단입니다.** 특히 `@TransactionalEventListener(AFTER_COMMIT)`의 사용은
정확한 문제 인식에서 나온 것입니다. 주석에도 그 이유가 적혀 있습니다:

```java
/**
 * 게시글 생성 후 검열 처리
 * 트랜잭션이 완전히 커밋된 후에 실행되어 "게시글을 찾을 수 없습니다" 오류 방지
 */
```

**실제로 겪은 버그를 이렇게 해결한 흔적입니다.** 좋은 엔지니어링입니다.

## 1-1. 🔴 그런데 검열되기 전에 이미 노출된다

위 흐름에서 **★ 표시 지점**을 보세요.

```
T1 COMMIT → 사용자에게 200 응답 → 게시글이 목록에 보임
                                        ↓
                            (검열은 여기서부터 시작. 수 초 ~ 수십 초)
```

목록 조회 쿼리를 보면 `moderationStatus`를 **전혀 필터링하지 않습니다.**

```java
// BoardRepository.findAllBoardDtos
"FROM Board b WHERE b.deleteYn = 'N' "        // ← 검열 상태 조건이 없다
```

즉 **`PENDING` 상태(검열 전)와 `REJECTED` 상태(검열 실패로 남은 것)가 모두 목록에 나옵니다.**

`REJECTED`는 내용이 검열 메시지로 교체되므로 큰 문제는 없지만,
`PENDING`은 **원본 그대로 노출됩니다.**

**공격 시나리오**

```
① 부적절한 게시글 작성 → 즉시 목록에 노출
② 검열이 끝나기 전 수 초 동안 모든 사용자에게 보임
③ LLM API가 장애면? 검열이 예외로 끝나고 PENDING에 영원히 머묾 → 계속 노출
```

`ModerationService`가 모든 예외를 삼키기 때문에 ③이 실제로 가능합니다.

```java
} catch (Exception e) {
    log.error("게시글 검열 중 오류 발생 - ID: {}, 오류: {}", boardId, e.getMessage(), e);
}
// → 재시도 없음. 상태는 PENDING으로 남고 아무도 모른다.
```

**해결 방향 3가지**

```java
// ① 조회 시 PENDING을 제외한다 (가장 간단, 사용자는 자기 글이 늦게 보이는 것을 감수)
"WHERE b.deleteYn = 'N' AND b.moderationStatus <> 'PENDING' "

// ② 작성자에게만 PENDING을 보여준다
"WHERE b.deleteYn = 'N' AND (b.moderationStatus <> 'PENDING' OR b.member.id = :viewerId) "

// ③ PENDING 상태를 응답에 포함시켜 프론트가 "검토 중" 배지를 표시하게 한다
```

**그리고 검열 실패를 방치하지 않아야 합니다.**
이 프로젝트에는 이미 AI 봇용 복구 스케줄러(`AiBotRecoveryScheduler`)가 있습니다.
**검열에도 같은 것이 필요합니다.**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ModerationRecoveryScheduler {

    private final BoardRepository boardRepository;
    private final ModerationService moderationService;

    /** 10분마다 PENDING으로 남은 게시글을 재검열 */
    @Scheduled(fixedDelay = 600_000)
    public void retryPendingModeration() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<Board> stuck = boardRepository.findByModerationStatusAndCreatedAtBefore(
                ModerationStatus.PENDING, threshold);

        if (stuck.isEmpty()) return;
        log.warn("[검열복구] PENDING 상태 게시글 {}건 재시도", stuck.size());
        stuck.forEach(b -> moderationService.moderateBoardAsync(b.getId()));
    }
}
```

## 1-2. 🔴 그리고 쓰기 API에 인증이 없다

```java
// SpringSecurityConfig
// 임시
.requestMatchers(new AntPathRequestMatcher("/api/boards/**")).permitAll()
```

`POST`/`PATCH`/`DELETE`가 모두 무인증으로 통과합니다.
그러면 `principalDetails`가 `null`이 되어 `createBoard` 첫 줄에서 NPE → 500입니다.
상세는 [global 6-1 ③](../global.md#③-url별-권한--️-이-프로젝트-최대의-문제)에 있습니다.

---

# 2. `Board` 엔티티 — 연관관계와 Lombok의 함정

```java
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Entity
public class Board extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "board_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @OneToMany(mappedBy = "board")  @Builder.Default  private List<BoardLike>   boardLikes   = new ArrayList<>();
    @OneToMany(mappedBy = "board")  @Builder.Default  private List<Comment>     comments     = new ArrayList<>();
    @OneToMany(mappedBy = "board")  @Builder.Default  private List<BoardReport> boardReports = new ArrayList<>();
    @OneToMany(mappedBy = "board")  @Builder.Default  private List<Image>       images       = new ArrayList<>();

    private String title;
    private String content;

    @Builder.Default @Column(nullable = false)
    private String deleteYn = "N";

    @Enumerated(EnumType.STRING) @Builder.Default @Column(nullable = false)
    private BoardCategory category = BoardCategory.GENERAL;

    @Builder.Default @Column(nullable = false, columnDefinition = "BIGINT DEFAULT 1")
    private Long viewCount = 0L;

    @Enumerated(EnumType.STRING) @Builder.Default @Column(nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.PENDING;

    private LocalDateTime moderatedAt;
```

## 2-1. `@Builder.Default` — 없으면 `null`이 들어간다

**Lombok을 쓸 때 가장 자주 만나는 함정입니다.**

```java
@Builder
public class Board {
    private List<Comment> comments = new ArrayList<>();     // ⚠️ @Builder.Default 없음
}

Board board = Board.builder().title("제목").build();
board.getComments();    // → null!  (new ArrayList<>()가 무시됨)
```

**왜 그런가**: `@Builder`는 필드 초기화식을 무시하고 **빌더에 설정된 값만** 대입합니다.
설정하지 않은 필드는 그 타입의 기본값(`null`, `0`, `false`)이 됩니다.

`@Builder.Default`를 붙이면 Lombok이 초기화식을 별도 메서드로 빼내 빌더의 기본값으로 씁니다.

```java
@Builder.Default
private List<Comment> comments = new ArrayList<>();   // ✅ 빌더로 만들어도 빈 리스트
```

**이 프로젝트는 모든 컬렉션과 기본값 필드에 정확히 붙였습니다.** 잘한 부분입니다.

> `@Builder.Default`는 `@NoArgsConstructor`와 함께 쓸 때 미묘한 문제가 있습니다.
> Lombok이 필드 초기화식을 제거하므로, **기본 생성자로 만든 객체는 필드가 `null`** 입니다.
> JPA가 엔티티를 만들 때는 기본 생성자를 쓰지만, **곧바로 리플렉션으로 필드를 채우므로**
> 실무에서 문제가 되지 않습니다. 다만 원리는 알아둘 필요가 있습니다.

## 2-2. `mappedBy` — 연관관계의 주인은 누구인가

```java
// Board 쪽
@OneToMany(mappedBy = "board")
private List<Comment> comments;

// Comment 쪽
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "board_id")      // ★ 실제 FK 컬럼은 여기
private Board board;
```

**핵심 개념: DB에는 "양방향"이 없습니다.**
`comment` 테이블에 `board_id` FK 컬럼이 하나 있을 뿐입니다.

```
board                     comment
┌──────────┐             ┌──────────┬──────────┐
│ board_id │◀────────────│comment_id│ board_id │
└──────────┘             └──────────┴──────────┘
                                       ↑ FK는 이쪽에만 존재
```

객체 세계에서는 양쪽에서 서로를 참조할 수 있으니, JPA에게
**"FK를 관리하는 쪽(주인)이 어디인지"** 알려줘야 합니다.

| | 주인(owner) | 반대편(inverse) |
|---|---|---|
| 어노테이션 | `@ManyToOne` + `@JoinColumn` | `@OneToMany(mappedBy = "필드명")` |
| FK 관리 | **함** (여기 값을 바꿔야 DB가 바뀜) | 안 함 (**읽기 전용**) |
| 이 프로젝트 | `Comment.board` | `Board.comments` |

**`mappedBy = "board"`의 의미**: "나는 주인이 아니고, `Comment` 클래스의 `board` 필드가 주인이다."

**흔한 실수**

```java
// ❌ 이렇게 하면 DB에 저장되지 않는다 (Board.comments는 읽기 전용)
board.getComments().add(newComment);

// ✅ 주인 쪽에 설정해야 한다
Comment comment = Comment.builder().board(board).member(member).content("...").build();
commentRepository.save(comment);
```

이 프로젝트는 후자로 하고 있습니다. **올바릅니다.**

## 2-3. 🟠 컬렉션 4개가 거의 쓰이지 않는다

`Board`가 가진 컬렉션 4개 중 실제로 쓰이는 것은 **`images` 하나뿐**입니다
(`ModerationService`에서 `board.getImages()`).

`comments`, `boardLikes`, `boardReports`는 **개수 세기 용도로만** 필요한데,
그건 별도 쿼리로 처리하고 있습니다.

```java
// BoardService.getBoardDetail — 컬렉션을 쓰지 않고 count 쿼리를 따로 날린다
Long commentCount = commentRepository.countByBoardIdAndDeleteYn(boardId, "N");
Long likeCount = boardLikeRepository.countByBoardId(boardId);
```

**이 판단 자체는 옳습니다.** 댓글이 1,000개인 게시글에서 개수만 알고 싶은데
컬렉션을 로드하면 1,000개 엔티티를 메모리에 올려야 하니까요.

**하지만 쓰지 않는 양방향 연관관계는 위험합니다.**

1. 누군가 실수로 `board.getComments().size()`를 호출하면 **전체 로드**가 발생
2. Jackson이 엔티티를 직렬화하려 하면 **무한 순환 참조**(Board → Comment → Board → ...)
3. `cascade`나 `orphanRemoval`을 잘못 붙이면 대량 삭제 사고

**양방향은 필요할 때만 만드세요.** 여기서는 `images`만 남기고 나머지 3개를 지우는 것이 낫습니다.

## 2-4. 🟡 `viewCount`의 기본값이 자바와 DB에서 다르다

```java
@Builder.Default
@Column(nullable = false, columnDefinition = "BIGINT DEFAULT 1")   // DB 기본값 1
private Long viewCount = 0L;                                        // 자바 기본값 0
```

**자바는 0, DB는 1입니다.**

JPA로 저장하면 항상 자바 값(0)이 INSERT되므로 실제로는 0이 들어갑니다.
DB의 `DEFAULT 1`은 **SQL로 직접 INSERT할 때만** 적용됩니다.

`columnDefinition`을 쓰면 **DB 방언 독립성이 깨지는 문제**도 있습니다
(`BIGINT`는 MySQL 문법. PostgreSQL로 옮기면 `BIGINT`는 있지만 다른 타입에서는 깨짐).

```java
// 정리한 형태 — 자바 쪽에서만 기본값을 관리
@Builder.Default
@Column(nullable = false)
private Long viewCount = 0L;
```

## 2-5. 🟡 `deleteYn`을 `String`으로

```java
@Builder.Default
@Column(nullable = false)
private String deleteYn = "N";      // "Y" 또는 "N"

public void softDelete() { this.deleteYn = "Y"; }
```

전 코드에 `"N"`, `"Y"` 문자열 리터럴이 흩어져 있습니다.

```java
commentRepository.countByBoardIdAndDeleteYn(boardId, "N");
commentRepository.findByIdAndMemberIdAndDeleteYn(commentId, findMemberId, "N");
"WHERE b.deleteYn = 'N' "
```

**문제**: `"n"`, `"No"`, `"y"` 같은 오타를 컴파일러가 잡지 못합니다.
그리고 `deleteYn`이 무엇을 뜻하는지 코드만 봐서는 알기 어렵습니다.

**개선안 2가지**

```java
// ① boolean — 가장 단순
@Column(nullable = false)
private boolean deleted = false;
public void softDelete() { this.deleted = true; }
// 쿼리: WHERE b.deleted = false

// ② 상수화 — 기존 스키마를 유지하면서 오타만 막기
public final class DeleteFlag {
    public static final String ACTIVE  = "N";
    public static final String DELETED = "Y";
}
```

> **더 근본적인 개선: Hibernate의 `@SQLRestriction`**
> ```java
> @Entity
> @SQLRestriction("delete_yn = 'N'")     // Hibernate 6.3+
> public class Board extends BaseTimeEntity { ... }
> ```
> 이 어노테이션을 붙이면 **모든 조회 쿼리에 조건이 자동으로 붙습니다.**
> 리포지토리 메서드 이름에서 `AndDeleteYn`을 전부 없앨 수 있습니다.
> (단, `findById`에도 적용되어 관리자용 조회가 어려워지는 대가가 있습니다.)

## 2-6. 🟢 잘 만든 것 — 의미 있는 도메인 메서드

```java
public void updateBoard(BoardCreateAndEditRequestDto dto) {
    this.title = dto.title();
    this.content = dto.content();
    this.category = BoardCategory.valueOf(dto.category());
}

public void plusviewCount() { this.viewCount++; }

public void softDelete() { this.deleteYn = "Y"; }

public void censorContent(String censoredTitle, String censoredContent) {
    this.title = censoredTitle;
    this.content = censoredContent;
    this.moderationStatus = ModerationStatus.REJECTED;
    this.moderatedAt = LocalDateTime.now();
}

public void approveModerationContent() {
    this.moderationStatus = ModerationStatus.APPROVED;
    this.moderatedAt = LocalDateTime.now();
}
```

`@Setter`가 없고 **행위 이름을 가진 메서드만** 열어두었습니다.
특히 `censorContent`는 **"제목·내용 교체 + 상태 변경 + 시각 기록"을 한 덩어리로 묶어**
호출자가 세 가지 중 하나를 빠뜨릴 수 없게 만듭니다.
→ [기초개념 3-3](../00-기초개념.md#3-3-getter--그리고-setter가-없는-이유)

> 다만 `plusviewCount`는 자바 명명 규칙(`plusViewCount`)에 어긋납니다.
> `BoardCategory.valueOf(dto.category())`는 **잘못된 문자열이 오면 `IllegalArgumentException`** 을
> 던지고, 그게 전역 핸들러에서 500이 됩니다. DTO 필드를 `String`이 아니라
> `BoardCategory` 타입으로 받으면 스프링이 400으로 처리해줍니다.

## 2-7. 🟢 잘 만든 것 — 검열 백업 엔티티

```java
@Entity
public class BoardBackup extends BaseTimeEntity {
    @Column(nullable = false) private Long boardId;              // ★ 연관관계가 아니라 ID만
    @Column(columnDefinition = "TEXT") private String originalTitle;
    @Column(columnDefinition = "TEXT") private String originalContent;
    private String censorReason;

    public static BoardBackup createBackup(Long boardId, String title, String content, String reason) { ... }
}
```

**`@ManyToOne Board`가 아니라 `Long boardId`를 쓴 것이 의도적으로 보입니다.**

백업은 **감사 기록**입니다. 원본 게시글이 나중에 삭제되어도 백업은 남아야 합니다.
FK로 묶으면 삭제가 막히거나 연쇄 삭제됩니다. **ID만 갖는 것이 정답입니다.**

이런 패턴을 **"약한 참조(soft reference)"** 라고 하며, 로그·감사·이벤트 테이블에서 표준입니다.

`@Column(columnDefinition = "TEXT")`도 필요합니다.
`String` 기본 매핑은 `VARCHAR(255)`라서 긴 게시글이 잘리기 때문입니다.

> 더 이식성 있는 방법: `@Lob` 또는 `@Column(length = 65535)`.
> `columnDefinition`은 DB 방언에 묶입니다.

---

# 3. `BoardRepository` — JPQL 생성자 프로젝션

이 프로젝트에서 **가장 기술적으로 흥미로운 파일**입니다.

## 3-1. 생성자 프로젝션이란

```java
@Query(value = "SELECT new com.gaebang.backend.domain.community.dto.response.BoardListProjectionDto(" +
        "b.id, " +
        "b.title, " +
        "b.category, " +
        "(SELECT COUNT(c) FROM Comment c WHERE c.board = b AND c.deleteYn = 'N')," +
        "b.member.memberBase.nickname," +
        "(SELECT img.imageUrl FROM Image img WHERE img.board = b AND img.id = " +
        "(SELECT MIN(img2.id) FROM Image img2 WHERE img2.board = b))," +
        "b.createdAt," +
        "b.viewCount, " +
        "b.member.points, " +
        "(SELECT COUNT(bl) FROM BoardLike bl WHERE bl.board = b)) " +
        "FROM Board b " +
        "WHERE b.deleteYn = 'N' ",
        countQuery = "SELECT COUNT(DISTINCT b) FROM Board b WHERE b.deleteYn = 'N'")
Page<BoardListProjectionDto> findAllBoardDtos(Pageable pageable);
```

`SELECT new 패키지.클래스(...)` 문법으로 **엔티티를 만들지 않고 DTO를 직접 생성**합니다.

```
[일반 조회]                          [생성자 프로젝션]
SELECT b FROM Board b                SELECT new ...Dto(b.id, b.title, ...)
    ↓                                     ↓
Board 엔티티 20개 생성                  DTO 20개 생성
    ↓                                     ↓
영속성 컨텍스트에 등록 (스냅샷까지)      영속성 컨텍스트와 무관
    ↓                                     ↓
getMember() 호출 시 추가 SELECT (N+1)   추가 쿼리 없음 ✅
```

**장점**

1. **N+1이 원리적으로 발생하지 않습니다** — 필요한 값을 SQL 한 번에 전부 가져오므로
2. **메모리를 덜 씁니다** — 엔티티 + 스냅샷을 만들지 않음
3. **더티 체킹 대상이 아닙니다** — 읽기 전용 조회에 딱 맞음

**주의사항**

- **완전한 클래스명(FQCN)** 을 써야 합니다. `import`가 적용되지 않습니다.
- 생성자의 **파라미터 타입과 순서가 정확히** 일치해야 합니다. 틀리면 **런타임에** 실패합니다
  (컴파일러가 문자열 안을 검사하지 않으므로).
- `record`도 생성자가 있으니 사용 가능합니다.

**이 프로젝트가 N+1을 의식하고 제대로 대응한 부분입니다.** 좋습니다.

## 3-2. 상관 서브쿼리 vs GROUP BY — 두 가지 방식이 섞여 있다

`findAllBoardDtos`와 `findByCondition`은 **상관 서브쿼리**를 씁니다.

```sql
(SELECT COUNT(c) FROM Comment c WHERE c.board = b AND c.deleteYn = 'N')
(SELECT COUNT(bl) FROM BoardLike bl WHERE bl.board = b)
```

반면 `findByWriter`는 **JOIN + GROUP BY**를 씁니다.

```sql
"COUNT(distinct c), ... COUNT(distinct bl) FROM Board b "
"LEFT JOIN b.comments c ON c.deleteYn = 'N' "
"LEFT JOIN b.boardLikes bl "
"WHERE ... GROUP BY b"
```

**왜 두 방식이 생겼나** — 짐작 가능한 히스토리가 있습니다.

`LEFT JOIN` 방식에는 **곱집합(cartesian product) 문제**가 있습니다.

```
게시글 1개에 댓글 3개, 좋아요 4개가 있으면
→ JOIN 결과가 3 × 4 = 12행
→ COUNT(c) = 12, COUNT(bl) = 12   ← 틀린 값!
```

그래서 `COUNT(distinct c)`, `COUNT(distinct bl)`로 중복을 제거해야 합니다.
`findByWriter`가 정확히 그렇게 하고 있습니다.

**하지만 `DISTINCT`는 비쌉니다.** 12행을 만들고 나서 중복을 걸러내는 것이므로
행 수가 곱으로 늘어난 뒤에 정렬·해시 작업이 붙습니다.

**상관 서브쿼리는 곱집합을 아예 만들지 않습니다.**
그래서 나중에 상관 서브쿼리 방식으로 바꾼 것으로 보입니다.

> **`findByWriter`도 상관 서브쿼리로 통일하는 것이 좋습니다.**
> 마이페이지에서 쓰이므로 호출량이 적어 우선순위는 낮지만,
> **같은 일을 두 방식으로 하는 코드는 유지보수 부담**입니다.

## 3-3. 🟠 대표 이미지를 뽑는 3중 서브쿼리

```sql
(SELECT img.imageUrl FROM Image img WHERE img.board = b AND img.id =
    (SELECT MIN(img2.id) FROM Image img2 WHERE img2.board = b))
```

"이 게시글의 이미지 중 ID가 가장 작은 것의 URL" = **첫 번째 이미지**를 가져옵니다.

동작은 하지만 **게시글 행마다 서브쿼리가 2번 실행**됩니다.
게시글 20개면 이미지 관련 서브쿼리만 40번입니다.

**개선안**: `Board`에 대표 이미지 URL을 비정규화 컬럼으로 두는 것입니다.

```java
@Entity
public class Board {
    ...
    private String thumbnailUrl;      // 첫 이미지 URL을 저장 시점에 복사
}
```

`Member.points`와 같은 반정규화입니다([member.md 1-1](member.md#points-필드--왜-중복-저장하는가)).
목록 조회가 압도적으로 많은 API라면 이 트레이드오프가 맞습니다.

## 3-4. 🟠 `LIKE '%...%'` 검색은 인덱스를 못 쓴다

```sql
WHERE b.deleteYn = 'N'
  AND (b.title LIKE CONCAT('%', :condition, '%')
    OR b.member.memberBase.nickname LIKE CONCAT('%', :condition, '%')
    OR b.content LIKE CONCAT('%', :condition, '%'))
```

**세 가지 문제가 겹쳐 있습니다.**

1. **앞에 `%`가 붙으면 인덱스를 사용할 수 없습니다.**
   B-tree 인덱스는 "앞에서부터 일치"만 빠르게 찾습니다.
   `LIKE 'java%'`는 인덱스를 쓸 수 있지만 `LIKE '%java%'`는 **전체 스캔**입니다.
2. **`OR`로 3개 컬럼을 묶으면** 인덱스가 있어도 옵티마이저가 포기하기 쉽습니다.
3. **`content`는 긴 텍스트**입니다. 게시글 10만 건의 본문을 전부 스캔하면 수 초가 걸립니다.

**Redis 캐시가 이 문제를 가려주고 있습니다.** 같은 검색어면 3분간 캐시에서 나오니까요.
하지만 검색어는 다양하므로 캐시 적중률이 낮고, MISS마다 전체 스캔이 발생합니다.

**개선 방향**

```sql
-- ① MySQL 전문 검색 인덱스 (FULLTEXT)
ALTER TABLE board ADD FULLTEXT INDEX ft_board (title, content) WITH PARSER ngram;
-- 쿼리: WHERE MATCH(title, content) AGAINST(:keyword IN NATURAL LANGUAGE MODE)

-- ② 검색 대상을 제목으로 한정 (현실적인 절충)

-- ③ 외부 검색 엔진 (Elasticsearch, OpenSearch)
```

> 한국어는 형태소 분석이 필요해서 `ngram` 파서를 쓰거나 형태소 분석기를 붙여야 합니다.
> 학생 프로젝트 규모에서는 **②가 가장 실용적**입니다.

## 3-5. `LEFT JOIN FETCH` — 상세 조회의 N+1 방어

```java
@Query("SELECT b FROM Board b " +
        "LEFT JOIN FETCH b.images " +
        "LEFT JOIN FETCH b.member " +
        "WHERE b.deleteYn = 'N' AND b.id = :boardId")
Optional<Board> findBoardDetailById(@Param("boardId") Long boardId);
```

**`JOIN FETCH`란**: JOIN한 데이터를 **그 자리에서 함께 로드**해 LAZY 프록시를 남기지 않습니다.

```
[JOIN FETCH 없이]
SELECT * FROM board WHERE board_id = 3           (1회)
board.getImages()  → SELECT * FROM image ...     (+1회)
board.getMember()  → SELECT * FROM member ...    (+1회)
총 3회

[JOIN FETCH로]
SELECT b.*, i.*, m.* FROM board b
  LEFT JOIN image i ON ... LEFT JOIN member m ON ...
총 1회 ✅
```

**`LEFT`인 이유**: 이미지가 없는 게시글도 조회되어야 하므로.
`INNER JOIN FETCH`면 이미지 없는 게시글이 결과에서 사라집니다.

### 알아둘 미묘한 점 — 컬렉션 fetch join의 행 중복

이미지가 3개면 JOIN 결과가 3행이 됩니다.

```
board_id | title | image_url
    3    | 제목  | a.png
    3    | 제목  | b.png
    3    | 제목  | c.png
```

`Optional<Board>`를 기대하는데 3행이 오면 문제가 될 것 같습니다.
**Hibernate 6부터는 루트 엔티티를 자동으로 중복 제거**하므로 `Board` 1개가 나옵니다.

> Hibernate 5까지는 `SELECT DISTINCT b`를 명시해야 했습니다.
> 이 프로젝트는 Spring Boot 3.4.5 → Hibernate 6.6이라 자동 처리됩니다.
> **버전에 따라 동작이 달라지는 부분**이니 알아두세요.

### 🟠 컬렉션은 하나만 fetch join할 수 있다

만약 `images`와 `comments`를 동시에 fetch join하면:

```java
"LEFT JOIN FETCH b.images LEFT JOIN FETCH b.comments"   // ❌
// → MultipleBagFetchException: cannot simultaneously fetch multiple bags
```

`List`(순서 없는 bag) 두 개를 동시에 fetch하면 곱집합이 되어 개수를 신뢰할 수 없기 때문입니다.
해결책은 `Set`으로 바꾸거나, 하나만 fetch하고 나머지는 별도 쿼리로 가져오는 것입니다.

**이 프로젝트는 컬렉션 하나(`images`)만 fetch하고 댓글은 별도 페이징 조회**를 하므로
이 문제를 우연히 피했습니다.

## 3-6. 🟢 `countQuery`를 따로 준 것

```java
@Query(value = "...복잡한 프로젝션 쿼리...",
       countQuery = "SELECT COUNT(DISTINCT b) FROM Board b WHERE b.deleteYn = 'N'")
Page<BoardListProjectionDto> findAllBoardDtos(Pageable pageable);
```

`Page<T>`를 반환하려면 **전체 건수**를 알아야 하므로 스프링이 COUNT 쿼리를 자동 생성합니다.
그런데 서브쿼리가 많은 복잡한 쿼리에서는 **자동 생성이 실패하거나 비효율적**입니다.

`countQuery`를 직접 주면 **필요한 조건만 남긴 가벼운 COUNT**를 쓸 수 있습니다.
`SELECT` 절의 서브쿼리 4개가 COUNT에서는 전부 불필요하니까요. **정확한 최적화입니다.**

> `Page` 대신 `Slice`를 쓰면 COUNT 쿼리 자체를 없앨 수 있습니다
> ("다음 페이지가 있는가"만 판단). 무한 스크롤 UI에는 `Slice`가 더 적합합니다.

## 3-7. 🟢 중복 리포지토리 — `LikeRepository`

```java
// BoardLikeRepository.java
public interface BoardLikeRepository extends JpaRepository<BoardLike, Long> {
    Long countByBoardId(Long boardId);
    Optional<BoardLike> findByBoardIdAndMemberId(Long boardId, Long memberId);
}

// LikeRepository.java — 같은 엔티티, 메서드 없음, 사용처 없음
public interface LikeRepository extends JpaRepository<BoardLike, Long> {
}
```

**같은 엔티티에 대한 리포지토리가 2개**입니다. `LikeRepository`는 쓰이지 않습니다. 삭제 대상입니다.

`CommentReportRepository`도 메서드가 하나도 없고, **댓글 신고 기능 자체가 미완성**입니다
(`CommentReport` 엔티티는 있지만 컨트롤러·서비스가 없습니다).

---

# 4. `BoardService` — Redis 캐시 패턴

## 4-1. 캐시 조회 흐름

```java
@Transactional(readOnly = true)
public Page<BoardListResponseDto> getBoard(Pageable pageable) {

    // 1. 캐시 키 생성
    String version = cacheVersion.current();                    // Redis에서 현재 버전
    String cacheKey = Keys.boardsKey(version, "", pageable);
    // → "getBoards:v=3:cond=:p=0:s=10:sort=unsorted"

    try {
        // 2. 캐시 조회
        @SuppressWarnings("unchecked")
        PageResponse<BoardListResponseDto> cachedPage =
            (PageResponse<BoardListResponseDto>) redisTemplate.opsForValue().get(cacheKey);

        if (cachedPage != null) {
            log.info("Cache HIT - Key: {}", cacheKey);
            return cachedPage.toPageUsingRequest(pageable);
        }
        log.info("Cache MISS - Key: {}", cacheKey);

    } catch (Exception e) {
        log.warn("캐시 조회 실패, DB로 폴백: {}", e.getMessage(), e);      // ★ 중요
    }

    // 3. DB 조회
    Page<BoardListProjectionDto> getDtos = boardRepository.findAllBoardDtos(pageable);
    Page<BoardListResponseDto> result = transformBoardDtos(getDtos);

    // 4. 캐시 저장 (3분 TTL)
    try {
        redisTemplate.opsForValue().set(cacheKey, PageResponse.from(result), Duration.ofMinutes(3));
        log.info("Cache SET - Key: {}", cacheKey);
    } catch (Exception e) {
        log.warn("캐시 저장 실패: {}", e.getMessage(), e);                 // ★ 중요
    }

    return result;
}
```

## 4-2. 🟢 잘 만든 것 — 캐시 장애 시 DB 폴백

```java
try {
    ... 캐시 조회 ...
} catch (Exception e) {
    log.warn("캐시 조회 실패, DB로 폴백: {}", e.getMessage(), e);
}
// 예외를 삼키고 DB 조회로 계속 진행
```

**캐시는 있으면 좋은 것(nice to have)이고, 없어도 서비스는 돌아야 합니다.**
Redis가 죽었을 때 게시판 전체가 500이 되면 안 됩니다.

이 패턴을 **Graceful Degradation(우아한 성능 저하)** 이라고 합니다.
캐시 저장 실패도 같은 방식으로 처리합니다. **정확한 설계입니다.**

> 반대로 **캐시가 필수인 경우**(세션 저장소, 분산 락)에는 예외를 던져야 합니다.
> "이 데이터가 없으면 서비스가 성립하는가?"로 판단합니다.

## 4-3. 🟢 잘 만든 것 — 버전 기반 캐시 무효화

```java
// 게시글 등록/수정/삭제 시
eventPublisher.publishEvent(new BoardChangedEvent(saveBoard.getId(), ChangeType.CREATED));

// 리스너 (커밋 후)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onChanged(BoardChangedEvent e) {
    long newVersion = cacheVersion.bump();      // Redis INCR
    log.info("🔄 Board cache invalidated - New Version: {}", newVersion);
}
```

키에 버전이 들어 있으므로 **버전을 하나 올리면 모든 옛 키가 자동으로 무효**가 됩니다.
`KEYS getBoards:*` + `DEL`처럼 Redis를 블로킹시키는 위험한 방법을 피했습니다.
상세는 [global 7-2](../global.md#7-2-cacheversion--버전-번호로-캐시를-한-번에-무효화)에 있습니다.

**`AFTER_COMMIT`인 것도 중요합니다.** 커밋 전에 버전을 올리면
아직 보이지 않는 게시글을 기준으로 새 캐시가 만들어질 수 있습니다.

## 4-4. 🟠 캐시 코드가 두 메서드에 복사되어 있다

`getBoard`와 `getBoardByCondition`의 캐시 로직이 **거의 글자 단위로 동일**합니다.
차이는 `condition` 값과 호출하는 리포지토리 메서드뿐입니다.

```java
// 중복을 제거한 형태 — 캐시 로직을 한 곳으로
@Transactional(readOnly = true)
public Page<BoardListResponseDto> getBoard(Pageable pageable) {
    return findWithCache("", pageable, () -> boardRepository.findAllBoardDtos(pageable));
}

@Transactional(readOnly = true)
public Page<BoardListResponseDto> getBoardByCondition(String condition, Pageable pageable) {
    return findWithCache(condition, pageable,
            () -> boardRepository.findByCondition(condition, pageable));
}

private Page<BoardListResponseDto> findWithCache(
        String condition, Pageable pageable, Supplier<Page<BoardListProjectionDto>> loader) {

    String cacheKey = Keys.boardsKey(cacheVersion.current(), condition, pageable);

    Page<BoardListResponseDto> cached = readCache(cacheKey, pageable);
    if (cached != null) return cached;

    Page<BoardListResponseDto> result = transformBoardDtos(loader.get());
    writeCache(cacheKey, result);
    return result;
}

private Page<BoardListResponseDto> readCache(String cacheKey, Pageable pageable) {
    try {
        @SuppressWarnings("unchecked")
        PageResponse<BoardListResponseDto> cached =
                (PageResponse<BoardListResponseDto>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache HIT - {}", cacheKey);
            return cached.toPageUsingRequest(pageable);
        }
    } catch (Exception e) {
        log.warn("캐시 조회 실패, DB 폴백 - {}", cacheKey, e);
    }
    return null;
}

private void writeCache(String cacheKey, Page<BoardListResponseDto> result) {
    try {
        redisTemplate.opsForValue().set(cacheKey, PageResponse.from(result), CACHE_TTL);
    } catch (Exception e) {
        log.warn("캐시 저장 실패 - {}", cacheKey, e);
    }
}
```

**`Supplier<T>`를 쓴 이유**: "나중에 실행할 조회 동작"을 인자로 넘기기 위함입니다.
캐시 HIT이면 `loader.get()`이 아예 호출되지 않으므로 DB 조회가 발생하지 않습니다.
람다를 이렇게 **지연 실행(lazy evaluation)** 용도로 쓰는 것은 흔한 관용구입니다.

> **더 나아가면 `@Cacheable`을 쓸 수 있습니다.** `build.gradle`에
> `spring-boot-starter-cache`가 이미 있고, `RedisCacheConfig`(빈 파일)는 그걸 시도한 흔적입니다.
> 다만 `@Cacheable`은 프록시 기반이라 버전 키 전략을 표현하기 까다롭고,
> 지금의 명시적 방식이 **동작을 이해하기에는 더 낫습니다.**

## 4-5. 🟠 로그 레벨이 너무 높다

```java
log.info("Cache HIT - Key: {}", cacheKey);
log.info("Cache MISS - Key: {}", cacheKey);
log.info("Cache SET - Key: {}", cacheKey);
```

**게시판 목록 조회마다 INFO 로그가 1~2줄** 나갑니다.
운영 환경(`application-prod.yml`의 `root: INFO`)에서 그대로 기록되고,
Loki로 전송되어 저장 비용까지 발생합니다.

캐시 히트/미스는 **디버깅 정보**입니다.

```java
log.debug("Cache HIT - Key: {}", cacheKey);      // DEBUG로 내리기
```

히트율을 계속 보고 싶다면 로그가 아니라 **메트릭**을 쓰세요.
이 프로젝트는 이미 Micrometer + Prometheus가 붙어 있습니다.

```java
private final MeterRegistry meterRegistry;

meterRegistry.counter("board.cache", "result", "hit").increment();
meterRegistry.counter("board.cache", "result", "miss").increment();
```

## 4-6. 🔴 `readOnly = true` 안에서 조회수를 올린다

```java
@Transactional(readOnly = true)                    // ⚠️
public BoardDetailResponseDto getBoardDetail(Long boardId, Pageable commentPageable,
                                             PrincipalDetails principalDetails) {
    Board findBoard = boardRepository.findBoardDetailById(boardId)
            .orElseThrow(BoardNotFoundException::new);
    findBoard.plusviewCount();                      // ⚠️ 이 변경이 저장되지 않는다
    ...
}
```

`readOnly = true`는 Hibernate 세션을 읽기 전용으로 만들어 **더티 체킹을 하지 않고
커밋 시 flush도 하지 않습니다.** 따라서 `viewCount++`가 DB에 반영되지 않습니다.
→ [기초개념 7-3](../00-기초개념.md#7-3-readonly--true--단순-최적화가-아니다)

**고치는 방법 3가지**

```java
// ① readOnly를 떼기 — 가장 단순하지만 조회마다 UPDATE (동시성 충돌 + 쓰기 부하)
@Transactional
public BoardDetailResponseDto getBoardDetail(...) { ... }

// ② 벌크 UPDATE로 분리 — 원자적이고 갱신 유실이 없다
@Modifying(clearAutomatically = true)
@Query("UPDATE Board b SET b.viewCount = b.viewCount + 1 WHERE b.id = :boardId")
void incrementViewCount(@Param("boardId") Long boardId);

// ③ Redis 카운터 + 주기적 반영 — 조회가 많은 서비스의 표준 방식 (권장)
redisTemplate.opsForValue().increment("board:view:" + boardId);
// → 스케줄러가 1분마다 DB에 합산 반영
```

**②의 `b.viewCount = b.viewCount + 1`이 중요합니다.**
자바에서 `viewCount++`를 하면 "읽은 값 + 1"을 쓰므로 동시 조회 시 갱신이 유실됩니다.
DB에서 계산하면 **원자적**입니다.

`@Modifying`은 "이 쿼리는 데이터를 변경한다"를 알리는 어노테이션입니다.
`clearAutomatically = true`는 실행 후 영속성 컨텍스트를 비워
**메모리의 낡은 엔티티와 DB가 어긋나는 것을 막습니다.**

## 4-7. 🟠 `getBoardDetail`이 만드는 쿼리 수

```java
public BoardDetailResponseDto getBoardDetail(Long boardId, Pageable commentPageable, ...) {
    Board findBoard = boardRepository.findBoardDetailById(boardId)...;        // ① 1회 (fetch join)
    findBoard.plusviewCount();

    Member findBoardMember = findBoard.getMember();
    int memberLevel = memberService.getMemberTierOrder(findBoardMember);      // ② 1회 (티어)

    Long commentCount = commentRepository.countByBoardIdAndDeleteYn(boardId, "N");  // ③ 1회
    Long likeCount = boardLikeRepository.countByBoardId(boardId);                    // ④ 1회
    Page<CommentResponseDto> comments =
            commentService.getCommentsByBoardId(boardId, commentPageable, principalDetails);  // ⑤
    ...
}
```

⑤ 안에서 벌어지는 일:

```java
// CommentService.getCommentsByBoardId
public Page<CommentResponseDto> getCommentsByBoardId(Long boardId, Pageable pageable, ...) {
    Board findBoard = boardRepository.findById(boardId)                       // ⑤-1 게시글 재조회!
            .orElseThrow(BoardNotFoundException::new);

    return commentRepository.findByBoardIdAndDeleteYnOrderByCreatedAtDesc(...)  // ⑤-2 댓글 목록
            .map(comment -> CommentResponseDto.fromEntity(comment,
                    memberService.getMemberTierOrder(comment.getMember())));    // ⑤-3 ★
}
```

`comment.getMember()`는 **LAZY 프록시**입니다. `getMemberTierOrder(Member)`가
`member.getPoints()`를 호출하는 순간 **회원 SELECT가 발생**합니다.
그리고 `findTierOrderByPoints` 쿼리가 또 나갑니다.

**댓글 10개면:**

```
①  게시글 상세 (fetch join)         1회
②  작성자 티어                      1회
③  댓글 수 count                    1회  ← ⑤-2에서 어차피 조회하는데 중복
④  좋아요 수 count                  1회
⑤-1 게시글 재조회                   1회  ← ①에서 이미 조회했는데 중복
⑤-2 댓글 목록                       1회
⑤-3 댓글 작성자 로드 × 10          10회  ← N+1!
⑤-3 티어 조회 × 10                 10회  ← N+1!
────────────────────────────────────────
합계                               26회
```

**게시글 하나를 보는 데 쿼리 26개입니다.**

**개선 방향**

```java
// ① 댓글도 프로젝션 DTO로 (목록 조회에서 이미 쓰는 기법을 여기에도 적용)
@Query("SELECT new com.gaebang.backend...CommentProjectionDto(" +
       "c.id, c.content, c.createdAt, c.member.memberBase.nickname, c.member.points, " +
       "c.moderationStatus) " +
       "FROM Comment c WHERE c.board.id = :boardId AND c.deleteYn = 'N' " +
       "ORDER BY c.createdAt DESC")
Page<CommentProjectionDto> findCommentProjections(@Param("boardId") Long boardId, Pageable pageable);
// → ⑤-3의 20회가 0회로

// ② 티어를 메모리 캐싱 (pointTier.md 5-3)
// → ②와 ⑤-3의 티어 쿼리가 전부 0회로

// ③ getCommentsByBoardId에서 게시글 재조회 제거
//    (이미 BoardService가 조회했으므로 boardId만 넘기면 됨)

// ④ 댓글 수를 Page.getTotalElements()에서 얻기
//    (⑤-2가 Page를 반환하므로 count 쿼리가 이미 실행됨 → ③ 삭제 가능)
```

**개선 후: 26회 → 3회** (게시글 상세 + 댓글 목록 + 댓글 count)

---

# 5. `CommentService` / `BoardLikeService` / `BoardReportService`

## 5-1. 🟠 `BoardLikeService` — 좋아요 중복 방지가 없다

```java
@Transactional
public BoardLikeResponseDto togglePostLike(Long boardId, PrincipalDetails principalDetails) {
    Member loginMember = principalDetails.getMember();
    Board findBoard = boardRepository.findById(boardId).orElseThrow(BoardNotFoundException::new);

    Optional<BoardLike> findBoardLike =
            boardLikeRepository.findByBoardIdAndMemberId(boardId, loginMember.getId());

    if (findBoardLike.isPresent()) {
        return removeBoardLike(boardId, findBoardLike.get());        // 있으면 삭제
    } else {
        return addBoardLike(boardId, loginMember, findBoard);        // 없으면 추가
    }
}
```

**"조회 후 분기"는 동시 요청에 취약합니다.**

```
       요청 A (좋아요)                  요청 B (좋아요)
t1  findByBoardIdAndMemberId → 없음
t2                                 findByBoardIdAndMemberId → 없음
t3  INSERT board_like ✅
t4                                 INSERT board_like ✅   ← 같은 사람의 좋아요가 2개!
```

`BoardLike` 엔티티에 **유니크 제약이 없습니다.**

```java
@Entity
@Table(name = "board_like")           // ⚠️ uniqueConstraints 없음
public class BoardLike extends BaseTimeEntity { ... }
```

이 프로젝트는 `Point`와 `Attendance`에는 유니크 제약을 잘 넣었는데
([point.md 1-4](point.md#1-4-uniqueconstraintmember_id-version--동시성-방어선),
[attendance.md 1-1](attendance.md#1-1-uniqueconstraintmember_id-attendance_date))
여기서는 빠뜨렸습니다.

**결과**: 버튼을 빠르게 두 번 누르면 좋아요 수가 2 늘어나고,
그 뒤 토글하면 하나만 지워져 **영구히 어긋난 상태**가 됩니다.

```java
// 고치기
@Entity
@Table(name = "board_like", uniqueConstraints = {
        @UniqueConstraint(name = "uk_board_like", columnNames = {"board_id", "member_id"})
})
public class BoardLike extends BaseTimeEntity { ... }
```

```java
// 그리고 충돌을 정상 흐름으로 처리
try {
    return addBoardLike(boardId, loginMember, findBoard);
} catch (DataIntegrityViolationException e) {
    // 동시 요청 — 상대가 먼저 넣었으므로 현재 상태를 반환
    return BoardLikeResponseDto.builder()
            .liked(true)
            .likeCount(boardLikeRepository.countByBoardId(boardId))
            .message("이미 좋아요 상태입니다.")
            .build();
}
```

> `BoardReport`에도 같은 문제가 있습니다.
> `validateDuplicateReport`로 애플리케이션 검사만 하고 DB 제약이 없습니다.
> 다만 신고는 중복이 생겨도 피해가 작아 우선순위는 낮습니다.

## 5-2. 🔴 `BoardReportService` — 관리자 API에 권한이 없다

```java
public Page<BoardReportResponseDto> getBoardReports(Pageable pageable) {   // 전체 신고 목록
    return boardReportRepository.findAll(pageable).map(BoardReportResponseDto::fromEntity);
}

public void deleteBoardReport(Long boardReportId) {                        // 신고 삭제
    boardReportRepository.findById(boardReportId).orElseThrow(BoardReportNotFoundException::new);
    boardReportRepository.deleteById(boardReportId);
}
```

**신고 목록 조회와 삭제는 명백히 관리자 기능**인데 권한 검사가 없습니다.
`/api/board-reports`가 `permitAll` 목록에 없어 **로그인은 필요하지만**,
**일반 사용자도 전체 신고 내역을 보고 지울 수 있습니다.**

신고 내역에는 "누가 무엇을 신고했는지"가 들어 있어 **프라이버시 문제**이기도 합니다.

```java
// 메서드 보안으로 막기 (@EnableMethodSecurity 필요)
@PreAuthorize("hasRole('ADMIN')")
public Page<BoardReportResponseDto> getBoardReports(Pageable pageable) { ... }

// 또는 시큐리티 설정에서
.requestMatchers("/api/board-reports/**").hasRole("ADMIN")
```

> 참고로 이 프로젝트에는 **`ROLE_ADMIN`을 가진 회원을 만드는 코드가 없습니다.**
> `ROLE_USER`와 `ROLE_BOT`만 존재합니다. 관리자 개념 자체가 미구현입니다.

## 5-3. 🟠 `deleteBoardReport`의 불필요한 조회

```java
boardReportRepository.findById(boardReportId).orElseThrow(BoardReportNotFoundException::new);
boardReportRepository.deleteById(boardReportId);          // ← 또 조회한다
```

`deleteById`는 내부에서 `findById`를 한 번 더 실행합니다(엔티티를 찾아 `remove` 호출).
**같은 행을 두 번 조회**합니다.

```java
// 조회한 엔티티를 그대로 넘기면 1회로 줄어든다
BoardReport report = boardReportRepository.findById(boardReportId)
        .orElseThrow(BoardReportNotFoundException::new);
boardReportRepository.delete(report);
```

## 5-4. 🟠 `CommentService.createAiComment` — 하드코딩과 예외 규약 위반

```java
public Comment createAiComment(Long boardId, String content, String aiProvider, Double confidence) {
    Board findBoard = boardRepository.findById(boardId).orElseThrow(BoardNotFoundException::new);

    // AI 어시스턴트 전용 계정 조회 (Member ID = 999)
    Member aiMember = memberRepository.findById(999L)
            .orElseThrow(() -> new RuntimeException("AI 어시스턴트 계정이 존재하지 않습니다 (ID: 999)"));

    String finalContent = content;                    // ⚠️ 의미 없는 대입

    Comment aiComment = Comment.builder()
            .member(aiMember).board(findBoard).content(finalContent).build();
    return commentRepository.save(aiComment);
}
```

**문제 4가지**

1. **`999L` 하드코딩** — `AiMemberInitializer`도 같은 값을 가정하는데,
   AUTO_INCREMENT가 정하는 실제 ID는 999가 아닐 수 있습니다.
   → [global 1-6](../global.md#1-6-aimemberinitializer--기동-시-한-번-실행되는-초기화)
   **이메일로 조회하는 것이 맞습니다.**
2. **`RuntimeException`을 직접 던짐** — 이 프로젝트의 `ApplicationException` 규약 위반.
   전역 핸들러에서 500 "서버 에러!"가 되어 원래 메시지가 사라집니다.
3. **`aiProvider`, `confidence` 파라미터를 받고 쓰지 않음** —
   "기술 정보 제거하여 자연스럽게"라는 주석은 있지만, 그러면 파라미터를 지워야 합니다.
   (신뢰도를 `BotResponse` 엔티티에 저장하고 있으니 여기서는 불필요한 것으로 보입니다.)
4. **`String finalContent = content;`** — 아무 변환도 하지 않는 대입. 삭제 대상.

## 5-5. 🟠 `PostRateLimitService` — 우회 가능하고 비효율적

```java
@Slf4j @RequiredArgsConstructor @Service
public class PostRateLimitService {

    private final BoardRepository boardRepository;

    @Value("${rate-limit.post.max-count:3}")     private int maxPostCount;
    @Value("${rate-limit.post.window-minutes:5}") private int windowMinutes;

    public void validatePostRateLimit(Long memberId) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(windowMinutes);
        int recentPostCount = boardRepository
                .countByMemberIdAndDeleteYnAndCreatedAtAfter(memberId, windowStart);

        if (recentPostCount >= maxPostCount) {
            throw new PostRateLimitExceededException();
        }
    }
}
```

**설계는 좋습니다** — 설정 외부화(`@Value` + 기본값), 명확한 예외, 적절한 로그.

**문제 ① 삭제로 우회 가능**

쿼리 조건에 `b.deleteYn = 'N'`이 있습니다.

```java
"WHERE b.member.id = :memberId AND b.deleteYn = 'N' AND b.createdAt > :windowStart"
```

즉 **게시글을 쓴 뒤 지우면 카운트에서 빠집니다.**

```
글 3개 작성 → 한도 도달 → 3개 삭제 → 카운트 0 → 다시 3개 작성 → 무한 반복
```

도배 방지의 목적은 "**작성 행위**"를 제한하는 것이므로 삭제 여부와 무관해야 합니다.
`deleteYn` 조건을 제거하면 됩니다.

**문제 ② DB 쿼리로 카운트**

게시글마다 `board` 테이블에 COUNT 쿼리가 나갑니다.
`(member_id, created_at)` 복합 인덱스가 없으면 스캔 범위가 커집니다.

Rate limit은 **Redis가 훨씬 적합합니다.**

```java
@Service
@RequiredArgsConstructor
public class PostRateLimitService {

    private final StringRedisTemplate redis;

    @Value("${rate-limit.post.max-count:3}")      private int maxPostCount;
    @Value("${rate-limit.post.window-minutes:5}") private int windowMinutes;

    public void validatePostRateLimit(Long memberId) {
        String key = "rate:post:" + memberId;
        Long count = redis.opsForValue().increment(key);       // 원자적 증가

        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofMinutes(windowMinutes));   // 첫 요청에 TTL 설정
        }
        if (count != null && count > maxPostCount) {
            throw new PostRateLimitExceededException();
        }
    }
}
```

**이점**: 쿼리 0회, 원자적(동시 요청 안전), TTL 자동 만료, 삭제로 우회 불가.

> [email.md 3-4](email.md#3-4--발송-횟수-제한도-없다)에서 메일 발송 제한에 필요하다고 한 것이
> 바로 이 패턴입니다. **한 곳에 만들어 재사용하면 됩니다.**

## 5-6. 🟢 죽은 클래스 2개

```java
// AIAssistantService.java — 필드만 있고 메서드가 없다
@Slf4j @RequiredArgsConstructor @Service
public class AIAssistantService {
    private final MemberRepository memberRepository;
    private final BoardRepository boardRepository;
}

// AIAssistantEventListener.java — 완전히 비어 있다
@Slf4j @Component @RequiredArgsConstructor
public class AIAssistantEventListener {
}
```

AI 답변 기능을 여기에 만들려다 `aibot` 도메인으로 옮긴 흔적입니다.
**빈 껍데기가 빈으로 등록되어 있습니다.** 삭제 대상입니다.

---

# 6. 검열 시스템 — `ModerationService` + `TextModerationService`

## 6-1. `@Async` + `@Transactional` 조합

```java
@Async("moderationExecutor")
@Transactional
public CompletableFuture<Void> moderateBoardAsync(Long boardId) { ... }
```

**두 어노테이션이 함께 붙어 있습니다.** 동작 순서:

```
ModerationEventListener (다른 빈!)
   → moderationService.moderateBoardAsync(id)
        ↓ 프록시 경유 ✅
   @Async 프록시: moderationExecutor 스레드풀에 작업 제출 → 즉시 리턴
        ↓ (별도 스레드에서)
   @Transactional 프록시: 새 트랜잭션 시작
        ↓
   원본 메서드 실행 → 커밋
```

**다른 빈(`ModerationEventListener`)에서 호출하므로 프록시를 정상적으로 거칩니다.**
`PointService`와 `AttendanceService`가 빠진 self-invocation 함정을 여기서는 피했습니다.

> **`@Async`는 반드시 별도 스레드에서 돌기 때문에 트랜잭션이 새로 시작됩니다.**
> 호출자의 트랜잭션은 `ThreadLocal`에 있어서 넘어가지 않습니다.
> 그래서 `@Async` 메서드에 `@Transactional`을 따로 붙여야 합니다. **올바른 조합입니다.**

## 6-2. 🟠 그런데 비동기의 이점을 스스로 없앤다

```java
CompletableFuture<ModerationResult> textResultFuture =
        textModerationService.moderateTitleAndContent(board.getTitle(), board.getContent());

ModerationResult textResult = textResultFuture.get();        // ★ 블로킹!
```

`get()`은 **결과가 나올 때까지 현재 스레드를 멈춥니다.**
`CompletableFuture`를 받아놓고 즉시 `get()`을 부르면 **동기 호출과 완전히 같습니다.**

이미지 검열도 for 루프 안에서 `get()`을 호출하므로 **이미지 5개면 순차로 5번 대기**합니다.

```java
for (Image image : board.getImages()) {
    CompletableFuture<ModerationResult> imageResultFuture =
            imageModerationService.moderateImage(image.getImageUrl());
    ModerationResult imageResult = imageResultFuture.get();     // 하나씩 기다린다
    ...
}
```

**`moderationExecutor` 스레드가 그동안 계속 점유됩니다.**
Core 5 / Max 10이므로 동시 게시글 10개가 검열 중이면 11번째는 큐에서 대기합니다.

**개선 — 이미지를 병렬로 검열**

```java
// 모든 이미지 검열을 동시에 시작
List<CompletableFuture<ModerationResult>> futures = board.getImages().stream()
        .filter(img -> imageModerationService.isSupportedImageFormat(img.getImageUrl()))
        .map(img -> imageModerationService.moderateImage(img.getImageUrl()))
        .toList();

// 전부 끝날 때까지 한 번만 대기
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

boolean imageCensored = futures.stream().map(CompletableFuture::join)
        .anyMatch(ModerationResult::isInappropriate);
```

이미지 5개 × 3초 = **15초에서 3초로** 줄어듭니다.

> `get()`은 `InterruptedException`, `ExecutionException`(둘 다 checked)을 던져
> `throws`나 try/catch가 필요합니다. `join()`은 unchecked 예외를 던져 람다 안에서 편합니다.
> 이 코드는 메서드 전체를 `try { ... } catch (Exception e)`로 감싸서 처리하고 있습니다.

## 6-3. 🟠 `@TimeLimiter`가 동작하지 않는다

```java
@CircuitBreaker(name = "text-moderation", fallbackMethod = "fallbackModeration")
@Retry(name = "text-moderation")
@TimeLimiter(name = "text-moderation")
public CompletableFuture<ModerationResult> moderateText(String content) {
    ...
    ModerationResult result = primaryLlmGateway.moderateContent(content);   // ★ 동기 호출
    return CompletableFuture.completedFuture(result);                        // ★ 이미 완료된 future
}
```

```yaml
resilience4j.timelimiter.instances.text-moderation.timeout-duration: 10s
```

**`@TimeLimiter`는 "다른 스레드에서 실행 중인 작업"에 시간 제한을 겁니다.**
그런데 이 메서드는 **동기적으로 LLM을 호출하고, 끝난 뒤에 `completedFuture`로 감쌉니다.**

```
메서드 진입 → LLM 호출 (60초 걸림) → completedFuture 반환
                                          ↓
                          TimeLimiter가 future를 받아 검사
                          → 이미 완료됨 → 타임아웃 판정 불가 ❌
```

**10초 제한이 적용되지 않습니다.** LLM이 60초를 끌면 60초를 그대로 기다립니다.

**고치는 방법 — 실제로 비동기로 실행해야 합니다**

```java
@CircuitBreaker(name = "text-moderation", fallbackMethod = "fallbackModeration")
@Retry(name = "text-moderation")
@TimeLimiter(name = "text-moderation")
public CompletableFuture<ModerationResult> moderateText(String content) {
    if (!moderationEnabled) {
        return CompletableFuture.completedFuture(new ModerationResult(false, null));
    }
    // supplyAsync로 별도 스레드에서 실행 → TimeLimiter가 개입할 수 있다
    return CompletableFuture.supplyAsync(
            () -> primaryLlmGateway.moderateContent(content), moderationExecutor);
}
```

> **또는 `@TimeLimiter`를 지우고 HTTP 클라이언트 타임아웃에 의존해도 됩니다.**
> `HttpClientConfig`의 `RestClient`는 응답 타임아웃이 **600초(10분)** 라
> 지금은 사실상 무제한입니다. → [global 1-4](../global.md#1-4-httpclientconfig--외부-api를-부르는-두-가지-도구)
> **어느 쪽이든 실제로 동작하는 타임아웃이 하나는 있어야 합니다.**

## 6-4. Resilience4j 어노테이션 3종의 적용 순서

세 어노테이션이 함께 붙으면 순서가 중요합니다. Resilience4j의 기본 적용 순서(바깥 → 안):

```
Retry  →  CircuitBreaker  →  RateLimiter  →  TimeLimiter  →  Bulkhead  →  실제 메서드
```

즉 **`Retry`가 가장 바깥**입니다. 의미:

```
① 메서드 호출
② CircuitBreaker가 회로 상태 확인 (OPEN이면 즉시 폴백)
③ 실행 → 실패
④ Retry가 재시도 (설정: max-attempts 3, wait 2s, 배수 2 → 0s, 2s, 4s)
⑤ 3번 다 실패하면 CircuitBreaker에 실패 기록
⑥ 실패율이 60%를 넘으면 회로 OPEN → 30초간 모든 호출을 즉시 폴백
```

```yaml
resilience4j:
  circuitbreaker.instances.text-moderation:
    failure-rate-threshold: 60          # 실패율 60% 초과 시 OPEN
    wait-duration-in-open-state: 30s    # 30초 후 HALF_OPEN으로 전환
    sliding-window-type: count-based
    sliding-window-size: 10             # 최근 10건으로 실패율 계산
    minimum-number-of-calls: 5          # 5건 미만이면 판정하지 않음
    permitted-number-of-calls-in-half-open-state: 3   # HALF_OPEN에서 3건 시험
  retry.instances.text-moderation:
    max-attempts: 3
    wait-duration: 2s
    exponential-backoff-multiplier: 2
```

**서킷브레이커의 3가지 상태**

```
   CLOSED (정상)
      │ 실패율 > 60%
      ▼
   OPEN (차단) ── 모든 호출을 즉시 폴백. 죽은 서비스를 계속 때리지 않음.
      │ 30초 경과
      ▼
   HALF_OPEN (탐색) ── 3건만 실제로 시도
      ├─ 성공 → CLOSED
      └─ 실패 → OPEN
```

**왜 필요한가**: LLM API가 죽었을 때 계속 호출하면
① 스레드가 타임아웃까지 묶이고 ② 상대 서비스 복구를 방해하고 ③ 비용이 발생합니다.
회로를 열어 **빠르게 실패(fail fast)** 하는 것이 전체 시스템에 이롭습니다.

**이 설정은 잘 되어 있습니다.** 학생 프로젝트에서 서킷브레이커를 인스턴스별로
세분화해 설정한 것은 인상적입니다.

> ⚠️ 단, `sliding-window-size: 10` + `minimum-number-of-calls: 5`는
> **트래픽이 적을 때 판정이 느립니다.** 게시글이 하루 20개면 회로가 거의 열리지 않습니다.
> `time-based` 윈도우가 더 적합할 수 있습니다.

## 6-5. 🟢 폴백 전략 — Primary → Fallback → 보수적 차단

```java
private final InterviewerAiGateway primaryLlmGateway;      // Gemini
private final InterviewerAiGateway fallbackLlmGateway;     // OpenAI

public TextModerationService(
        @Qualifier("geminiInterviewerGateway") InterviewerAiGateway primaryLlmGateway,
        @Qualifier("openAiInterviewerGateway") InterviewerAiGateway fallbackLlmGateway) { ... }
```

**`@Qualifier`란**: 같은 타입의 빈이 여러 개일 때 **어느 것을 주입할지 이름으로 지정**합니다.
`InterviewerAiGateway`를 구현한 빈이 `GeminiAiAdapter`와 `OpenAiAiAdapter` 둘이므로
이름 없이는 스프링이 판단할 수 없습니다(`NoUniqueBeanDefinitionException`).

```java
public CompletableFuture<ModerationResult> fallbackModeration(String content, Exception exception) {
    try {
        ModerationResult result = fallbackLlmGateway.moderateContent(content);   // OpenAI 시도
        return CompletableFuture.completedFuture(result);
    } catch (Exception e) {
        log.error("[TEXT] Fallback LLM Gateway도 실패, 보수적 차단 처리: {}", e.getMessage());
        // 모든 LLM Gateway 실패 시 보수적으로 차단 (보안 우선)
        return CompletableFuture.completedFuture(
                new ModerationResult(true, "LLM 검열 시스템 전체 장애 - 관리자 검토 필요"));
    }
}
```

**3단 방어입니다.** 그리고 마지막 결정이 중요합니다:

> **둘 다 실패하면 "부적절"로 판정합니다 (fail-closed).**

이것은 **보안 원칙에 맞는 선택**입니다. 검열할 수 없으면 통과시키지 말아야 합니다.
반대(fail-open)로 만들면 LLM을 일부러 장애 상태로 만들어 검열을 우회할 수 있습니다.

**대가**: LLM 장애 시 **모든 게시글이 검열 메시지로 교체됩니다.**
원문은 백업에 있으니 복구 가능하지만, **복구 기능이 구현되지 않았습니다.**

```java
// ContentBackupService — 조회 메서드만 있고 복구 로직이 없다
// 추후 복구 기능용 메서드 (현재는 구현하지 않음)
public BoardBackup findBoardBackup(Long boardId) { return boardBackupRepository.findByBoardId(boardId); }
```

**fail-closed를 택했다면 복구 경로가 반드시 있어야 합니다.**

```java
@Transactional
public void restoreBoard(Long boardId) {
    BoardBackup backup = boardBackupRepository.findByBoardId(boardId);
    if (backup == null) throw new BackupNotFoundException();

    Board board = boardRepository.findById(boardId).orElseThrow(BoardNotFoundException::new);
    board.restore(backup.getOriginalTitle(), backup.getOriginalContent());   // 엔티티에 메서드 추가
}
```

## 6-6. 🟠 `fallbackMethod`의 시그니처 규칙

```java
@CircuitBreaker(name = "text-moderation", fallbackMethod = "fallbackModeration")
public CompletableFuture<ModerationResult> moderateText(String content) { ... }

public CompletableFuture<ModerationResult> fallbackModeration(String content, Exception exception) { ... }
//                                                            ↑ 원본과 동일   ↑ 예외 파라미터 추가
```

Resilience4j의 폴백 메서드 규칙:

1. **반환 타입이 원본과 같아야** 합니다
2. **원본의 파라미터를 그대로** 받고, **맨 뒤에 예외 파라미터**를 추가합니다
3. 접근 제어자는 `public`이어야 합니다 (프록시 기반이므로)

**규칙이 잘 지켜져 있습니다.** 다만 시그니처가 틀리면
`NoSuchMethodException`이 **런타임에** 발생하니 조심해야 합니다.

> **주의**: `moderateTitleAndContent`는 `moderateText`를 **self-invocation**으로 호출합니다.
> ```java
> public CompletableFuture<ModerationResult> moderateTitleAndContent(String title, String content) {
>     String combined = (title != null ? title : "") + "\n" + (content != null ? content : "");
>     return moderateText(combined.trim());       // ★ this.moderateText()
> }
> ```
> **따라서 `@CircuitBreaker`, `@Retry`, `@TimeLimiter`가 전부 적용되지 않습니다!**
>
> `ModerationService`는 게시글 검열에 `moderateTitleAndContent`를 쓰고
> 댓글 검열에는 `moderateText`를 직접 씁니다. 즉:
> - **댓글 검열** → 서킷브레이커·재시도 적용 ✅
> - **게시글 검열** → 아무것도 적용 안 됨 ❌ (폴백도 안 되므로 예외가 그대로 전파)
>
> 게시글 검열에서 Gemini가 실패하면 **OpenAI 폴백이 시도되지 않고 예외로 끝나** →
> `ModerationService`의 `catch (Exception e)`가 삼킴 → **`PENDING`에 영원히 남습니다.**
> [1-1](#1-1--그런데-검열되기-전에-이미-노출된다)에서 지적한 문제가 여기서 발생합니다.
>
> **고치는 방법**: 문자열 결합을 `ModerationService`에서 하고 `moderateText`를 직접 호출합니다.
> ```java
> // ModerationService
> String combined = (board.getTitle() == null ? "" : board.getTitle()) + "\n"
>                 + (board.getContent() == null ? "" : board.getContent());
> ModerationResult textResult = textModerationService.moderateText(combined.trim()).get();
> ```

## 6-7. 🟢 죽은 코드 — `moderateOptimized`

```java
public CompletableFuture<ModerationResult> moderateOptimized(String content) {
    if (content == null || content.trim().isEmpty()) {
        return CompletableFuture.completedFuture(new ModerationResult(false, null));
    }
    // 짧은 텍스트는 빠른 검열, 긴 텍스트는 정밀 검열
    if (content.length() < 100) {
        log.debug("단문 텍스트 검열 모드");        // ← 로그만 다르고
    } else {
        log.debug("장문 텍스트 검열 모드");        // ← 동작은 동일
    }
    return moderateText(content);
}
```

**아무도 호출하지 않고**, if/else의 두 갈래가 **로그 문자열만 다릅니다.**
"최적화"라는 이름과 달리 최적화가 없습니다. 삭제 대상입니다.

`getPrimaryLlmProvider()`, `getFallbackLlmProvider()`, `isEnabled()`도 호출처가 없습니다.

---

# 7. 정리 — 고쳐야 할 것

| 우선순위 | 문제 | 위치 | 참조 |
|---|---|---|---|
| 🔴 1 | 쓰기 API 무인증 (`/api/boards/**` permitAll) | `SpringSecurityConfig` | [1-2](#1-2--그리고-쓰기-api에-인증이-없다) |
| 🔴 2 | 검열 전(`PENDING`) 게시글이 목록에 노출 | `BoardRepository` 쿼리 | [1-1](#1-1--그런데-검열되기-전에-이미-노출된다) |
| 🔴 3 | 게시글 검열에 서킷브레이커·폴백이 적용되지 않음 (self-invocation) | `TextModerationService` | [6-6](#6-6--fallbackmethod의-시그니처-규칙) |
| 🔴 4 | 검열 실패 시 재시도 없음 → `PENDING` 영구 방치 | `ModerationService` | [1-1](#1-1--그런데-검열되기-전에-이미-노출된다) |
| 🔴 5 | 신고 목록 조회/삭제에 관리자 권한 없음 | `BoardReportService` | [5-2](#5-2--boardreportservice--관리자-api에-권한이-없다) |
| 🔴 6 | `readOnly = true`에서 조회수 증가 → 유실 | `BoardService:222` | [4-6](#4-6--readonly--true-안에서-조회수를-올린다) |
| 🟠 7 | 좋아요 유니크 제약 없음 → 중복 좋아요 | `BoardLike` 엔티티 | [5-1](#5-1--boardlikeservice--좋아요-중복-방지가-없다) |
| 🟠 8 | 상세 조회 1건에 쿼리 26회 (N+1 두 군데) | `BoardService`, `CommentService` | [4-7](#4-7--getboarddetail이-만드는-쿼리-수) |
| 🟠 9 | `@TimeLimiter`가 실제로 동작하지 않음 | `TextModerationService` | [6-3](#6-3--timelimiter가-동작하지-않는다) |
| 🟠 10 | 이미지 검열이 순차 실행 (`get()` 블로킹) | `ModerationService` | [6-2](#6-2--그런데-비동기의-이점을-스스로-없앤다) |
| 🟠 11 | 도배 방지를 삭제로 우회 가능 | `PostRateLimitService` | [5-5](#5-5--postratelimitservice--우회-가능하고-비효율적) |
| 🟠 12 | `LIKE '%...%'` 3컬럼 OR 검색 → 전체 스캔 | `BoardRepository` | [3-4](#3-4--like--검색은-인덱스를-못-쓴다) |
| 🟠 13 | 검열 복구(fail-closed 보상) 기능 미구현 | `ContentBackupService` | [6-5](#6-5--폴백-전략--primary--fallback--보수적-차단) |
| 🟡 14 | AI 계정 ID `999L` 하드코딩 + `RuntimeException` | `CommentService` | [5-4](#5-4--commentservicecreateaicomment--하드코딩과-예외-규약-위반) |
| 🟡 15 | 캐시 로직이 두 메서드에 복사 | `BoardService` | [4-4](#4-4--캐시-코드가-두-메서드에-복사되어-있다) |
| 🟡 16 | 캐시 HIT/MISS를 `log.info`로 → DEBUG 또는 메트릭 | `BoardService` | [4-5](#4-5--로그-레벨이-너무-높다) |
| 🟡 17 | 대표 이미지 3중 서브쿼리 → 비정규화 컬럼 검토 | `BoardRepository` | [3-3](#3-3--대표-이미지를-뽑는-3중-서브쿼리) |
| 🟡 18 | `findByWriter`만 GROUP BY 방식 (일관성 없음) | `BoardRepository` | [3-2](#3-2-상관-서브쿼리-vs-group-by--두-가지-방식이-섞여-있다) |
| 🟡 19 | `viewCount` 기본값이 자바 0 / DB 1 | `Board` 엔티티 | [2-4](#2-4--viewcount의-기본값이-자바와-db에서-다르다) |
| 🟡 20 | `deleteYn` 문자열 리터럴이 전역에 흩어짐 | 전체 | [2-5](#2-5--deleteyn을-string으로) |
| 🟡 21 | 쓰지 않는 양방향 컬렉션 3개 | `Board` 엔티티 | [2-3](#2-3--컬렉션-4개가-거의-쓰이지-않는다) |
| 🟡 22 | 게시글 삭제 시 S3 이미지가 남음 | `BoardService.deleteBoard` | — |
| 🟢 23 | 빈 클래스 2개 (`AIAssistantService`, `AIAssistantEventListener`) | `service`, `listener` | [5-6](#5-6--죽은-클래스-2개) |
| 🟢 24 | 중복 리포지토리 `LikeRepository` | `repository` | [3-7](#3-7--중복-리포지토리--likerepository) |
| 🟢 25 | 죽은 코드 `moderateOptimized` 및 미사용 getter 3개 | `TextModerationService` | [6-7](#6-7--죽은-코드--moderateoptimized) |
| 🟢 26 | `deleteBoardReport`가 같은 행을 2회 조회 | `BoardReportService` | [5-3](#5-3--deleteboardreport의-불필요한-조회) |
| 🟢 27 | 패키지명 오타 `dto/reqeust` | `dto` | [파일 지도](#파일-지도) |
| 🟢 28 | 댓글 신고 기능 미완성 (엔티티만 존재) | `CommentReport` | [3-7](#3-7--중복-리포지토리--likerepository) |

---

# 8. 정리 — 잘 만든 것

이 도메인은 **이 프로젝트에서 가장 야심차고, 가장 잘 만든 부분**도 갖고 있습니다.

| 항목 | 왜 좋은가 |
|---|---|
| `@TransactionalEventListener(AFTER_COMMIT)` | "커밋 전에는 다른 스레드가 못 본다"는 문제를 정확히 인식하고 해결. 주석에 이유까지 기록 |
| 검열을 비동기로 분리 | LLM 호출 수 초를 사용자 응답에서 제거 |
| 이벤트로 도메인 간 결합 제거 | `BoardService`는 검열·캐시·AI봇을 전혀 모른다 |
| 버전 기반 캐시 무효화 | `KEYS`+`DEL` 없이 원자적 전체 무효화. 실무 패턴 |
| 캐시 장애 시 DB 폴백 | Graceful Degradation. 캐시가 필수가 아님을 이해 |
| JPQL 생성자 프로젝션 | N+1을 원리적으로 차단. 필요한 컬럼만 조회 |
| `countQuery` 분리 | 복잡한 프로젝션 쿼리의 COUNT를 가볍게 |
| `LEFT JOIN FETCH` (상세 조회) | 연관 데이터를 1회 쿼리로 |
| 서킷브레이커 + Primary/Fallback LLM | 외부 의존성 장애를 3단으로 방어 |
| fail-closed 검열 정책 | "검열 못 하면 통과시키지 않는다" — 보안 원칙에 맞음 |
| 검열 전 원문 백업 (`BoardBackup`) | 오탐 복구 가능성 확보. FK 대신 ID만 갖는 설계도 정확 |
| 백업 엔티티가 `Long boardId`를 가짐 | 원본 삭제와 독립. 감사 테이블의 정석 |
| `@Builder.Default` 일관 적용 | Lombok의 흔한 함정을 전부 피함 |
| 엔티티에 의미 있는 도메인 메서드 | `censorContent`가 3가지 상태 변경을 원자적으로 묶음 |
| `ModerationStatus` 3단 상태 | PENDING/APPROVED/REJECTED로 검열 생애주기 표현 |
| 도배 방지 설정 외부화 | `@Value` + 기본값으로 재배포 없이 조정 가능 |
| AI 봇 트리거 조건 (카테고리 + 20자) | 무의미한 AI 호출을 걸러내는 실용적 필터 |

**핵심 평가**: 아키텍처 감각은 학생 프로젝트 수준을 넘습니다.
문제는 대부분 **"프레임워크의 세부 동작을 몰라서 의도가 무효화된 것"**
(self-invocation, `readOnly`, `TimeLimiter`)이지, 설계가 틀린 것이 아닙니다.

---

## 다음 문서

- [conversation.md](conversation.md) — AI 대화 저장
- [aibot.md](aibot.md) — 검열 통과 후 AI 답변을 만드는 곳
- [llm.md](llm.md) — `InterviewerAiGateway`와 두 어댑터
- [point.md](point.md) — `BoardService`가 호출하는 포인트 적립
- [pointTier.md](pointTier.md) — 목록 조회의 티어 쿼리 N회 문제
