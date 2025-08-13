package com.gaebang.backend.domain.interviewTurn.service;

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
import com.gaebang.backend.domain.interviewTurn.entity.InterviewsAnswer;
import com.gaebang.backend.domain.interviewTurn.entity.InterviewsSession;
import com.gaebang.backend.domain.interviewTurn.enums.InterviewMode;
import com.gaebang.backend.domain.interviewTurn.enums.InterviewStatus;
import com.gaebang.backend.domain.interviewTurn.llm.InterviewerAiGateway;
import com.gaebang.backend.domain.interviewTurn.repository.InterviewsAnswerRepository;
import com.gaebang.backend.domain.interviewTurn.repository.InterviewsSessionRepository;
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
    private final InterviewerAiGateway ai;
    private final ObjectMapper om;
    private final PlanParser planParser;
    private final QuestionCatalog questionCatalog;

    public InterviewsService(InterviewsSessionRepository interviewsSessionRepository,
                             InterviewerAiGateway ai,
                             ObjectMapper objectMapper,
                             InterviewsAnswerRepository interviewsAnswerRepository,
                             MemberRepository memberRepository,
                             PlanParser planParser, QuestionCatalog questionCatalog) {
        this.interviewsSessionRepository = interviewsSessionRepository;
        this.interviewsAnswerRepository = interviewsAnswerRepository;
        this.memberRepository = memberRepository;
        this.ai = ai;
        this.om = objectMapper;
        this.planParser = planParser;
        this.questionCatalog = questionCatalog;
    }

    @Transactional
    public StartSessionResponseDto start(Long memberId, StartSessionRequestDto req) throws Exception {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("member not found: " + memberId));

        String displayName = (req.displayName() != null && !req.displayName().isBlank())
                ? req.displayName()
                : member.getMemberBase().getNickname();

        Map<String,Object> snap = (req.profileSnapshotJson()==null || req.profileSnapshotJson().isBlank())
                ? java.util.Map.of()
                : om.readValue(req.profileSnapshotJson(), Map.class);

        String role = req.role();
        java.util.List<String> skills = om.convertValue(
                snap.getOrDefault("skills", java.util.List.of()),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}
        );

        java.util.List<java.util.Map<String,Object>> candidates =
                questionCatalog.candidates(role, skills, 10);

        Map<String, Object> planMap = ai.generatePlan(role, req.profileSnapshotJson(), candidates);

        String planJson = om.writeValueAsString(planMap);

        UUID sessionId = UUID.randomUUID();
        InterviewsSession session = InterviewsSession.create(
                sessionId,
                member,
                displayName,
                req.role(),
                InterviewMode.TURN_TEXT,
                req.profileSnapshotJson(),
                planJson
        );
        interviewsSessionRepository.save(session);

        InterviewPlanDto plan = planParser.parse(planJson);
        String firstQuestion = plan.questions().get(0).text();
        String greeting = ai.generateGreeting(displayName);

        return new StartSessionResponseDto(sessionId, greeting, firstQuestion);
    }

    @Transactional
    public NextTurnResponseDto nextTurn(TurnRequestDto req, Long memberId) throws Exception {
        InterviewsSession session = interviewsSessionRepository.findById(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + req.sessionId()));

        // 세션 소유자 검증
        if (!session.getMember().getId().equals(memberId)) {
            throw new AccessDeniedException("forbidden: not your session");
        }

        // 이미 종료된 세션은 더 못 보냄
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

        // metricsJson 생성
        Map<String, Object> metricsPayload = new HashMap<>();
        metricsPayload.put("coachingTips", coachingTips);
        metricsPayload.put("scoreDelta", scoreDelta);
        String metricsJson = om.writeValueAsString(metricsPayload);

        // ③ 답변 엔티티 생성 시 prev/llmResponseId 함께 저장  ← 저장 위치는 여기!
        InterviewsAnswer answer = InterviewsAnswer.create(
                session,
                req.questionIndex(),
                questionType,
                questionText,
                req.transcript(),
                metricsJson,
                llmResponseId,              // 이번 턴의 응답 ID
                prevResponseId              // 직전 턴의 응답 ID
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

        // 이후 로직(플랜 파싱 → nextIndex/done 계산 → session.advance/finish → nextQuestion)은 그대로 유지
        InterviewPlanDto plan = planParser.parse(session.getPlanJson());
        int nextIndex = req.questionIndex() + 1;
        boolean done = nextIndex >= plan.questions().size();
        if (done) {
            session.finishNow(OffsetDateTime.now());
        } else {
            session.advance();
        }
        String nextQuestion = done ? null : plan.questions().get(nextIndex).text();

        return new NextTurnResponseDto(nextQuestion, coachingTips, scoreDelta, done);
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
                            : java.util.Collections.emptyMap();
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
        java.util.Comparator<Map.Entry<String, Integer>> byVal = Map.Entry.comparingByValue();
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
                ? new java.util.HashMap<>()
                : om.readValue(session.getProfileSnapshotJson(), Map.class);

        if (req.role() != null && !req.role().isBlank()) snap.put("role", req.role());
        if (req.skills() != null) snap.put("skills", req.skills());
        if (req.docs() != null) {
            // 너무 길면 LLM 안전을 위해 8~10KB 정도로 컷
            java.util.List<Map<String,Object>> ds = new java.util.ArrayList<>();
            for (UpsertContextRequestDto.DocItem d : req.docs()) {
                String t = d.text() == null ? "" : d.text().trim();
                if (t.length() > 10_000) t = t.substring(0, 10_000) + "…";
                ds.add(java.util.Map.of("title", d.title(), "text", t));
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
