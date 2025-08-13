package com.gaebang.backend.domain.interviewTurn.llm;

import com.gaebang.backend.domain.interviewTurn.dto.internal.AiTurnFeedbackDto;
import com.gaebang.backend.domain.interviewTurn.dto.internal.PlanQuestionDto;
import com.gaebang.backend.domain.interviewTurn.util.PlanParser;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubInterviewerAiGateway implements InterviewerAiGateway {

    private final PlanParser planParser;

    public StubInterviewerAiGateway(PlanParser planParser) {
        this.planParser = planParser;
    }

    @PostConstruct
    void log() { System.out.println("[AI] Using StubInterviewerAiGateway"); }

    @Override
    public String generateGreeting(String displayName) {
        return displayName + "님, 안녕하세요. 이번 면접을 진행할 면접관입니다. 만나서 반갑습니다. 질문을 시작하겠습니다.";
    }

    @Override
    public Map<String, Object> generatePlan(String role, String snapshot, List<Map<String, Object>> candidates) {
        // Stub 플랜 생성 로직...
        Map<String, Object> q0 = Map.of("idx", 0, "type", "BEHAVIORAL", "text", "자기소개를 간단히 해주세요.");
        Map<String, Object> q1 = Map.of("idx", 1, "type", "BEHAVIORAL", "text", "최근 협업 갈등을 STAR로 설명해 주세요.");
        Map<String, Object> q2 = Map.of("idx", 2, "type", "SYSTEM_DESIGN", "text", "피크 트래픽 10배에서 확장 전략은?");
        Map<String, Object> q3 = Map.of("idx", 3, "type", "DB_TX", "text", "트랜잭션 격리수준과 JPA의 관계를 설명해 주세요.");
        Map<String, Object> q4 = Map.of("idx", 4, "type", "PERF", "text", "N+1 문제를 어떻게 탐지/해결했나요?");
        Map<String, Object> q5 = Map.of("idx", 5, "type", "SECURITY_TEST", "text", "인증/인가 설계를 요약해 주세요.");
        Map<String, Object> q6 = Map.of("idx", 6, "type", "SYSTEM_DESIGN", "text", "캐시 일관성과 무효화 전략은?");
        Map<String, Object> q7 = Map.of("idx", 7, "type", "TROUBLESHOOT", "text", "최근 장애 RCA를 설명해 주세요.");
        Map<String, Object> q8 = Map.of("idx", 8, "type", "PERF", "text", "성능 측정과 검증 방법은?");
        Map<String, Object> q9 = Map.of("idx", 9, "type", "WRAPUP", "text", "질문 있으신가요? 마지막으로 강조하고 싶은 점은?");

        return Map.of("questions", List.of(q0, q1, q2, q3, q4, q5, q6, q7, q8, q9));
    }

    @Override
    public AiTurnFeedbackDto nextTurn(String planJson, int questionIndex,
                                      String transcript, String recentSummaryJson,
                                      String previousResponseId) throws Exception {
        PlanQuestionDto q = planParser.getQuestionByIndex(planJson, questionIndex);

        String coachingTips;
        Map<String, Integer> scoreDelta = new HashMap<>();

        if ("BEHAVIORAL".equalsIgnoreCase(q.type())) {
            coachingTips = "STAR 구조로 Situation-Task-Action-Result를 1~2문장씩 말해 주세요.";
            scoreDelta.put("structure_STAR", 2);
            scoreDelta.put("clarity", 2);
        } else if ("SYSTEM_DESIGN".equalsIgnoreCase(q.type())) {
            coachingTips = "트래픽 가정→병목→해결(캐시/큐/샤딩)→트레이드오프 순으로 구조화하세요.";
            scoreDelta.put("tech_depth", 3);
            scoreDelta.put("tradeoff", 2);
        } else if ("DB_TX".equalsIgnoreCase(q.type())) {
            coachingTips = "현상→격리수준 영향→JPA 동작→해결(락/패턴) 순서로 구체적으로.";
            scoreDelta.put("tech_depth", 3);
            scoreDelta.put("root_cause", 2);
        } else {
            coachingTips = "핵심 근거와 수치 지표를 한 번 이상 포함해 주세요.";
            scoreDelta.put("clarity", 2);
        }

        String fakeResponseId = "resp_stub_" + questionIndex + "_" + java.util.UUID.randomUUID();
        return new AiTurnFeedbackDto(coachingTips, scoreDelta, fakeResponseId); // ★ responseId=null
    }

    @Override
    public Map<String, Object> finalizeReport(String sessionJson, String previousResponseId) {
        return Map.of(
                "strengths", "원인분석이 빠름",
                "areasToImprove", "테스트 전략 구체화",
                "nextSteps", "장애 전파 차단 전략을 사례로 정리"  // ← recommendedQuestions 대신 nextSteps
        );
    }

}
