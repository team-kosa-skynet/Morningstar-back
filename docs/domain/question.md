# question — LLM 질의응답 (SSE 스트리밍 · 파일 첨부 · 모델 피드백)

> 선행 문서: [00-기초개념.md](../00-기초개념.md) · [conversation.md](conversation.md) · [llm.md](llm.md) · [point.md](point.md)
>
> 파일 44개로 두 번째로 큰 도메인입니다. 하위 패키지 5개(`claude`/`gemini`/`openai`/`common`/`feedback`)로
> 나뉘어 있고, **AI 제공자 3사를 나란히 붙인 "AI 모델 비교" 기능**의 핵심입니다.
>
> **이 도메인에는 이 프로젝트에서 가장 아까운 문제가 있습니다** —
> SSE 스트리밍을 만들었는데 **스트리밍이 되지 않고**, 파일을 **3번 중복 처리**합니다.

---

## 파일 지도

```
domain/question/
├── claude/                                    Anthropic Claude
│   ├── controller/ClaudeQuestionController.java
│   ├── dto/request/  ClaudeQuestionRequestDto, ClaudeMessage
│   ├── dto/response/ ClaudeQuestionResponseDto, Content, Usage
│   ├── service/ClaudeQuestionService.java          308줄
│   └── util/ClaudeQuestionProperties.java
├── gemini/                                    Google Gemini
│   ├── controller/GeminiQuestionController.java
│   ├── dto/  (Candidate, Content, Part, SafetyRating, Usage ...)
│   ├── service/GeminiQuestionService.java          495줄
│   └── util/GeminiQuestionProperties.java
├── openai/                                    OpenAI GPT
│   ├── controller/OpenaiQuestionController.java
│   ├── dto/  (OpenaiQuestionRequestDto, OpenaiImageGenerateRequestDto)
│   ├── service/OpenaiQuestionService.java          500줄
│   └── util/OpenaiQuestionProperties.java
├── common/
│   ├── controller/ModelInfoController.java         GET /api/models
│   ├── entity/AiModel.java                         모델 카탈로그
│   ├── repository/AiModelRepository.java
│   ├── service/ModelInfoService.java
│   ├── service/FileProcessingService.java     ★ Tika + PDFBox
│   ├── util/QuestionServiceUtils.java         ★ 3사 공통 로직
│   └── dto/response/  ModelInfoResponseDto, ProviderModelsDto, ModelDetailDto
└── feedback/                                  모델 평가 (리더보드용)
    ├── controller/ModelFeedbackController.java
    ├── entity/ModelFeedback.java, FeedbackCategory.java
    ├── repository/ModelFeedbackRepository.java
    └── service/ModelFeedbackService.java
```

**흐름**

```
POST /api/conversations/{id}/gemini/stream   (multipart: content + model + files[])
   │
   ▼ GeminiQuestionService.createQuestionStream
   │
   ├─ ① QuestionServiceUtils.validateAndGetMember()      회원 확인
   ├─ ② new SseEmitter(300_000L)                          5분 타임아웃
   ├─ ③ processQuestionWithTransaction()      @Transactional (⚠️ private → 무효)
   │     ├─ pointService.deductQuestionPoints()           -5 포인트
   │     ├─ QuestionServiceUtils.processFiles()           파일 처리 #1
   │     ├─ buildContentWithExtractedFiles()              파일 처리 #2 ⚠️
   │     └─ conversationService.addQuestion()             질문 저장
   │
   ├─ ④ performApiCallWithFiles()             ⚠️ 동기 실행 — 여기서 블로킹
   │     └─ LLM 호출 → emitter.send() 반복 → conversationService.addAnswer()
   │
   ├─ ⑤ QuestionServiceUtils.setupEmitterCallbacks()      ⚠️ 순서가 늦다
   └─ ⑥ return emitter
```

---

# 1. 🔴 SSE 스트리밍이 스트리밍되지 않는다

## 1-1. SSE란

**Server-Sent Events**는 서버가 클라이언트로 **한 방향으로 데이터를 계속 밀어 보내는** 표준입니다.
ChatGPT처럼 답변이 한 글자씩 나타나는 UI가 이것으로 만들어집니다.

```
Content-Type: text/event-stream

event: message
data: 안녕

event: message
data: 하세요

event: done
data: 완료
```

Spring MVC에서는 `SseEmitter`를 반환하면 됩니다.

```java
@PostMapping(value = "/{conversationId}/claude/stream",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)          // ★ SSE 선언
public SseEmitter streamQuestionWithFiles(...) {
    return claudeQuestionService.createQuestionStream(...);
}
```

**핵심 규칙: 컨트롤러는 `SseEmitter`를 즉시 반환하고, 실제 작업은 별도 스레드에서 해야 합니다.**

```java
// ✅ 올바른 SSE 패턴
public SseEmitter createStream() {
    SseEmitter emitter = new SseEmitter(300_000L);

    executor.execute(() -> {              // ★ 별도 스레드
        try {
            emitter.send("첫 조각");
            emitter.send("둘째 조각");
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;                       // ★ 즉시 반환 → 요청 스레드 해제
}
```

## 1-2. 이 프로젝트의 구조

```java
public SseEmitter createQuestionStream(Long conversationId, GeminiQuestionRequestDto dto,
                                       PrincipalDetails principalDetails) {
    Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);
    SseEmitter emitter = new SseEmitter(300000L);

    // 포인트 차감과 질문 저장을 트랜잭션으로 묶어서 처리
    List<FileAttachmentDto> attachments = processQuestionWithTransaction(
            conversationId, dto, principalDetails, member);

    // AI API 호출은 트랜잭션 외부에서 처리
    performApiCallWithFiles(emitter, conversationId, dto.model(), dto, member, attachments);
    //  ↑ private void — 별도 스레드가 아니다. 여기서 LLM이 끝날 때까지 블로킹.

    QuestionServiceUtils.setupEmitterCallbacks(emitter, "Gemini");
    return emitter;                       // ★ LLM이 다 끝난 뒤에야 반환된다
}
```

`performApiCallWithFiles`는 `private void` 일반 메서드입니다.
`@Async`도, `CompletableFuture`도, `executor.execute()`도 없습니다.
(세 서비스 모두 동일합니다.)

## 1-3. 무슨 일이 벌어지나

```
클라이언트 요청
     ↓
Tomcat 요청 스레드가 컨트롤러 진입
     ↓
createQuestionStream()
     ├─ SseEmitter 생성 (아직 응답이 시작되지 않음)
     ├─ 포인트 차감 + 질문 저장
     ├─ LLM 호출 ─────── 10~60초 블로킹 ───────
     │    │
     │    └─ 이 사이 emitter.send()가 호출되지만,
     │       Spring이 아직 응답을 열지 않았으므로 내부 버퍼에 쌓임
     │       (ResponseBodyEmitter의 earlySendAttempts)
     ↓
return emitter
     ↓
Spring이 비동기 응답 시작 → 버퍼에 쌓인 이벤트를 한꺼번에 전송
     ↓
클라이언트: 60초 침묵 후 전체 답변이 한 번에 도착
```

