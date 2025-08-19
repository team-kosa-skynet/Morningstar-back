package com.gaebang.backend.domain.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interview.config.QuestionCatalog;
import com.gaebang.backend.domain.interview.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interview.dto.internal.InterviewPlanDto;
import com.gaebang.backend.domain.interview.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interview.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interview.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interview.dto.request.UpsertContextRequestDto;
import com.gaebang.backend.domain.interview.dto.response.FinalizeReportResponseDto;
import com.gaebang.backend.domain.interview.dto.response.NextTurnResponseDto;
import com.gaebang.backend.domain.interview.dto.response.StartSessionResponseDto;
import com.gaebang.backend.domain.interview.dto.response.TtsPayloadDto;
import com.gaebang.backend.domain.interview.entity.InterviewAnswer;
import com.gaebang.backend.domain.interview.entity.InterviewSession;
import com.gaebang.backend.domain.interview.entity.UploadedDocument;
import com.gaebang.backend.domain.interview.enums.InterviewMode;
import com.gaebang.backend.domain.interview.enums.InterviewStatus;
import com.gaebang.backend.domain.interview.llm.InterviewerAiGateway;
import com.gaebang.backend.domain.interview.repository.InterviewAnswerRepository;
import com.gaebang.backend.domain.interview.repository.InterviewSessionRepository;
import com.gaebang.backend.domain.interview.repository.UploadedDocumentRepository;
import com.gaebang.backend.domain.interview.util.PlanParser;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class InterviewService {

    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewAnswerRepository interviewAnswerRepository;
    private final MemberRepository memberRepository;
    private final UploadedDocumentRepository uploadedDocumentRepository;
    private final InterviewerAiGateway openAiGateway;
    private final InterviewerAiGateway geminiGateway;
    private final ObjectMapper om;
    private final PlanParser planParser;
    private final QuestionCatalog questionCatalog;
    private final TtsService ttsService;
    
    @Value("${tts.default-format:mp3}")
    private String defaultTtsFormat;
    
    @Value("${ai.provider:openai}")
    private String aiProvider;

    public InterviewService(InterviewSessionRepository interviewSessionRepository,
                            @Qualifier("openAiInterviewerGateway") InterviewerAiGateway openAiGateway,
                            @Qualifier("geminiInterviewerGateway") InterviewerAiGateway geminiGateway,
                            ObjectMapper objectMapper,
                            InterviewAnswerRepository interviewAnswerRepository,
                            MemberRepository memberRepository,
                            UploadedDocumentRepository uploadedDocumentRepository,
                            PlanParser planParser, QuestionCatalog questionCatalog,
                            TtsService ttsService) {
        this.interviewSessionRepository = interviewSessionRepository;
        this.interviewAnswerRepository = interviewAnswerRepository;
        this.memberRepository = memberRepository;
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.openAiGateway = openAiGateway;
        this.geminiGateway = geminiGateway;
        this.om = objectMapper;
        this.planParser = planParser;
        this.questionCatalog = questionCatalog;
        this.ttsService = ttsService;
    }
    
    /**
     * 설정에 따라 AI 게이트웨이를 선택합니다.
     */
    private InterviewerAiGateway getAiGateway() {
        return "gemini".equalsIgnoreCase(aiProvider) ? geminiGateway : openAiGateway;
    }

    @Transactional
    public StartSessionResponseDto start(Long memberId, StartSessionRequestDto req, boolean withAudio) throws Exception {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("member not found: " + memberId));

        String displayName = (req.displayName() != null && !req.displayName().isBlank())
                ? req.displayName()
                : member.getMemberBase().getNickname();

        // documentId가 있으면 업로드된 문서에서 프로필 스냅샷 생성
        String profileSnapshotJson = "{}";
        if (req.documentId() != null) {
            UploadedDocument document = uploadedDocumentRepository.findById(req.documentId())
                    .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + req.documentId()));
            
            // 문서 소유자 확인
            if (!document.getMember().getId().equals(memberId)) {
                throw new AccessDeniedException("해당 문서에 접근할 수 없습니다.");
            }
            
            // 문서 내용으로 프로필 스냅샷 생성
            Map<String, Object> profileSnapshot = new HashMap<>();
            profileSnapshot.put("role", req.jobRole().name());
            profileSnapshot.put("documentContent", document.getExtractedContent());
            profileSnapshot.put("fileName", document.getFileName());
            profileSnapshotJson = om.writeValueAsString(profileSnapshot);
        }

        Map<String,Object> snap = profileSnapshotJson.equals("{}")
                ? Map.of()
                : om.readValue(profileSnapshotJson, Map.class);

        String role = req.jobRole().name();
        List<String> skills = om.convertValue(
                snap.getOrDefault("skills", List.of()),
                new TypeReference<List<String>>() {}
        );

        List<Map<String,Object>> candidates =
                questionCatalog.candidates(role, skills, 10);

        Map<String, Object> planMap = getAiGateway().generatePlan(role, profileSnapshotJson, candidates);
        String planJson = om.writeValueAsString(planMap);

        UUID sessionId = UUID.randomUUID();
        InterviewSession session = InterviewSession.create(
                sessionId,
                member,
                displayName,
                role,
                InterviewMode.TURN_TEXT,
                profileSnapshotJson,
                planJson
        );
        interviewSessionRepository.save(session);

        InterviewPlanDto plan = planParser.parse(planJson);
        String firstQuestion = plan.questions().get(0).text();
        String firstQuestionType = plan.questions().get(0).type();
        String greeting = getAiGateway().generateGreeting(displayName);
        int totalQuestions = plan.questions().size();

        // 첫 질문의 의도와 가이드를 pre-generated 데이터에서 가져오기
        PlanQuestionDto firstQuestionDto = plan.questions().get(0);
        String questionIntent = firstQuestionDto.intent();
        List<String> answerGuides = firstQuestionDto.guides();
        
        // 레거시 planJson 호환성을 위한 폴백 로직
        if (questionIntent == null || questionIntent.isBlank()) {
            log.info("[FALLBACK] 첫 질문 intent가 없어 폴백값 사용 (레거시 planJson)");
            questionIntent = "이 질문을 통해 지원자의 역량을 평가합니다.";
        }
        if (answerGuides == null || answerGuides.isEmpty()) {
            log.info("[FALLBACK] 첫 질문 guides가 없어 폴백값 사용 (레거시 planJson)");
            answerGuides = List.of(
                "구체적인 상황과 배경을 명확히 설명하고, 당시 직면한 과제를 구체적으로 제시하세요.",
                "문제 해결을 위해 취한 행동과 접근 방법을 단계별로 설명하고, 기술적 근거를 포함하세요.",
                "최종 결과와 비즈니스 임팩트를 수치나 구체적 사례로 보여주고, 얻은 인사이트를 언급하세요."
            );
        }

        // withAudio면 첫 질문(필요시 greeting 포함) 음성 생성
        TtsPayloadDto tts = null;
        if (withAudio && firstQuestion != null && !firstQuestion.isBlank()) {
            try {
                String text = greeting + " " + firstQuestion;
                tts = ttsService.synthesize(text, defaultTtsFormat);
            } catch (Exception e) {
                log.warn("[TTS] start synthesize failed: {}", e.getMessage());
            }
        }

        return new StartSessionResponseDto(sessionId, greeting, firstQuestion, questionIntent, answerGuides, totalQuestions, 0, tts);
    }

    @Transactional
    public NextTurnResponseDto nextTurn(TurnRequestDto req, Long memberId, boolean withAudio) throws Exception {
        long methodStart = System.nanoTime();
        log.info("[PERF] nextTurn 메서드 시작 - session: {}, question: {}, withAudio: {}", 
                req.sessionId(), req.questionIndex(), withAudio);
        
        InterviewSession session = interviewSessionRepository.findById(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + req.sessionId()));

        if (!session.getMember().getId().equals(memberId)) {
            throw new AccessDeniedException("forbidden: not your session");
        }
        if (session.getStatus() == InterviewStatus.FINISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session already finished");
        }
        if (req.questionIndex() != session.getQuestionIndex()) {
            throw new IllegalStateException(
                    "out-of-order turn: expected " + session.getQuestionIndex() + ", got " + req.questionIndex());
        }
        if (interviewAnswerRepository.existsBySession_IdAndQuestionIndex(req.sessionId(), req.questionIndex())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already answered this question");
        }

        PlanQuestionDto q = planParser.getQuestionByIndex(session.getPlanJson(), req.questionIndex());
        String questionType = q.type();
        String questionText = q.text();

        String prevResponseId = session.getLastResponseId();

        long t0 = System.nanoTime();
        AiTurnFeedbackDto feedback = getAiGateway().nextTurn(
                session.getPlanJson(),
                req.questionIndex(),
                req.transcript(),
                "{}",                        // 요약은 옵션
                prevResponseId
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        String coachingTips = normalizeTips(feedback.coachingTips());
        String llmResponseId = feedback.responseId();

        log.info("[AI][turn] session={} qidx={} prev={} -> new={} ({} ms)",
                req.sessionId(), req.questionIndex(), prevResponseId, llmResponseId, elapsedMs);

        Map<String, Object> metricsPayload = new HashMap<>();
        metricsPayload.put("coachingTips", coachingTips);
        // scoreResult 제거 - 배치 평가로 이동
        String metricsJson = om.writeValueAsString(metricsPayload);

        InterviewAnswer answer = InterviewAnswer.create(
                session,
                req.questionIndex(),
                questionType,
                questionText,
                req.transcript(),
                metricsJson,
                llmResponseId,
                prevResponseId
        );

        try {
            interviewAnswerRepository.save(answer);
            log.info("[AI][save] session={} qidx={} saved llmId={} prevId={}",
                    req.sessionId(), req.questionIndex(), llmResponseId, prevResponseId);
        } catch (DataIntegrityViolationException e) {
            log.warn("[AI][conflict] session={} qidx={} already answered", req.sessionId(), req.questionIndex());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already answered this question");
        }

        if (llmResponseId != null && !llmResponseId.isBlank()) {
            session.updateLastResponseId(llmResponseId);
        }

        InterviewPlanDto plan = planParser.parse(session.getPlanJson());
        int nextIndex = req.questionIndex() + 1;
        boolean done = nextIndex >= plan.questions().size();
        if (done) {
            session.finishNow(java.time.OffsetDateTime.now());
        } else {
            session.advance();
        }
        String nextQuestion = done ? null : plan.questions().get(nextIndex).text();
        
        // pre-generated 데이터에서 질문 의도/가이드 가져오기 + TTS 병렬 처리
        String questionIntent = null;
        List<String> answerGuides = null;
        TtsPayloadDto tts = null;
        
        if (!done && nextQuestion != null) {
            long optimizedStart = System.nanoTime();
            
            // pre-generated 데이터에서 질문 의도/가이드 직접 가져오기
            PlanQuestionDto nextQuestionDto = plan.questions().get(nextIndex);
            questionIntent = nextQuestionDto.intent();
            answerGuides = nextQuestionDto.guides();
            
            // 레거시 planJson 호환성을 위한 폴백 로직
            if (questionIntent == null || questionIntent.isBlank()) {
                log.info("[FALLBACK] 다음 질문 intent가 없어 폴백값 사용 (레거시 planJson)");
                questionIntent = "이 질문을 통해 지원자의 역량을 평가합니다.";
            }
            if (answerGuides == null || answerGuides.isEmpty()) {
                log.info("[FALLBACK] 다음 질문 guides가 없어 폴백값 사용 (레거시 planJson)");
                answerGuides = List.of(
                    "구체적인 경험을 바탕으로 답변해주세요.",
                    "STAR 방식(상황, 과제, 행동, 결과)을 활용하면 좋습니다.",
                    "기술적 근거와 함께 설명해주세요."
                );
            }

            // TTS 합성 처리 (withAudio일 때만)
            if (withAudio && !nextQuestion.isBlank()) {
                try {
                    long ttsStart = System.nanoTime();
                    log.info("[TTS] starting synthesize for question: '{}'", nextQuestion);
                    tts = ttsService.synthesize(nextQuestion, defaultTtsFormat);
                    long ttsMs = (System.nanoTime() - ttsStart) / 1_000_000;
                    log.info("[TTS] completed synthesize in {} ms", ttsMs);
                } catch (Exception e) {
                    log.warn("[TTS] synthesize failed: {}", e.getMessage());
                    tts = null;
                }
            }

            long optimizedMs = (System.nanoTime() - optimizedStart) / 1_000_000;
            log.info("[OPTIMIZED] 질문 데이터 준비 완료: {} ms (pre-generated 방식)", optimizedMs);
        }

        // 전체 성능 측정 및 로깅
        long methodMs = (System.nanoTime() - methodStart) / 1_000_000;
        log.info("[PERF] nextTurn 메서드 완료 - 전체 실행 시간: {} ms (AI: {} ms, 최적화됨)", 
                methodMs, elapsedMs);
                
        return new NextTurnResponseDto(nextQuestion, questionIntent, answerGuides, coachingTips, nextIndex, done, tts);
    }

    @Transactional(readOnly = true)
    public FinalizeReportResponseDto finalizeReport(UUID sessionId, Long memberId) throws Exception {
        InterviewSession session = interviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        if (!session.getMember().getId().equals(memberId)) {
            throw new AccessDeniedException("forbidden: not your session");
        }

        List<InterviewAnswer> answers = interviewAnswerRepository
                .findBySession_IdOrderByQuestionIndexAsc(sessionId);

        // 1) 배치 평가: AI를 통해 전체 면접 세션 종합 점수 계산
        Map<String, Integer> subscores;
        try {
            log.info("[BATCH] 배치 평가 시작 - session: {}, 답변 수: {}", sessionId, answers.size());
            subscores = generateBatchEvaluation(answers, session);
            log.info("[BATCH] 배치 평가 완료 - 점수: {}", subscores);
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

        double overall = 0.0;
        for (Integer v : subscores.values()) overall += v;
        double overallRounded = subscores.isEmpty() ? 0.0 : Math.round((overall / subscores.size()) * 10.0) / 10.0;

        // 4) 요약 팩트(facts) 강화: 상/하위 지표 키와 Q/A 발췌 포함
        //    - 상위 2개, 하위 2개 키를 뽑아 요약이 점수와 일치하도록 가이드
        Comparator<Map.Entry<String, Integer>> byVal = Map.Entry.comparingByValue();
        List<String> topStrengthKeys = subscores.entrySet().stream()
                .sorted(byVal.reversed()).limit(2).map(Map.Entry::getKey).toList();
        List<String> areasToImproveKeys = subscores.entrySet().stream()
                .sorted(byVal).limit(2).map(Map.Entry::getKey).toList();

        // Q/A 전부 포함하되, transcript는 1~2줄 발췌(너무 길면 220자까지만)
        List<Map<String, Object>> qaList = new ArrayList<>();
        for (InterviewAnswer a : answers) {
            Map<String, Object> qa = new HashMap<>();
            qa.put("qidx", a.getQuestionIndex());
            qa.put("questionType", a.getQuestionType());
            qa.put("questionText", a.getQuestionText());

            String tr = a.getTranscript();
            if (tr != null) {
                String excerpt = tr.replaceAll("\\s+", " ").trim();
                if (excerpt.length() > 220) excerpt = excerpt.substring(0, 220) + "…";
                qa.put("transcriptExcerpt", excerpt);
            }

            Map<?, ?> metrics = om.readValue(a.getMetricsJson(), Map.class);
            Object tipsRaw = metrics.get("coachingTips");
            if (tipsRaw != null) {
                String norm = normalizeTips(String.valueOf(tipsRaw));
                qa.put("coachingTips", norm);
            }

            qaList.add(qa);
        }

        Map<String, Object> facts = new HashMap<>();
        facts.put("sessionId", session.getId().toString());
        facts.put("role", session.getRole());
        facts.put("displayName", session.getDisplayName());
        facts.put("answeredCount", answers.size());
        facts.put("questionPointer", session.getQuestionIndex());
        facts.put("overallScore", overallRounded);
        facts.put("subscores", subscores);
        facts.put("topStrengthKeys", topStrengthKeys);
        facts.put("areasToImproveKeys", areasToImproveKeys);
        facts.put("qa", qaList);

        String sessionJson = om.writeValueAsString(facts);     // ← LLM에 전달할 facts
        String previousResponseId = session.getLastResponseId(); // ← 체인 anchor

        // 5) OpenAI로 요약 3문장 생성 (체인 + facts). 실패 시 폴백.
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

        String strengths = String.valueOf(sum.getOrDefault("strengths", ""));
        String areas = String.valueOf(sum.getOrDefault("areasToImprove", ""));
        String next = String.valueOf(
                sum.containsKey("nextSteps") ? sum.get("nextSteps") : sum.getOrDefault("recommendedQuestions", "")
        );

        return new FinalizeReportResponseDto(overallRounded, subscores, strengths, areas, next);
    }

    @Transactional
    public void upsertContext(UpsertContextRequestDto req, Long memberId) throws Exception {
        InterviewSession session = interviewSessionRepository.findById(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("session not found"));
        if (!session.getMember().getId().equals(memberId)) {
            throw new AccessDeniedException("forbidden: not your session");
        }

        // 기존 snapshot JSON 불러오고 병합
        Map<String, Object> snap = (session.getProfileSnapshotJson() == null || session.getProfileSnapshotJson().isBlank())
                ? new HashMap<>()
                : om.readValue(session.getProfileSnapshotJson(), Map.class);

        if (req.role() != null && !req.role().isBlank()) snap.put("role", req.role());
        if (req.skills() != null) snap.put("skills", req.skills());
        if (req.docs() != null) {
            // 너무 길면 LLM 안전을 위해 8~10KB 정도로 컷
            List<Map<String,Object>> ds = new ArrayList<>();
            for (UpsertContextRequestDto.DocItem d : req.docs()) {
                String t = d.text() == null ? "" : d.text().trim();
                if (t.length() > 10_000) t = t.substring(0, 10_000) + "…";
                ds.add(Map.of("title", d.title(), "text", t));
            }
            snap.put("docs", ds);
        }

        session.updateProfileSnapshotJson(om.writeValueAsString(snap));
    }

    /**
     * 배치 평가: 전체 면접 세션을 종합하여 5지표 점수 계산
     */
    private Map<String, Integer> generateBatchEvaluation(List<InterviewAnswer> answers, InterviewSession session) throws Exception {
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("답변이 없어 배치 평가를 수행할 수 없습니다");
        }

        // 면접 세션 요약 데이터 생성
        StringBuilder evaluationData = new StringBuilder();
        evaluationData.append(String.format("면접 역할: %s\n", session.getRole()));
        evaluationData.append(String.format("답변 수: %d개\n\n", answers.size()));

        for (InterviewAnswer answer : answers) {
            evaluationData.append(String.format("Q%d [%s]: %s\n", 
                answer.getQuestionIndex() + 1, 
                answer.getQuestionType(), 
                answer.getQuestionText()));
            
            String transcript = answer.getTranscript();
            if (transcript != null && !transcript.isBlank()) {
                // 긴 답변은 요약하여 전달 (배치 평가의 정확성을 위해)
                String summary = transcript.length() > 500 
                    ? transcript.substring(0, 500) + "..." 
                    : transcript;
                evaluationData.append(String.format("A%d: %s\n\n", 
                    answer.getQuestionIndex() + 1, summary));
            } else {
                evaluationData.append(String.format("A%d: [답변 없음]\n\n", 
                    answer.getQuestionIndex() + 1));
            }
        }

        // AI를 통한 종합 점수 계산
        Map<String, Object> batchResult = getAiGateway().generateBatchEvaluation(
            evaluationData.toString(), 
            session.getRole(),
            session.getLastResponseId()
        );

        // 결과를 Integer Map으로 변환
        Map<String, Integer> scores = new HashMap<>();
        Object scoresRaw = batchResult.get("scores");
        if (scoresRaw instanceof Map) {
            Map<?, ?> scoresMap = (Map<?, ?>) scoresRaw;
            for (Map.Entry<?, ?> entry : scoresMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                Integer score = null;
                
                if (value instanceof Number) {
                    score = ((Number) value).intValue();
                } else if (value instanceof String) {
                    try {
                        score = Integer.parseInt((String) value);
                    } catch (NumberFormatException e) {
                        log.warn("[BATCH] 점수 파싱 실패: {} = {}", key, value);
                    }
                }
                
                if (score != null) {
                    // 0-100 범위로 제한
                    score = Math.max(0, Math.min(100, score));
                    scores.put(key, score);
                }
            }
        }

        // 필수 지표가 없는 경우 기본값 설정
        String[] requiredMetrics = {"clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause"};
        for (String metric : requiredMetrics) {
            if (!scores.containsKey(metric)) {
                scores.put(metric, 45); // 기본값: 보통 수준
                log.warn("[BATCH] 누락된 지표 {} 기본값 설정: 45점", metric);
            }
        }

        return scores;
    }

    /**
     * AI 가이드 생성을 재시도 로직과 함께 수행
     */
    private Map<String, Object> generateQuestionIntentAndGuidesWithRetry(String questionType, String questionText, String role) throws Exception {
        Exception lastException = null;
        
        // 최대 2번 시도
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                log.debug("[AI] 질문 의도/가이드 생성 시도 {}/2", attempt);
                return getAiGateway().generateQuestionIntentAndGuides(questionType, questionText, role);
            } catch (Exception e) {
                lastException = e;
                log.warn("[AI] 질문 의도/가이드 생성 {}차 시도 실패: {}", attempt, e.getMessage());
                
                if (attempt < 2) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("재시도 중 인터럽트", ie);
                    }
                }
            }
        }
        
        throw new RuntimeException("AI 가이드 생성 2차 시도 모두 실패", lastException);
    }

    /**
     * 100점 만점 평균 점수 계산 방식
     * 각 지표별로 모든 턴의 점수를 평균내어 최종 점수 산정
     */
    private Map<String, Integer> calculateAverageScores(Map<String, List<Integer>> scoreHistory) {
        Map<String, Integer> subscores = new HashMap<>();
        
        for (Map.Entry<String, List<Integer>> entry : scoreHistory.entrySet()) {
            String metric = entry.getKey();
            List<Integer> scores = entry.getValue();
            
            if (scores.isEmpty()) {
                subscores.put(metric, 20); // 기본값 20점 (기본선)
                continue;
            }
            
            double average = scores.stream().mapToInt(Integer::intValue).average().orElse(20.0);
            int finalScore = (int) Math.round(average);
            
            finalScore = Math.max(0, Math.min(100, finalScore));
            
            subscores.put(metric, finalScore);
            
            log.debug("[점수계산] {} = {} (평균 {:.1f}, 턴별점수: {})", 
                metric, finalScore, average, scores);
        }
        
        // 기본 지표들이 없는 경우 기본값 설정
        String[] defaultMetrics = {"clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause"};
        for (String metric : defaultMetrics) {
            if (!subscores.containsKey(metric)) {
                subscores.put(metric, 20); // 기본값 20점 (기본선)
            }
        }
        
        return subscores;
    }

    /**
     * 개선된 누적 점수 산정 방식 (기존 방식 - 호환성 유지)
     * 기본 점수 3점에서 시작하여 scoreDelta를 가중치와 함께 누적 적용
     */
    private Map<String, Integer> calculateImprovedScores(Map<String, Integer> totals, int questionCount) {
        Map<String, Integer> subscores = new HashMap<>();
        
        // 기본 점수 설정 (평균적인 면접자 수준)
        double baseScore = 3.0;
        
        // 가중치 설정 (scoreDelta의 영향력 조절)
        double weightFactor = 0.3;
        
        // 질문 수에 따른 완화 계수 (질문이 많을수록 점수 변화를 완화)
        double stabilityFactor = Math.max(1.0, Math.sqrt(questionCount));
        
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            String metric = entry.getKey();
            int totalDelta = entry.getValue();
            
            // 누적 점수 계산
            // 기본점수 + (총변화량 * 가중치 / 안정화계수)
            double cumulativeScore = baseScore + (totalDelta * weightFactor / stabilityFactor);
            
            // 1-5점 범위로 조정
            int finalScore = (int) Math.round(Math.max(1.0, Math.min(5.0, cumulativeScore)));
            
            subscores.put(metric, finalScore);
            
            log.debug("[점수계산] {} = {} (기본{}+델타{}*{}÷{} = {}→{})", 
                metric, finalScore, baseScore, totalDelta, weightFactor, 
                String.format("%.1f", stabilityFactor), 
                String.format("%.2f", cumulativeScore), finalScore);
        }
        
        // 기본 지표들이 없는 경우 기본값 설정
        String[] defaultMetrics = {"clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause"};
        for (String metric : defaultMetrics) {
            if (!subscores.containsKey(metric)) {
                subscores.put(metric, (int) Math.round(baseScore));
            }
        }
        
        return subscores;
    }

    private String normalizeTips(String tipsRaw) {
        if (tipsRaw == null) return "";
        String s = tipsRaw.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            try {
                JsonNode n = om.readTree(s);
                if (n.has("coachingTips")) return n.path("coachingTips").asText(s);
            } catch (Exception ignore) {}
        }
        return s;
    }
}
