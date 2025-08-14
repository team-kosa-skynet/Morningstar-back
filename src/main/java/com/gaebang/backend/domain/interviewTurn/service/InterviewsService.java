package com.gaebang.backend.domain.interviewTurn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.config.QuestionCatalog;
import com.gaebang.backend.domain.interviewTurn.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.InterviewPlanDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.UpsertContextRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.FinalizeReportResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.NextTurnResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.StartSessionResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.TtsPayloadDto;
import com.gaebang.backend.domain.interviewTurn.entity.InterviewsAnswer;
import com.gaebang.backend.domain.interviewTurn.entity.InterviewsSession;
import com.gaebang.backend.domain.interviewTurn.entity.UploadedDocument;
import com.gaebang.backend.domain.interviewTurn.enums.InterviewMode;
import com.gaebang.backend.domain.interviewTurn.enums.InterviewStatus;
import com.gaebang.backend.domain.interviewTurn.llm.InterviewerAiGateway;
import com.gaebang.backend.domain.interviewTurn.repository.InterviewsAnswerRepository;
import com.gaebang.backend.domain.interviewTurn.repository.InterviewsSessionRepository;
import com.gaebang.backend.domain.interviewTurn.repository.UploadedDocumentRepository;
import com.gaebang.backend.domain.interviewTurn.util.PlanParser;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
public class InterviewsService {

    private final InterviewsSessionRepository interviewsSessionRepository;
    private final InterviewsAnswerRepository interviewsAnswerRepository;
    private final MemberRepository memberRepository;
    private final UploadedDocumentRepository uploadedDocumentRepository;
    private final InterviewerAiGateway ai;
    private final ObjectMapper om;
    private final PlanParser planParser;
    private final QuestionCatalog questionCatalog;
    private final TtsService ttsService;

    public InterviewsService(InterviewsSessionRepository interviewsSessionRepository,
                             InterviewerAiGateway ai,
                             ObjectMapper objectMapper,
                             InterviewsAnswerRepository interviewsAnswerRepository,
                             MemberRepository memberRepository,
                             UploadedDocumentRepository uploadedDocumentRepository,
                             PlanParser planParser, QuestionCatalog questionCatalog,
                             TtsService ttsService) {
        this.interviewsSessionRepository = interviewsSessionRepository;
        this.interviewsAnswerRepository = interviewsAnswerRepository;
        this.memberRepository = memberRepository;
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.ai = ai;
        this.om = objectMapper;
        this.planParser = planParser;
        this.questionCatalog = questionCatalog;
        this.ttsService = ttsService;
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

        Map<String, Object> planMap = ai.generatePlan(role, profileSnapshotJson, candidates);
        String planJson = om.writeValueAsString(planMap);

        UUID sessionId = UUID.randomUUID();
        InterviewsSession session = InterviewsSession.create(
                sessionId,
                member,
                displayName,
                role,
                InterviewMode.TURN_TEXT,
                profileSnapshotJson,
                planJson
        );
        interviewsSessionRepository.save(session);

        InterviewPlanDto plan = planParser.parse(planJson);
        String firstQuestion = plan.questions().get(0).text();
        String greeting = ai.generateGreeting(displayName);

        // withAudio면 첫 질문(필요시 greeting 포함) 음성 생성
        TtsPayloadDto tts = null;
        if (withAudio && firstQuestion != null && !firstQuestion.isBlank()) {
            try {
                // 필요하면 greeting까지 합성: String text = greeting + " " + firstQuestion;
                String text = greeting + " " + firstQuestion;
                tts = ttsService.synthesize(text, "wav");
            } catch (Exception e) {
                log.warn("[TTS] start synthesize failed: {}", e.getMessage());
            }
        }

        return new StartSessionResponseDto(sessionId, greeting, firstQuestion, tts);
    }