**결과 3가지**

| 문제 | 설명 |
|---|---|
| **스트리밍 효과가 없다** | 사용자는 60초 동안 아무것도 못 보고, 마지막에 전체가 한 번에 나타납니다 |
| **Tomcat 스레드가 묶인다** | SSE를 쓰는 목적(스레드 해제)이 완전히 무효화됩니다 |
| **동시 처리량이 제한된다** | 기본 `max-threads: 200`. LLM 호출이 200개 동시에 오면 서버 전체가 멈춥니다 |

**즉 "SSE의 비용(복잡한 코드, 연결 유지)은 다 내고 이점은 하나도 못 얻는" 상태입니다.**

## 1-4. 고치는 방법

```java
@Service
@RequiredArgsConstructor
public class GeminiQuestionService {

    private final Executor llmExecutor;              // AsyncConfig에 전용 풀 추가

    public SseEmitter createQuestionStream(Long conversationId, GeminiQuestionRequestDto dto,
                                           PrincipalDetails principalDetails) {
        Member member = QuestionServiceUtils.validateAndGetMember(principalDetails, memberRepository);

        SseEmitter emitter = new SseEmitter(300_000L);
        QuestionServiceUtils.setupEmitterCallbacks(emitter, "Gemini");   // ★ 먼저 등록 (1-5 참조)

        // 포인트 차감 + 질문 저장은 요청 스레드에서 (짧고, 실패 시 즉시 응답해야 하므로)
        List<FileAttachmentDto> attachments =
                questionTxService.prepareQuestion(conversationId, dto, principalDetails, member);

        // LLM 호출은 별도 스레드로 → 요청 스레드는 즉시 해제
        llmExecutor.execute(() -> {
            try {
                performApiCallWithFiles(emitter, conversationId, dto.model(), dto, member, attachments);
            } catch (Exception e) {
                QuestionServiceUtils.handleStreamError(emitter, e);
            }
        });

        return emitter;                              // ★ 즉시 반환
    }
}
```

```java
// AsyncConfig에 LLM 전용 풀 추가 — 검열·AI봇과 격리
@Bean(name = "llmExecutor")
public Executor llmExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(30);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("LLM-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());  // 초과 시 즉시 실패
    executor.initialize();
    return executor;
}
```

**`AbortPolicy`를 쓰는 이유**: LLM 요청이 폭주할 때 큐에 무한정 쌓아두면
사용자가 5분을 기다리다 타임아웃을 봅니다. **빠르게 거절해 "지금 혼잡합니다"를 알려주는 편이 낫습니다.**

## 1-5. 🟠 콜백 등록 순서가 늦다

```java
performApiCallWithFiles(emitter, ...);                        // ① API 호출 (여기서 예외 가능)
QuestionServiceUtils.setupEmitterCallbacks(emitter, "Gemini"); // ② 콜백 등록
return emitter;
```

`setupEmitterCallbacks`는 `onTimeout`, `onCompletion`, `onError` 핸들러를 등록합니다.

```java
public static void setupEmitterCallbacks(SseEmitter emitter, String serviceName) {
    emitter.onTimeout(() -> { log.warn("{} 스트리밍 타임아웃", serviceName); emitter.complete(); });
    emitter.onCompletion(() -> log.info("{} 스트리밍 완료", serviceName));
    emitter.onError(throwable -> log.error("{} 스트리밍 에러", serviceName, throwable));
}
```

**API 호출 중 타임아웃이나 에러가 나면 그 시점에는 핸들러가 없습니다.**
로그가 남지 않아 장애 원인을 찾을 수 없습니다.

**콜백은 항상 작업 시작 전에 등록해야 합니다.**

---

# 2. 🔴 같은 파일을 2~3번 처리한다

## 2-1. 문제

```java
@Transactional                                        // (private이라 무효 — 3-1 참조)
private List<FileAttachmentDto> processQuestionWithTransaction(
        Long conversationId, GeminiQuestionRequestDto dto,
        PrincipalDetails principalDetails, Member member) {

    pointService.deductQuestionPoints(principalDetails);

    // ★ 1회차: 파일 메타데이터 추출
    List<FileAttachmentDto> attachments =
            QuestionServiceUtils.processFiles(dto.files(), fileProcessingService);

    // ★ 2회차: 파일 본문 텍스트 추출 (같은 파일을 다시 처리!)
    String contentWithFiles = QuestionServiceUtils.buildContentWithExtractedFiles(
            dto.content(), dto.files(), fileProcessingService);
    ...
}
```

두 유틸 메서드가 **각각 내부에서 `fileProcessingService.processFile(file)`을 호출**합니다.

```java
// QuestionServiceUtils.processFiles
return files.stream()
        .map(file -> {
            Map<String, Object> processedFile = fileProcessingService.processFile(file);   // 호출 1
            return new FileAttachmentDto(...);
        })
        .collect(Collectors.toList());

// QuestionServiceUtils.buildContentWithExtractedFiles
for (MultipartFile file : files) {
    Map<String, Object> processedFile = fileProcessingService.processFile(file);            // 호출 2
    ...
}
```

## 2-2. `processFile` 한 번의 비용

```java
public Map<String, Object> processFile(MultipartFile file) {
    String mimeType = detectMimeTypeSafely(file);            // Tika MIME 감지 (파일 읽기)

    if (isImageFile(mimeType)) {
        String base64 = encodeToBase64(file);                // 전체 바이트 → Base64
    } else if (isTextBasedFile(mimeType)) {
        if (mimeType.equals("application/pdf")) {
            String extractedText = extractTextSafely(file, mimeType);   // Tika PDF 파싱

            if (isPdfWithNoText(extractedText)) {
                String base64Image = convertPdfToBase64Image(file);      // ★ 300 DPI 렌더링!
            }
        }
    }
}
```

```java
private String convertPdfToBase64Image(MultipartFile file) {
    PDDocument document = PDDocument.load(file.getBytes());
    PDFRenderer pdfRenderer = new PDFRenderer(document);
    BufferedImage image = pdfRenderer.renderImageWithDPI(0, 300);        // ★ 300 DPI
    ...
}
```

**300 DPI로 A4 한 장을 렌더링하면 약 2,480 × 3,508 픽셀 = 870만 픽셀입니다.**
`BufferedImage`(ARGB)로 약 **35MB의 힙**을 잡고, PNG 인코딩과 Base64 인코딩이 이어집니다.

**이것을 같은 파일에 대해 2번 합니다.**

| 작업 | 1회 비용(추정) | 2회 |
|---|---|---|
| Tika MIME 감지 | 수십 ms | — |
| Tika PDF 텍스트 추출 | 100ms ~ 수 초 | ×2 |
| PDF 300 DPI 렌더링 | 0.5 ~ 3초 + 35MB 힙 | ×2 |
| Base64 인코딩 | 파일 크기 × 1.33 | ×2 |

**스캔 PDF를 첨부하면 렌더링이 두 번 돌고 힙이 70MB 순간 점유됩니다.**
동시 사용자 5명이면 350MB입니다.

