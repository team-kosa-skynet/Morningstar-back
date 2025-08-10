package com.gaebang.backend.domain.interview.service;

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
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TurnService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final CandidateProfileRepository profileRepository;

    @Transactional
    public TurnResponseDto processTurn(TurnRequestDto req) {
        InterviewSession s = sessionRepository.findById(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("세션 없음: " + req.sessionId()));

        if (s.getStatus() != InterviewStatus.ACTIVE) {
            return new TurnResponseDto(s.getQuestionNo(), s.getStage(), true, "세션이 종료되었습니다.");
        }

        // 1) 현재 질문 번호/스테이지 기준으로 답변 저장
        InterviewAnswer answer = InterviewAnswer.of(
                s,
                s.getQuestionNo(),
                s.getStage(),
                safe(req.userTranscript()),
                req.durationMs()
        );
        answerRepository.save(answer);

        // 2) 후보 프로필(간단) 갱신: 키워드 누적 정도로 시작
        CandidateProfile profile = ensureProfile(s);
        String updatedJson = SimpleProfileUpdater.updateJson(profile.getSnapshotJson(), req.userTranscript());
        profile.updateJson(updatedJson);

        // 3) 다음 질문 번호 & 스테이지 결정 (간단 규칙)
        int nextNo = s.getQuestionNo() + 1;
        InterviewStage nextStage = decideNextStage(nextNo, s.getStage(), req.userTranscript());

        // 4) 종료 여부 판단
        boolean done = nextNo > s.getMaxQuestions();
        if (done) {
            s.finish();
            sessionRepository.save(s);
            return new TurnResponseDto(nextNo, s.getStage(), true, "면접을 종료합니다. 참여해 주셔서 감사합니다.");
        }

        // 5) 세션 상태 갱신
        s.moveToStage(nextStage);
        s.advanceRealtime(); // questionNo +1
        sessionRepository.save(s);

        // 6) (선택) RAG 요약: 지금 단계에서는 미구현/더미
        String ragSummary = ""; // TODO: 다음 스프린트에 붙이기

        // 7) 다음 질문 지시문 생성
        String instructions = buildNextInstructions(nextNo, nextStage, profile.getSnapshotJson(), ragSummary);

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
        // 아주 단순한 키워드 기반 라우팅(초기)
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

        // 스테이지별 프롬프트 템플릿(아주 간단 버전)
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
}