    @Transactional
    public NextTurnResponseDto nextTurn(TurnRequestDto req, Long memberId, boolean withAudio) throws Exception {
        InterviewsSession session = interviewsSessionRepository.findById(req.sessionId())
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
        if (interviewsAnswerRepository.existsBySession_IdAndQuestionIndex(req.sessionId(), req.questionIndex())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already answered this question");
        }

        PlanQuestionDto q = planParser.getQuestionByIndex(session.getPlanJson(), req.questionIndex());
        String questionType = q.type();
        String questionText = q.text();

        String prevResponseId = session.getLastResponseId();

        long t0 = System.nanoTime();
        AiTurnFeedbackDto feedback = ai.nextTurn(
                session.getPlanJson(),
                req.questionIndex(),
                req.transcript(),
                "{}",                        // 요약은 옵션
                prevResponseId
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        Map<String, Integer> scoreDelta = feedback.scoreDelta();
        String coachingTips = normalizeTips(feedback.coachingTips());
        String llmResponseId = feedback.responseId();

        log.info("[AI][turn] session={} qidx={} prev={} -> new={} ({} ms)",
                req.sessionId(), req.questionIndex(), prevResponseId, llmResponseId, elapsedMs);

        Map<String, Object> metricsPayload = new HashMap<>();
        metricsPayload.put("coachingTips", coachingTips);
        metricsPayload.put("scoreDelta", scoreDelta);
        String metricsJson = om.writeValueAsString(metricsPayload);

        InterviewsAnswer answer = InterviewsAnswer.create(
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
            interviewsAnswerRepository.save(answer);
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

        // withAudio면 다음 질문 합성(종료가 아닐 때만)
        TtsPayloadDto tts = null;
        if (!done && withAudio && nextQuestion != null && !nextQuestion.isBlank()) {
            try {
                log.info("[TTS] starting synthesize for question: '{}'", nextQuestion);
                long ttsStart = System.nanoTime();
                tts = ttsService.synthesize(nextQuestion, "wav");
                long ttsMs = (System.nanoTime() - ttsStart) / 1_000_000;
                log.info("[TTS] completed synthesize in {} ms", ttsMs);
            } catch (Exception e) {
                log.warn("[TTS] turn synthesize failed: {}", e.getMessage());
            }
        }

        return new NextTurnResponseDto(nextQuestion, coachingTips, scoreDelta, done, tts);
    }

    @Transactional(readOnly = true)
    public FinalizeReportResponseDto finalizeReport(UUID sessionId, Long memberId) throws Exception {
        InterviewsSession session = interviewsSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        if (!session.getMember().getId().equals(memberId)) {
            throw new AccessDeniedException("forbidden: not your session");
        }

        List<InterviewsAnswer> answers = interviewsAnswerRepository
                .findBySession_IdOrderByQuestionIndexAsc(sessionId);

        // 1) 턴별 metricsJson에서 scoreDelta 합산
        Map<String, Integer> totals = new HashMap<>();
        for (InterviewsAnswer a : answers) {
            Map<?, ?> metrics = om.readValue(a.getMetricsJson(), Map.class);
            Object raw = metrics.get("scoreDelta");
            Map<String, Integer> delta =
                    (raw instanceof Map)
                            ? om.convertValue(raw, om.getTypeFactory().constructMapType(Map.class, String.class, Integer.class))
                            : Collections.emptyMap();
            for (Map.Entry<String, Integer> e : delta.entrySet()) {
                totals.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }

        // 2) 1..5 스케일로 정규화
        int n = Math.max(1, answers.size());
        Map<String, Integer> subscores = new HashMap<>();
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            double avg = (double) e.getValue() / n;             // -2..+3 예상
            double scaled = ((avg + 2.0) / 5.0) * 4.0 + 1.0;    // 1..5 맵핑
            int rounded = (int) Math.round(Math.max(1.0, Math.min(5.0, scaled)));
            subscores.put(e.getKey(), rounded);
        }

        // 3) overallScore = 서브스코어 평균(소수 1자리)
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
        for (InterviewsAnswer a : answers) {
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
            sum = ai.finalizeReport(sessionJson, previousResponseId);
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
        InterviewsSession session = interviewsSessionRepository.findById(req.sessionId())
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