## 2-3. 고치는 방법 — 한 번 처리해서 넘겨 쓴다

```java
// ① 처리 결과를 담을 타입을 정의 (Map<String,Object> 대신)
public record ProcessedFile(
        String fileName,
        String type,            // "image" | "text" | "unsupported" | "error"
        long fileSize,
        String mimeType,
        String extractedText,   // type == "text"
        String base64           // type == "image"
) {}
```

```java
// ② 한 번만 처리
List<ProcessedFile> processed = dto.files() == null ? List.of()
        : dto.files().stream().map(fileProcessingService::process).toList();

// ③ 두 용도에 재사용
List<FileAttachmentDto> attachments = processed.stream()
        .map(p -> new FileAttachmentDto(p.fileName(), p.type(), p.fileSize(), p.mimeType()))
        .toList();

String contentWithFiles = QuestionServiceUtils.buildContent(dto.content(), processed);
```

**파일 처리 횟수가 2회 → 1회로, 코드도 더 명확해집니다.**

## 2-4. 🟠 `Map<String, Object>` 반환의 문제

```java
Map<String, Object> processedFile = fileProcessingService.processFile(file);

return new FileAttachmentDto(
        (String) processedFile.get("fileName"),      // 캐스팅
        (String) processedFile.get("type"),
        (Long) processedFile.get("fileSize"),
        (String) processedFile.get("mimeType")
);
```

**문제**

1. **키 이름이 문자열이라 오타를 컴파일러가 못 잡습니다.**
   `get("filename")`(소문자 n)이라고 쓰면 `null`이 반환되고 런타임에 NPE가 납니다.
2. **캐스팅이 필요하고 실패 시 `ClassCastException`** 입니다.
3. **어떤 키가 있는지 문서 없이 알 수 없습니다.**
   `type`이 `"error"`일 때 `extractedText`가 없다는 사실을 코드로 알 수 없습니다.

