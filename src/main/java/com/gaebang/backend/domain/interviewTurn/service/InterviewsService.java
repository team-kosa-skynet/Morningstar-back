package com.gaebang.backend.domain.interviewTurn.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.InterviewPlanDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.TurnRequestDto;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class InterviewsService {

    private final InterviewsSessionRepository interviewsSessionRepository;
    private final InterviewsAnswerRepository interviewsAnswerRepository;
    private final MemberRepository memberRepository;
    private final InterviewerAiGateway ai; // 현재는 Stub 구현 주입
    private final ObjectMapper om;
    private final PlanParser planParser;

    public InterviewsService(InterviewsSessionRepository interviewsSessionRepository,
                             InterviewerAiGateway ai,
                             ObjectMapper objectMapper,
                             InterviewsAnswerRepository interviewsAnswerRepository,
                             MemberRepository memberRepository,
                             PlanParser planParser) {
        this.interviewsSessionRepository = interviewsSessionRepository;
        this.interviewsAnswerRepository = interviewsAnswerRepository;
        this.memberRepository = memberRepository;
        this.ai = ai;
        this.om = objectMapper;
        this.planParser = planParser;
    }

    @Transactional
    public StartSessionResponseDto start(Long memberId, StartSessionRequestDto req) throws Exception {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("member not found: " + memberId));

        String displayName = (req.displayName() != null && !req.displayName().isBlank())
                ? req.displayName()
                : member.getMemberBase().getNickname();

        Map<String, Object> planMap = ai.generatePlan(req.role(), req.profileSnapshotJson(), java.util.List.of());
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

        // ✅ 세션 소유자 검증
        if (!session.getMember().getId().equals(memberId)) {
            throw new AccessDeniedException("forbidden: not your session");
        }

        // ✅ 이미 종료된 세션은 더 못 보냄
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

        AiTurnFeedbackDto feedback = ai.nextTurn(
                session.getPlanJson(),
                req.questionIndex(),
                req.transcript(),
                "{}",                        // 요약은 옵션
                session.getLastResponseId()  // ★ 이전 응답 id 전달
        );
        Map<String, Integer> scoreDelta = feedback.scoreDelta();
        String coachingTips = feedback.coachingTips();

        String llmResponseId = feedback.responseId();

        // ✅ 2) metricsJson 생성
        Map<String, Object> metricsPayload = new HashMap<>();
        metricsPayload.put("coachingTips", coachingTips);
        metricsPayload.put("scoreDelta", scoreDelta);
        String metricsJson = om.writeValueAsString(metricsPayload);

        // ✅ 3) 이제 답변 생성 시 metricsJson을 넣어 저장
        InterviewsAnswer answer = InterviewsAnswer.create(
                session,
                req.questionIndex(),
                questionType,
                questionText,
                req.transcript(),
                metricsJson,          // 이전의 "{}" 대신 실제 값
                llmResponseId
        );

        try {
            interviewsAnswerRepository.save(answer);
        } catch (DataIntegrityViolationException e) {
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

}
