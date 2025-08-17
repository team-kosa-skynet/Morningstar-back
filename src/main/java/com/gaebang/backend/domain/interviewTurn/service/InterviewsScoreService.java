package com.gaebang.backend.domain.interviewTurn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interviewTurn.dto.response.ScoresDto;
import com.gaebang.backend.domain.interviewTurn.entity.InterviewsAnswer;
import com.gaebang.backend.domain.interviewTurn.repository.InterviewsAnswerRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class InterviewsScoreService {

    private final InterviewsAnswerRepository answers;
    private final ObjectMapper om;

    // 집계 대상 키
    private static final List<String> KEYS = List.of(
            "clarity", "structure_STAR", "tech_depth", "tradeoff", "root_cause"
    );

    public InterviewsScoreService(InterviewsAnswerRepository answers, ObjectMapper om) {
        this.answers = answers;
        this.om = om;
    }

    public ScoresDto computeScores(UUID sessionId) {
        List<InterviewsAnswer> list = answers.findBySession_IdOrderByQuestionIndexAsc(sessionId);

        Map<String, Integer> sum = new HashMap<>();
        Map<String, Integer> cnt = new HashMap<>();
        int totalSum = 0;
        int totalCnt = 0;

        for (InterviewsAnswer a : list) {
            JsonNode root = safeRead(a.getMetricsJson());
            JsonNode delta = root.path("scoreDelta");

            // 과거 호환: coachingTips에 JSON 문자열이 들어간 경우 추출
            if (!delta.isObject()) {
                String tipsRaw = root.path("coachingTips").asText(null);
                if (tipsRaw != null && tipsRaw.trim().startsWith("{")) {
                    JsonNode tipsNode = safeRead(tipsRaw);
                    if (tipsNode.has("scoreDelta")) {
                        delta = tipsNode.path("scoreDelta");
                    }
                }
            }

            for (String k : KEYS) {
                int v = delta.path(k).asInt(0);
                sum.merge(k, v, Integer::sum);
                cnt.merge(k, 1, Integer::sum);
                totalSum += v;
                totalCnt++;
            }
        }

        Map<String, Integer> subscores = new LinkedHashMap<>();
        for (String k : KEYS) {
            int c = cnt.getOrDefault(k, 0);
            double avg = c == 0 ? 0.0 : (double) sum.getOrDefault(k, 0) / c; // 평균 delta
            // 스케일링: baseline 3.0 + (delta 평균 / 2.0), 1..5 clamp
            int scaled = (int) Math.round(clamp(3.0 + avg / 2.0, 1.0, 5.0));
            subscores.put(k, scaled);
        }

        double overall = totalCnt == 0 ? 3.0 : clamp(3.0 + ((double) totalSum / totalCnt) / 2.0, 1.0, 5.0);
        return new ScoresDto(overall, subscores);
    }

    private JsonNode safeRead(String json) {
        try { return om.readTree(json); } catch (Exception e) { return om.createObjectNode(); }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}