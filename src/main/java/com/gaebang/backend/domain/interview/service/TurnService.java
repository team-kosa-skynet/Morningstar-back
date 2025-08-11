package com.gaebang.backend.domain.interview.service;

import com.gaebang.backend.domain.interview.dto.request.KickoffRequestDto;
import com.gaebang.backend.domain.interview.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interview.dto.response.TurnResponseDto;
import com.gaebang.backend.domain.interview.entity.CandidateProfile;
import com.gaebang.backend.domain.interview.entity.InterviewAnswer;
import com.gaebang.backend.domain.interview.entity.InterviewSession;
import com.gaebang.backend.domain.interview.enums.InterviewStage;
import com.gaebang.backend.domain.interview.enums.InterviewStatus;
import com.gaebang.backend.domain.interview.repository.CandidateProfileRepository;
import com.gaebang.backend.domain.interview.repository.InterviewAnswerRepository;
import com.gaebang.backend.domain.interview.repository.InterviewSessionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TurnService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final CandidateProfileRepository profileRepository;

    @Transactional
    public TurnResponseDto processTurn(TurnRequestDto req) {
        log.info("TURN req sessionId={}", req.sessionId());

        InterviewSession s = sessionRepository.findById(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("세션 없음: " + req.sessionId()));

        // START 이벤트: 첫 질문만 생성해서 반환 (DB 진행도 변경 없음)
        if ("start".equalsIgnoreCase(req.systemEvent())) {
            s.ensureStartState(); // 레거시 방어
            if (req.jobPosition() != null && !req.jobPosition().isBlank()) {
                s.updateJobPosition(req.jobPosition().trim());
                sessionRepository.save(s);
            }
            String first = buildFirstInstructions(s.getQuestionNo(), s.getStage(), s.getJobPosition());
            return new TurnResponseDto(s.getQuestionNo(), s.getStage(), false, first);
        }

        // 이후 일반 턴 처리
        if (s.getStatus() != InterviewStatus.ACTIVE) {
            return new TurnResponseDto(s.getQuestionNo(), s.getStage(), true, "세션이 종료되었습니다.");
        }

        // 답변 저장
        InterviewAnswer answer = InterviewAnswer.of(
                s,
                s.getQuestionNo(),
                s.getStage(),
                safe(req.userTranscript()),
                req.durationMs()
        );
        answerRepository.save(answer);

        // 프로필 스냅샷 갱신
        CandidateProfile profile = ensureProfile(s);
        String updatedJson = SimpleProfileUpdater.updateJson(profile.getSnapshotJson(), req.userTranscript());
        profile.updateJson(updatedJson);

        // 다음 질문/스테이지 결정
        int nextNo = s.getQuestionNo() + 1;
        InterviewStage nextStage = decideNextStage(nextNo, s.getStage(), req.userTranscript());

        // 종료 판정
        boolean done = nextNo > s.getMaxQuestions();
        if (done) {
            s.finish();
            sessionRepository.save(s);
            return new TurnResponseDto(nextNo, s.getStage(), true, "면접을 종료합니다. 참여해 주셔서 감사합니다.");
        }

        // 진행도 반영
        s.moveToStage(nextStage);
        s.advanceRealtime(); // questionNo += 1
        sessionRepository.save(s);

        // 다음 질문 프롬프트
        String instructions = buildNextInstructions(nextNo, nextStage, profile.getSnapshotJson(), "");
        return new TurnResponseDto(nextNo, nextStage, false, instructions);
    }

    private static String safe(String s) {
        return (s == null) ? "" : s.trim();
    }

    private CandidateProfile ensureProfile(InterviewSession s) {
        Optional<CandidateProfile> found = profileRepository.findBySession(s);
        if (found.isPresent()) return found.get();
        CandidateProfile created = new CandidateProfile(s, "{\"keywords\":[]}");
        return profileRepository.save(created);
    }

    private InterviewStage decideNextStage(int nextNo, InterviewStage curStage, String transcript) {
        if (nextNo <= 3) {
            return InterviewStage.BASIC; // 1~3: 기본 파악
        }
        // 간단 키워드 라우팅
        String t = transcript == null ? "" : transcript.toLowerCase();
        if (t.contains("프로젝트") || t.contains("트래픽") || t.contains("결제") || t.contains("redis")) {
            return InterviewStage.PROJECT;
        }
        if (t.contains("문제") || t.contains("장애") || t.contains("사고") || t.contains("복구")) {
            return InterviewStage.PROBLEM_SOLVING;
        }
        return InterviewStage.DEEP_DIVE;
    }

    private String buildNextInstructions(int nextNo, InterviewStage stage, String profileJson, String ragSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 한국어 면접관입니다. 한 번에 하나의 질문만 하고, ")
                .append("지원자가 20~30초 내로 답하도록 유도하세요. 허구 정보는 금지합니다.\n\n");

        sb.append("[후보 프로필 스냅샷]\n").append(profileJson).append("\n\n");

        if (ragSummary != null && !ragSummary.isBlank()) {
            sb.append("[참고 문맥 요약]\n").append(ragSummary).append("\n\n");
        }

        sb.append("다음은 ").append(nextNo).append("번째 질문입니다. (스테이지: ").append(stage).append(")\n");

        switch (stage) {
            case BASIC -> sb.append("지원자의 기본 배경을 더 깊게 이해할 수 있는 한 문장을 물어보세요.");
            case PROJECT -> sb.append("최근 프로젝트에서 구체적 설계/트레이드오프를 묻는 한 문장을 물어보세요.");
            case DEEP_DIVE -> sb.append("기술 선택 이유, 성능·확장성·일관성 등 시스템적 질문을 한 문장으로 물어보세요.");
            case PROBLEM_SOLVING -> sb.append("문제 해결/장애 대응/디버깅 경험을 파고드는 한 문장을 물어보세요.");
            case MOTIVATION -> sb.append("동기/커리어 목표/문화 적합성을 확인하는 한 문장을 물어보세요.");
            case ICEBREAK -> sb.append("가볍고 편안한 시작 질문을 한 문장으로 물어보세요.");
        }
        return sb.toString();
    }

    /** (선택) 별도 /kickoff 엔드포인트를 유지하고 싶을 때 */
    @Transactional
    public TurnResponseDto kickoff(KickoffRequestDto req) {
        InterviewSession s = sessionRepository.findById(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("세션 없음: " + req.sessionId()));

        if (s.getStatus() != InterviewStatus.ACTIVE) {
            return new TurnResponseDto(s.getQuestionNo(), s.getStage(), true, "세션이 종료되었습니다.");
        }

        // ✅ 일반 setter 없이 시작 상태 보정
        s.ensureStartState();
        sessionRepository.save(s);

        // 포지션이 들어오면 업데이트
        if (req.jobPosition() != null && !req.jobPosition().isBlank()) {
            s.updateJobPosition(req.jobPosition().trim());
            sessionRepository.save(s);
        }

        String instructions = buildFirstInstructions(s.getQuestionNo(), s.getStage(), s.getJobPosition());
        return new TurnResponseDto(s.getQuestionNo(), s.getStage(), false, instructions);
    }

    private String buildFirstInstructions(int no, InterviewStage stage, String jobPosition) {
        String pos = (jobPosition == null || jobPosition.isBlank()) ? "지원하신 포지션" : jobPosition.trim();
        return pos + " 면접을 시작합니다. 자기소개를 20초 내로 부탁드립니다.";
    }
}