이 프로젝트는 `llm` 도메인에서도 같은 패턴을 씁니다(`Map<String, Object>` 반환).
→ [llm.md 8-9](llm.md#8-9--omreadvalueresponsetext-mapclass--원시-타입)

**`record`를 쓰면 셋 다 해결됩니다.**

## 2-5. 🟠 `PDDocument`를 try-with-resources로 닫지 않는다

```java
private String convertPdfToBase64Image(MultipartFile file) {
    try {
        PDDocument document = PDDocument.load(file.getBytes());
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        BufferedImage image = pdfRenderer.renderImageWithDPI(0, 300);   // ★ 여기서 예외가 나면?

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();

        document.close();                       // ⚠️ 예외가 나면 도달하지 않는다
        baos.close();
        ...
    } catch (Exception e) {
        log.error("PDF → 이미지 변환 실패: {}", file.getOriginalFilename(), e);
        return null;
    }
}
```

`renderImageWithDPI`가 `OutOfMemoryError`나 손상된 PDF로 예외를 던지면
**`document.close()`가 실행되지 않습니다.** `PDDocument`는 임시 파일과 메모리를 잡으므로 누수입니다.

```java
private String convertPdfToBase64Image(MultipartFile file) {
    try (PDDocument document = PDDocument.load(file.getBytes());
         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 150);  // DPI도 낮춤
        ImageIO.write(image, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());

    } catch (Exception e) {
        log.error("PDF → 이미지 변환 실패: {}", file.getOriginalFilename(), e);
        return null;
    }
}
```

**DPI를 150으로 낮추는 것도 검토할 만합니다.** LLM 비전 모델은 보통 이미지를
자체적으로 리사이즈하므로 300 DPI가 필요하지 않습니다. **힙 사용량이 1/4로 줄어듭니다.**

> 참고: 주석에 `// PDFBox 3.x 버전용 API 사용`이라고 적혀 있지만
> `PDDocument.load()`는 **PDFBox 2.x API**입니다. 3.x는 `Loader.loadPDF()`를 씁니다.
> `build.gradle`이 `pdfbox:2.0.31`이므로 **코드는 맞고 주석이 틀렸습니다.**

## 2-6. 🟢 잘 만든 것 — 다단 폴백 파일 처리

```java
private String detectMimeTypeSafely(MultipartFile file) {
    try {
        return tika.detect(file.getInputStream());              // ① Tika 내용 기반 감지
    } catch (Exception e) {
        log.warn("Tika MIME 타입 감지 실패, 대체 방법 사용: {}", file.getOriginalFilename(), e);

        String contentType = file.getContentType();              // ② 브라우저가 보낸 Content-Type
        if (contentType != null && !contentType.isEmpty()) return contentType;

        return getMimeTypeByExtension(file.getOriginalFilename());  // ③ 확장자 추정
    }
}
```

**3단 폴백입니다.** 그리고 순서가 옳습니다.

| 순위 | 방법 | 신뢰도 |
|---|---|---|
| ① | **파일 내용**(매직 넘버) 기반 | 높음 — 위조 불가 |
| ② | 브라우저가 보낸 `Content-Type` | 중간 — 클라이언트가 조작 가능 |
| ③ | 파일 확장자 | 낮음 — 이름만 바꾸면 됨 |

**`global/util/S3/S3ImageService`는 ③만 씁니다.**
→ [global 8-2](../global.md#8-2-s3imageservice)
같은 프로젝트에 Tika를 제대로 쓰는 코드가 있으니 **S3 쪽도 이 방식으로 통일**하면 됩니다.

**스캔 PDF 대응도 영리합니다.**

```java
if (isPdfWithNoText(extractedText)) {
    log.info("PDF에서 텍스트 추출 실패 또는 빈 텍스트 - 이미지로 변환 시도");
    String base64Image = convertPdfToBase64Image(file);
    ...
}
```

**텍스트 레이어가 없는 스캔 PDF는 Tika로 아무것도 못 뽑습니다.**
그때 이미지로 렌더링해 **LLM의 비전 능력으로 읽게** 하는 것은 실무적으로 정확한 판단입니다.

`processFile`이 예외를 절대 밖으로 던지지 않고 `type: "error"`로 반환하는 것도
**"파일 하나 때문에 질문 전체가 실패하지 않게"** 하는 설계입니다.

---

# 3. 🔴 포인트 차감의 문제

## 3-1. `@Transactional`이 private 메서드에 붙어 무효

```java
/**
 * 포인트 차감과 질문 저장을 트랜잭션으로 묶어서 처리    ← 주석의 의도
 */
@Transactional                                            // ⚠️ 완전히 무시됨
private List<FileAttachmentDto> processQuestionWithTransaction(...) {
    pointService.deductQuestionPoints(principalDetails);   // ① 포인트 -5
    ...
    conversationService.addQuestion(conversationId, member.getId(), questionRequest);   // ② 질문 저장
}
```

**세 서비스(claude/gemini/openai)에 동일한 코드가 있습니다.**

`private` + self-invocation이므로 프록시를 거치지 않습니다.
→ [기초개념 4-4](../00-기초개념.md#4-4-프록시--spring-마법의-정체), [point.md 3-1](point.md#3-1--버그--private-메서드의-transactional은-무시된다)

**결과: 포인트 차감과 질문 저장이 각각 별개 트랜잭션으로 커밋됩니다.**

```
① deductQuestionPoints  → 커밋 ✅ (포인트 5점 빠짐)
② addQuestion           → 대화방을 못 찾음 등으로 실패 ❌
→ 포인트만 빠지고 질문은 저장되지 않음
```

**주석이 약속한 원자성이 지켜지지 않습니다.**

## 3-2. 🔴 LLM 실패 시 포인트를 환불하지 않는다

더 큰 문제입니다. 순서를 보세요.

```java
processQuestionWithTransaction(...);      // 포인트 -5, 질문 저장
performApiCallWithFiles(...);             // LLM 호출 → 여기서 실패하면?
```

**LLM 호출이 실패해도 포인트는 이미 빠졌습니다.**

```
사용자: 질문 전송 → 5포인트 차감 → Gemini API 장애 → "오류가 발생했습니다"
     → 포인트는 돌아오지 않음
     → 다시 시도 → 또 5포인트 차감 → 또 실패
```

**LLM 장애 시 사용자가 포인트를 계속 잃습니다.**

**고치는 방법 2가지**

```java
// ① 실패 시 보상 트랜잭션(환불)
private void performApiCallWithFiles(SseEmitter emitter, ..., Member member) {
    try {
        ... LLM 호출 ...
    } catch (Exception e) {
        pointService.refundQuestionPoints(principalDetails);      // +5 환불 (PointType.REFUND)
        QuestionServiceUtils.handleStreamError(emitter, e);
    }
}

// ② 성공 후에 차감 (권장)
//    LLM 응답을 받은 뒤 addAnswer와 함께 차감하면 실패 시 차감이 아예 없다
```

**②가 더 단순하고 안전합니다.** 원장 방식이라 취소 거래를 남기는 ①도 감사 관점에서는 좋습니다.
→ [point.md 6장](point.md#6-pointcontroller--테스트용-api가-열려-있다)의 "상계 거래" 원칙

## 3-3. 🟡 주석과 코드의 금액이 다르다

```java
// GeminiQuestionService
// 질문 포인트 차감 (10포인트)
pointService.deductQuestionPoints(principalDetails);
```

```java
// PointService — 실제로는 5포인트
public void deductQuestionPoints(PrincipalDetails principalDetails) {
    PointRequestDto pointRequest = PointRequestDto.builder()
            .amount(-5)                    // 5포인트
            .type(PointType.QUESTION)
            .build();
    ...
}
```

커밋 이력에 `refactor: 질문하기 사용 5포인트 차감으로 변경`이 있습니다.
**금액을 바꾸면서 주석을 안 고쳤습니다.** 틀린 주석은 없는 주석보다 나쁩니다.

---

# 4. 🟠 세 서비스의 중복 — 1,300줄

## 4-1. 현황

| 서비스 | 줄 수 | 구조 |
|---|---|---|
| `OpenaiQuestionService` | 500 | `createQuestionStream` → `processQuestionWithTransaction` → `performApiCallWithFiles` |
| `GeminiQuestionService` | 495 | 동일 |
| `ClaudeQuestionService` | 308 | 동일 |

**세 서비스가 같은 골격을 갖고, 다른 것은 "HTTP 요청 본문 형식"과 "응답 JSON 경로"뿐입니다.**

```java
// 세 서비스에 모두 있는 메서드 (이름까지 동일)
public SseEmitter createQuestionStream(...)
@Transactional private List<FileAttachmentDto> processQuestionWithTransaction(...)
private void performApiCallWithFiles(...)
```

`QuestionServiceUtils`로 공통 부분을 빼낸 것은 좋은 시작인데,
**정작 가장 큰 중복(스트림 오케스트레이션)은 그대로 남았습니다.**

## 4-2. `llm` 도메인에 이미 답이 있다

이 프로젝트에는 **포트-어댑터 패턴이 이미 구축되어 있습니다.**
→ [llm.md](llm.md)

```java
// llm/port/InterviewerAiGateway — Gemini/OpenAI 어댑터가 구현
public interface InterviewerAiGateway {
    ModerationResult moderateContent(String content) throws Exception;
    String generateQuestionAnswer(String title, String content) throws Exception;
    ...
}
```

**그런데 `question` 도메인은 이 포트를 전혀 쓰지 않고 직접 HTTP를 호출합니다.**

```
[community]  →  llm 포트  →  Gemini/OpenAI 어댑터        ✅ 추상화 사용
[aibot]      →  llm 포트  →  Gemini 어댑터              ✅
[interview]  →  llm 포트  →  Gemini/OpenAI 어댑터        ✅
[question]   →  RestClient 직접 호출 × 3사               ❌ 추상화 우회
```

**같은 프로젝트에서 LLM 호출 경로가 두 갈래입니다.**
API 키 관리, 타임아웃, 재시도, 로깅이 모두 이중으로 존재합니다.

## 4-3. 개선 방향 — 스트리밍 포트 추가

기존 포트는 동기 응답용이라 스트리밍에 맞지 않습니다. **별도 포트를 정의합니다.**

```java
// llm/port/ChatStreamGateway.java
public interface ChatStreamGateway {

    /**
     * LLM에 채팅 요청을 보내고 응답 조각을 콜백으로 전달한다.
     * @param request  모델·메시지·첨부파일
     * @param onChunk  응답 조각이 도착할 때마다 호출
     * @return 전체 응답 텍스트
     */
    String streamChat(ChatRequest request, Consumer<String> onChunk) throws Exception;

    String getProviderName();
    boolean supports(String modelName);
}
```

```java
// question/service/QuestionStreamService.java — 3사 공통 오케스트레이션 하나로
@Service
@RequiredArgsConstructor
public class QuestionStreamService {

    private final List<ChatStreamGateway> gateways;      // ★ 구현체를 리스트로 주입받는다
    private final Executor llmExecutor;
    private final QuestionTransactionService txService;

    public SseEmitter stream(Long conversationId, ChatRequest request, PrincipalDetails principal) {
        ChatStreamGateway gateway = gateways.stream()
                .filter(g -> g.supports(request.model()))
                .findFirst()
                .orElseThrow(ModelNotFoundException::new);

        SseEmitter emitter = new SseEmitter(300_000L);
        QuestionServiceUtils.setupEmitterCallbacks(emitter, gateway.getProviderName());

        List<ProcessedFile> files = fileProcessingService.processAll(request.files());   // 1회만
        txService.saveQuestion(conversationId, principal, request, files);

        llmExecutor.execute(() -> {
            StringBuilder full = new StringBuilder();
            try {
                gateway.streamChat(request.withFiles(files), chunk -> {
                    full.append(chunk);
                    sendChunk(emitter, chunk);
                });
                txService.saveAnswerAndDeductPoints(conversationId, principal, full.toString(),
                                                    request.model());
                emitter.send(SseEmitter.event().name("done").data("완료"));
                emitter.complete();

            } catch (Exception e) {
                QuestionServiceUtils.handleStreamError(emitter, e);
            }
        });

        return emitter;
    }
}
```

**`List<ChatStreamGateway>` 주입이 핵심 기법입니다.**
스프링은 **같은 타입의 빈을 모두 리스트로 주입**해줍니다.
새 제공자를 추가하면 어댑터 클래스만 만들면 되고, 이 서비스는 손대지 않습니다.

**1,300줄이 300줄 + 어댑터 3개로 줄어들고, 컨트롤러도 3개 → 1개가 됩니다.**

---

# 5. `common` — 모델 카탈로그

## 5-1. 🟢 `AiModel` 엔티티 — 잘 설계된 카탈로그

```java
@Entity
@Table(name = "ai_models")
public class AiModel extends BaseTimeEntity {

    @Column(nullable = false, length = 20)   private String provider;      // claude|gemini|openai
    @Column(name = "model_name", nullable = false, length = 100) private String modelName;

    @Column(name = "supports_files", nullable = false)  private Boolean supportsFiles;
    @Column(name = "is_default", nullable = false)      private Boolean isDefault = false;
    @Column(name = "is_active", nullable = false)       private Boolean isActive = true;
    @Column(name = "is_create_image", nullable = false) private Boolean isCreateImage = false;

    public void updateActiveStatus(Boolean isActive) { this.isActive = isActive; }
    public void updateDefaultStatus(Boolean isDefault) { this.isDefault = isDefault; }
    public void updateFileSupport(Boolean supportsFiles) { this.supportsFiles = supportsFiles; }
    public void updateImageCreateSupport(Boolean isCreateImage) { this.isCreateImage = isCreateImage; }
}
```

**이 설계가 좋은 이유**

LLM 모델은 **수시로 추가·폐기됩니다.** 하드코딩하면 모델이 나올 때마다 배포해야 합니다.
DB 테이블로 관리하면 **INSERT 한 줄로 새 모델을 추가**할 수 있습니다.

`supportsFiles`, `isCreateImage`처럼 **모델별 능력(capability)** 을 플래그로 관리하는 것도 정확합니다.
프론트엔드는 `GET /api/models`로 목록을 받아 "이 모델은 파일 첨부 가능" 배지를 표시할 수 있습니다.

`isActive`로 **삭제 없이 비활성화**하는 것도 좋습니다(과거 대화 이력의 모델명이 깨지지 않음).

**생성자에서 `null` 방어를 하는 것도 세심합니다.**

```java
@Builder
public AiModel(String provider, String modelName, Boolean supportsFiles,
               Boolean isDefault, Boolean isActive, Boolean isCreateImage) {
    this.isDefault = isDefault != null ? isDefault : false;      // NOT NULL 컬럼에 null 방지
    this.isActive = isActive != null ? isActive : true;
    this.isCreateImage = isCreateImage != null ? isCreateImage : false;
}
```

> `Boolean`(래퍼) 대신 `boolean`(원시)을 쓰면 이 방어가 아예 필요 없습니다.
> → [conversation.md 1-1](conversation.md#boolean-isactive-vs-boolean-isactive)

## 5-2. 🟠 그런데 이 카탈로그를 활용하지 않는다

```java
// ClaudeQuestionProperties — 기본 모델을 하드코딩
@Component
@Getter
public class ClaudeQuestionProperties {

    private static Dotenv dotenv = Dotenv.load();              // ⚠️ global 1-7
    private final String apiKey = dotenv.get("CLAUDE_API_KEY");

    private final String responseUrl = "https://api.anthropic.com/v1/messages";
    private final String defaultModel = "claude-3-haiku-20240307";       // ⚠️ 하드코딩

    public String getModelToUse(String requestModel) {
        return (requestModel != null && !requestModel.trim().isEmpty()) ? requestModel : defaultModel;
    }
}
```

**`AiModelRepository`에 딱 맞는 메서드가 있습니다.**

```java
Optional<AiModel> findByProviderAndIsDefaultTrueAndIsActiveTrue(String provider);
```

**만들어놓고 쓰지 않습니다.**

```java
// DB의 기본 모델을 사용하도록
public String getModelToUse(String requestModel) {
    if (requestModel != null && !requestModel.isBlank()) {
        // 요청 모델이 활성 모델인지 검증까지 할 수 있다
        aiModelRepository.findByModelNameAndIsActiveTrue(requestModel)
                .orElseThrow(ModelNotFoundException::new);
        return requestModel;
    }
    return aiModelRepository.findByProviderAndIsDefaultTrueAndIsActiveTrue("claude")
            .map(AiModel::getModelName)
            .orElseThrow(ModelNotFoundException::new);
}
```

**더 중요한 문제: 요청 모델명을 검증하지 않습니다.**
사용자가 `model=존재하지않는모델`을 보내면 그대로 LLM API에 전달되고,
API가 400을 반환해 **500 에러로 나갑니다.** 그런데 **포인트는 이미 차감되었습니다.**

## 5-3. 🟠 `ai_models` 테이블을 채우는 코드가 없다

`point_tier`와 똑같은 문제입니다. → [pointTier.md 2-4](pointTier.md#2-4-이-테이블은-누가-채우나--아무도-채우지-않는다)

`ddl-auto: update`가 테이블은 만들지만 **행은 넣어주지 않습니다.**
그러면 `GET /api/models`가 빈 응답을 반환하고, 프론트엔드에 모델 선택 목록이 비어 있습니다.

커밋 이력에 이런 것이 있습니다:

```
chore(job-signal): 시각모델 단가표에 모델 4종 추가 (다른 세션 작업분)
```

**수동 SQL로 관리하고 있다는 뜻입니다.** 초기화 컴포넌트를 만들면 로컬 환경 세팅이 쉬워집니다.

## 5-4. 🟢 `ModelInfoService` — 제공자별 그룹화

```java
@Transactional(readOnly = true)      // 🟢 올바르게 붙었다
public ModelInfoResponseDto getAllModelInfo() {
    List<AiModel> activeModels = aiModelRepository.findAllActiveModelsOrderedByProviderAndDefault();

    Map<String, List<AiModel>> modelsByProvider = activeModels.stream()
            .collect(Collectors.groupingBy(AiModel::getProvider));       // 🟢 쿼리 1회 + 메모리 그룹화

    ProviderModelsDto claudeModels = buildProviderModels("claude", modelsByProvider.get("claude"));
    ProviderModelsDto geminiModels = buildProviderModels("gemini", modelsByProvider.get("gemini"));
    ProviderModelsDto openaiModels = buildProviderModels("openai", modelsByProvider.get("openai"));
    ...
}
```

**쿼리 1회로 전체를 가져와 자바에서 그룹화합니다.**
제공자별로 3번 쿼리하는 것보다 낫습니다(모델 수가 적으므로).

`Collectors.groupingBy`는 **분류 함수의 결과를 키로 묶어 `Map<K, List<T>>`를 만듭니다.**
SQL의 `GROUP BY`를 자바 스트림으로 하는 것입니다.

```java
// 기본 모델 찾기 — 없으면 첫 번째를 기본으로
String defaultModel = models.stream()
        .filter(AiModel::getIsDefault)
        .findFirst()
        .map(AiModel::getModelName)
        .orElse(models.get(0).getModelName());     // 🟢 방어적 폴백
```

**`isDefault`가 하나도 없어도 동작합니다.** 데이터 실수에 대한 방어가 있습니다.

### 🟢 죽은 코드

```java
int claudeCount = claudeModels != null ? claudeModels.models().size() : 0;
int geminiCount = geminiModels != null ? geminiModels.models().size() : 0;
int openaiCount = openaiModels != null ? openaiModels.models().size() : 0;

return response;      // ⚠️ 세 변수를 계산하고 쓰지 않는다
```

로그를 지우면서 변수를 남긴 흔적입니다. **삭제 대상입니다.**

### 🟠 제공자 이름이 하드코딩되어 있다

```java
buildProviderModels("claude", ...);
buildProviderModels("gemini", ...);
buildProviderModels("openai", ...);
```

**네 번째 제공자(예: Llama)를 추가하면 이 코드와 `ModelInfoResponseDto`를 고쳐야 합니다.**
`Map<String, ProviderModelsDto>`로 반환하면 코드 수정 없이 확장됩니다.

```java
public Map<String, ProviderModelsDto> getAllModelInfo() {
    return aiModelRepository.findAllActiveModelsOrderedByProviderAndDefault().stream()
            .collect(Collectors.groupingBy(AiModel::getProvider))
            .entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                                      e -> buildProviderModels(e.getKey(), e.getValue())));
}
```

## 5-5. 🟡 `QuestionServiceUtils.validateAndGetMember`의 죽은 검사

```java
public static Member validateAndGetMember(PrincipalDetails principalDetails,
                                          MemberRepository memberRepository) {
    Long memberId = principalDetails.getMember().getId();    // ★ principalDetails가 null이면 여기서 NPE
    if (memberId == null) {                                   // ⚠️ 절대 참이 되지 않음
        throw new UserInvalidAccessException();
    }
    return memberRepository.findById(memberId).orElseThrow(() -> new UserNotFoundException());
}
```

`PointService`와 같은 실수입니다. → [point.md 4-4](point.md#4-4-memberid--null-검사는-죽은-코드)

`/api/conversations/**`가 `permitAll`이라 **인증 없이 호출하면 첫 줄에서 NPE → 500**입니다.

```java
if (principalDetails == null || principalDetails.getMember() == null) {
    throw new UserInvalidAccessException();
}
```

**단, 근본 해결은 시큐리티에서 인증을 요구하는 것입니다.**
→ [conversation.md 4-1](conversation.md#4-1--permitall인데-인증을-전제한다)

## 5-6. 🟡 프롬프트를 사용자 콘텐츠에 섞는다

```java
// QuestionServiceUtils.buildContentWithExtractedFiles
contentBuilder.append("\n\n너는 파일을 해석하는 전문가야. 다음 파일의 내용을 분석하고 사용자의 질문에 답변해줘.\n\n");
contentBuilder.append("=== 파일 전체 내용 시작 ===\n\n");
contentBuilder.append("파일명: ").append(fileName).append("\n\n");
contentBuilder.append(extractedText);
contentBuilder.append("\n\n=== 파일 전체 내용 끝 ===\n");
```

**두 가지 문제**

1. **시스템 지시("너는 ~ 전문가야")가 사용자 메시지에 들어갑니다.**
   LLM API에는 `system` 역할이 따로 있습니다. 사용자 메시지에 지시를 섞으면
   **사용자가 그 지시를 덮어쓸 수 있습니다**(프롬프트 인젝션).

   ```java
   // 올바른 구조
   messages = [
     {"role": "system",  "content": "너는 파일 해석 전문가다. ..."},
     {"role": "user",    "content": "이 파일 요약해줘\n\n=== 파일 ===\n..."}
   ]
   ```

2. **이 지시문이 그대로 DB에 저장됩니다.**
   `addQuestion(conversationId, memberId, new AddQuestionRequestDto(contentWithFiles, ...))`이므로
   대화 이력을 다시 조회하면 사용자 화면에 **"너는 파일을 해석하는 전문가야"가 보입니다.**

   ```java
   // 저장은 원본, LLM 전송은 조립된 것으로 분리해야 한다
   conversationService.addQuestion(conversationId, memberId,
           new AddQuestionRequestDto(dto.content(), attachments));      // 원본만 저장
   String promptContent = buildPrompt(dto.content(), processedFiles);    // LLM 전송용
   ```

**추출한 파일 전문을 그대로 붙이는 것**도 위험합니다.
100페이지 PDF면 수십만 토큰이 되어 **컨텍스트 한도 초과 또는 비용 폭증**입니다.
길이 제한과 요약이 필요합니다.

---

# 6. `feedback` — 모델 평가 (AI 리더보드)

## 6-1. 🟢 `FeedbackCategory` — enum 안에 enum

```java
@Getter
@RequiredArgsConstructor
public enum FeedbackCategory {

    // 긍정적 피드백
    ACCURATE("정확해요", FeedbackType.POSITIVE),
    FAST("빨라요", FeedbackType.POSITIVE),
    SATISFYING("마음에 들어요", FeedbackType.POSITIVE),
    KIND("친절해요", FeedbackType.POSITIVE),
    DETAILED("답변이 자세해요", FeedbackType.POSITIVE),

    // 부정적 피드백
    INCORRECT("틀려요", FeedbackType.NEGATIVE),
    HALLUCINATION("환각증상", FeedbackType.NEGATIVE),
    SLOW("느려요", FeedbackType.NEGATIVE),
    TOO_LONG("답변이 너무 길어요", FeedbackType.NEGATIVE),
    NOT_DETAILED("자세하지 않아요", FeedbackType.NEGATIVE);

    private final String displayName;
    private final FeedbackType feedbackType;

    public enum FeedbackType { POSITIVE, NEGATIVE }      // ★ 중첩 enum
}
```

**설계가 좋습니다.**

- **분류(`FeedbackType`)를 enum이 스스로 알고 있습니다.**
  `if (category == ACCURATE || category == FAST || ...)` 같은 나열이 필요 없습니다.
- **화면 문구(`displayName`)가 함께 있습니다.**
- **중첩 enum**으로 `FeedbackType`을 `FeedbackCategory`의 소유로 표현했습니다.
  이 타입이 다른 곳에서 쓰이지 않으므로 적절한 캡슐화입니다.

```java
// 덕분에 이런 코드가 가능합니다
var positiveCategories = Arrays.stream(FeedbackCategory.values())
        .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.POSITIVE)
        .toList();
```

`@RequiredArgsConstructor`가 `final` 필드 2개를 받는 생성자를 만들어줍니다.
**enum에 Lombok을 쓰는 것도 잘 활용한 예입니다.**

## 6-2. 🔴 무한 포인트 획득이 가능하다

```java
@Transactional
public SubmitFeedbackResponseDto submitFeedback(SubmitFeedbackRequestDto requestDto,
                                                PrincipalDetails principalDetails) {
    ...
    ModelFeedback savedFeedback = modelFeedbackRepository.save(feedback);

    // 피드백 제출 보상 포인트 적립 (10포인트)
    pointService.rewardFeedbackPoints(principalDetails);      // ★ 제한 없음

    return SubmitFeedbackResponseDto.fromEntity(savedFeedback);
}
```

**중복 제출 방지가 전혀 없습니다.**

```bash
# 같은 피드백을 반복 제출하면 매번 10포인트
while true; do
  curl -X POST https://gaebang.site/api/feedback \
       -H "Authorization: Bearer <토큰>" \
       -d '{"positiveModel":"gpt-4o","negativeModel":"claude-3-haiku-20240307",
            "positiveFeedback":"ACCURATE","negativeFeedback":"SLOW"}'
done
```

**포인트는 질문·면접 기능에 쓰이고 결제로 구매하는 대상입니다.**
즉 **유료 기능을 무한히 공짜로 쓸 수 있습니다.**

`POST /api/point`([point.md 6장](point.md#6-pointcontroller--테스트용-api가-열려-있다))와
같은 종류의 문제인데, 이쪽은 **정상 기능으로 위장**되어 더 발견하기 어렵습니다.

**그리고 리더보드 데이터도 오염됩니다.** 한 사람이 특정 모델에 1,000표를 넣을 수 있습니다.

**고치는 방법**

```java
// ① 하루 1회 제한
@Query("SELECT COUNT(f) > 0 FROM ModelFeedback f " +
       "WHERE f.member.id = :memberId AND f.createdAt >= :todayStart")
boolean existsTodayFeedback(@Param("memberId") Long memberId,
                            @Param("todayStart") LocalDateTime todayStart);

@Transactional
public SubmitFeedbackResponseDto submitFeedback(...) {
    if (modelFeedbackRepository.existsTodayFeedback(memberId, LocalDate.now().atStartOfDay())) {
        throw new FeedbackAlreadySubmittedException();        // 포인트 지급 없이 거부
    }
    ...
}

// ② 또는 모델 조합당 1회 (DB 제약으로)
@Table(name = "model_feedback", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"member_id", "positive_model", "negative_model"})
})

// ③ 또는 실제 대화에 대한 피드백만 허용 (conversationId를 받아 소유권 검증)
```

**③이 가장 근본적입니다.** "실제로 두 모델을 써본 사람만 평가 가능"이 되어
리더보드의 신뢰도까지 올라갑니다.

## 6-3. 🟠 검증과 DB 제약이 어긋난다

```java
// 엔티티 — 네 필드 모두 NOT NULL
@Column(name = "positive_model", nullable = false)    private String positiveModel;
@Column(name = "negative_model", nullable = false)    private String negativeModel;
@Column(name = "positive_feedback", nullable = false)  private FeedbackCategory positiveFeedback;
@Column(name = "negative_feedback", nullable = false)  private FeedbackCategory negativeFeedback;
```

```java
// 서비스 — null이면 검증을 건너뛴다
if (requestDto.positiveModel() != null) {
    aiModelRepository.findByModelNameAndIsActiveTrue(requestDto.positiveModel())
            .orElseThrow(ModelNotFoundException::new);
}
if (requestDto.negativeModel() != null) { ... }
if (requestDto.positiveFeedback() != null) { ... }
if (requestDto.negativeFeedback() != null) { ... }
```

**`null`이면 검증을 통과하고 저장 단계에서 DB 제약 위반이 납니다.**

```
{"positiveModel": null, ...}
  → 검증 통과 (null이므로 if 블록 스킵)
  → INSERT → Column 'positive_model' cannot be null
  → DataIntegrityViolationException → 500 "서버 에러!"
```

**DTO에 `@NotBlank`/`@NotNull`을 붙여 400으로 처리해야 합니다.**

```java
public record SubmitFeedbackRequestDto(
        @NotBlank(message = "긍정 평가 모델을 선택해주세요") String positiveModel,
        @NotBlank(message = "부정 평가 모델을 선택해주세요") String negativeModel,
        @NotBlank(message = "긍정 피드백을 선택해주세요")   String positiveFeedback,
        @NotBlank(message = "부정 피드백을 선택해주세요")   String negativeFeedback,
        @Size(max = 1000) String detailedComment
) {}
```

> **그리고 같은 모델을 긍정·부정에 모두 넣을 수 있습니다.**
> `positiveModel == negativeModel`이면 의미 없는 데이터입니다. 검증이 필요합니다.

## 6-4. 🟢 `valueOf` 예외를 도메인 예외로 변환

```java
if (requestDto.positiveFeedback() != null) {
    try {
        FeedbackCategory positiveFeedbackCategory = FeedbackCategory.valueOf(requestDto.positiveFeedback());
        if (positiveFeedbackCategory.getFeedbackType() != FeedbackCategory.FeedbackType.POSITIVE) {
            throw new InvalidFeedbackCategoryException();          // 긍정 자리에 부정 카테고리
        }
    } catch (IllegalArgumentException e) {
        throw new InvalidFeedbackCategoryException();               // 존재하지 않는 enum 이름
    }
}
```

**`Enum.valueOf()`는 없는 이름이면 `IllegalArgumentException`을 던집니다.**
그것을 잡아 도메인 예외로 바꿨습니다. → 400 응답 ✅

`community` 도메인의 `BoardCategory.valueOf(dto.category())`는 이 처리를 하지 않아
**500이 납니다.** → [community.md 2-6](community.md#2-6--잘-만든-것--의미-있는-도메인-메서드)

**같은 프로젝트에서 한쪽은 제대로, 한쪽은 안 되어 있습니다.**

> 더 나은 방법: **DTO 필드 타입을 `FeedbackCategory`로 선언**하면
> 스프링이 자동 변환하고 실패 시 400을 반환합니다. 이 검증 코드가 전부 사라집니다.
> ```java
> public record SubmitFeedbackRequestDto(
>         @NotNull FeedbackCategory positiveFeedback,      // String이 아니라 enum
>         ...
> ) {}
> ```
> 다만 "긍정 자리에 부정 카테고리" 검증은 남겨야 하므로, 커스텀 밸리데이터가 어울립니다.

## 6-5. 🟡 `@Transactional(readOnly = true)` + `@Transactional` 조합

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)        // 🟢 클래스 기본은 읽기 전용
@Slf4j
public class ModelFeedbackService {

    public FeedbackOptionsResponseDto getFeedbackOptions() { ... }    // readOnly

    @Transactional                                                     // 🟢 쓰기 메서드만 덮어씀
    public SubmitFeedbackResponseDto submitFeedback(...) { ... }

    public Long getPositiveFeedbackCount(String modelName) { ... }     // readOnly
    public Long getNegativeFeedbackCount(String modelName) { ... }
}
```

**이것이 권장 패턴입니다.** → [기초개념 7-3](../00-기초개념.md#7-3-readonly--true--단순-최적화가-아니다)

`MemberService`(전체 쓰기), `ConversationService`(읽기 메서드 누락)와 비교하면
**이 서비스가 가장 잘 되어 있습니다.**

---

# 7. 정리

## 고쳐야 할 것

| 우선순위 | 문제 | 참조 |
|---|---|---|
| 🔴 1 | 피드백 무한 제출 → 무한 포인트 획득 + 리더보드 조작 | [6-2](#6-2--무한-포인트-획득이-가능하다) |
| 🔴 2 | SSE가 동기 실행 → 스트리밍 효과 없음 + 요청 스레드 블로킹 | [1장](#1--sse-스트리밍이-스트리밍되지-않는다) |
| 🔴 3 | 같은 파일을 2회 처리 (PDF 300 DPI 렌더링 중복, 힙 70MB) | [2장](#2--같은-파일을-23번-처리한다) |
| 🔴 4 | LLM 실패 시 포인트 환불 없음 → 장애 시 사용자 손실 | [3-2](#3-2--llm-실패-시-포인트를-환불하지-않는다) |
| 🔴 5 | `private @Transactional` 3곳 → 포인트/질문 원자성 없음 | [3-1](#3-1-transactional이-private-메서드에-붙어-무효) |
| 🟠 6 | 시스템 지시문이 사용자 메시지에 섞여 DB에 저장됨 | [5-6](#5-6--프롬프트를-사용자-콘텐츠에-섞는다) |
| 🟠 7 | 요청 모델명을 검증하지 않음 → 500 + 포인트 차감 | [5-2](#5-2--그런데-이-카탈로그를-활용하지-않는다) |
| 🟠 8 | 피드백 DTO 검증 누락 → `null`이 DB 제약 위반으로 500 | [6-3](#6-3--검증과-db-제약이-어긋난다) |
| 🟠 9 | 세 서비스 1,300줄 중복 + `llm` 포트 우회 | [4장](#4--세-서비스의-중복--1300줄) |
| 🟠 10 | `PDDocument`/`ByteArrayOutputStream` try-with-resources 미사용 | [2-5](#2-5--pddocument를-try-with-resources로-닫지-않는다) |
| 🟠 11 | 추출한 파일 전문을 길이 제한 없이 프롬프트에 삽입 | [5-6](#5-6--프롬프트를-사용자-콘텐츠에-섞는다) |
| 🟡 12 | SSE 콜백을 API 호출 후에 등록 | [1-5](#1-5--콜백-등록-순서가-늦다) |
| 🟡 13 | `Map<String, Object>` 반환 → `record`로 | [2-4](#2-4--mapstring-object-반환의-문제) |
| 🟡 14 | `ai_models` 초기 데이터 삽입 코드 없음 | [5-3](#5-3--ai_models-테이블을-채우는-코드가-없다) |
| 🟡 15 | `Dotenv.load()` static (Properties 3개 클래스) | [5-2](#5-2--그런데-이-카탈로그를-활용하지-않는다) |
| 🟡 16 | 기본 모델 하드코딩 (DB 조회 메서드가 있는데 미사용) | [5-2](#5-2--그런데-이-카탈로그를-활용하지-않는다) |
| 🟡 17 | `validateAndGetMember`의 죽은 `null` 검사 | [5-5](#5-5--questionserviceutilsvalidateandgetmember의-죽은-검사) |
| 🟡 18 | 제공자 이름 3개 하드코딩 → 확장 시 코드 수정 필요 | [5-4](#5-4--modelinfoservice--제공자별-그룹화) |
| 🟢 19 | 포인트 금액 주석(10) ≠ 실제(5) | [3-3](#3-3--주석과-코드의-금액이-다르다) |
| 🟢 20 | `ModelInfoService`의 미사용 변수 3개 | [5-4](#-죽은-코드) |
| 🟢 21 | PDFBox 버전 주석이 틀림 (3.x라고 적혀 있으나 2.x API) | [2-5](#2-5--pddocument를-try-with-resources로-닫지-않는다) |
| 🟢 22 | 같은 모델을 긍정·부정에 동시 지정 가능 | [6-3](#6-3--검증과-db-제약이-어긋난다) |

## 잘 만든 것

| 항목 | 왜 좋은가 |
|---|---|
| **`AiModel` 카탈로그 테이블** | LLM 모델이 수시로 바뀌는 현실에 맞는 설계. capability 플래그까지 |
| `isActive` 비활성화 방식 | 삭제 없이 숨김 → 과거 대화 이력의 모델명이 깨지지 않음 |
| **`FileProcessingService`의 3단 MIME 폴백** | 내용 → Content-Type → 확장자. 신뢰도 순서가 정확 |
| **스캔 PDF → 이미지 렌더링 폴백** | 텍스트 레이어 없는 PDF를 LLM 비전으로 읽게 함. 실무적 판단 |
| `processFile`이 예외를 던지지 않음 | 파일 1개 실패로 질문 전체가 죽지 않음 |
| **`FeedbackCategory` enum 설계** | 분류·표시명을 enum이 소유. 중첩 enum으로 캡슐화 |
| `valueOf` 예외를 도메인 예외로 변환 | 400 응답. `community`는 놓친 처리 |
| `ModelFeedbackService`의 트랜잭션 구성 | `readOnly` 기본 + 쓰기만 덮어쓰기. 이 프로젝트 최고 |
| `QuestionServiceUtils`로 공통 로직 추출 | 3사 중복을 줄이려는 시도 |
| SSE 콜백 3종 등록 | 타임아웃·완료·에러를 모두 처리 |
| `groupingBy`로 쿼리 1회 그룹화 | 제공자별 3회 쿼리를 1회로 |
| `orElse(models.get(0))` 폴백 | `isDefault` 데이터 실수에 대한 방어 |
| `getMimeTypeByExtension`의 `switch` 표현식 | Java 14+ 화살표 문법. 15종 매핑이 간결 |

**핵심 평가**: **기능적으로 가장 야심찬 도메인이고, 성능적으로 가장 손해를 보고 있습니다.**

파일 처리(Tika + PDFBox 폴백)와 모델 카탈로그 설계는 실무 수준입니다.
그런데 **SSE를 동기로 실행해 스트리밍 이점을 버리고, 파일을 두 번 처리해 비용을 두 배로 내고,
LLM 실패 시 포인트를 돌려주지 않습니다.**

가장 먼저 할 일은 두 가지입니다.

1. **피드백 중복 제출 차단** — 무한 포인트는 서비스 경제를 무너뜨립니다
2. **`performApiCallWithFiles`를 별도 스레드로 이동** — 몇 줄이면 SSE가 제대로 동작합니다

---

## 다음 문서

- [conversation.md](conversation.md) — 질문/답변이 저장되는 곳
- [llm.md](llm.md) — 이 도메인이 우회한 LLM 추상화
- [point.md](point.md) — 포인트 차감의 구조적 문제
- [interview.md](interview.md) — 같은 파일 처리 로직을 다르게 구현한 도메인
