package com.gaebang.backend.domain.interviewTurn.config;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QuestionCatalog {
    public List<Map<String,Object>> candidates(String role, List<String> skills, int limit) {
        List<Map<String,Object>> all = new ArrayList<>();

        if ("FRONTEND".equalsIgnoreCase(role)) {
            all.add(q(0,"BEHAVIORAL","최근 퍼포먼스 개선 경험을 STAR로 설명해 주세요."));
            all.add(q(1,"PERF","LCP/INP을 낮추기 위한 실전 튜닝 전략은?"));
            all.add(q(2,"SYSTEM_DESIGN","마이크로프론트엔드 도입 시 장단점과 합리적 트레이드오프는?"));
            all.add(q(3,"SECURITY_TEST","XSS/CSRF 대응을 프레임워크 수준에서 어떻게 설계했나요?"));
            all.add(q(4,"TROUBLESHOOT","빌드 체인(webpack/vite) 이슈 RCA 경험을 설명해 주세요."));
        }
        if ("BACKEND".equalsIgnoreCase(role)) {
            all.add(q(0,"SYSTEM_DESIGN","트래픽 10배 시 확장 전략과 병목 제거 순서를 설명해 주세요."));
            all.add(q(1,"DB_TX","격리수준별 부작용과 JPA 설정/패턴 매핑은?"));
            all.add(q(2,"PERF","N+1 탐지/해결 경험과 재발 방지 가드 설정은?"));
            all.add(q(3,"SECURITY_TEST","인증/인가 설계를 토큰/세션 기준으로 비교해 주세요."));
            all.add(q(4,"TROUBLESHOOT","최근 장애의 RCA와 재발 방지 액션 아이템은?"));
        }

        // ✅ 세션마다 순서 랜덤
        Collections.shuffle(all);

        // (선택) skills 가중치 주고 싶으면 여기서 재정렬 로직 추가
        // 예: skills에 "Spring" 있으면 DB_TX/PERF 우선 등

        return all.stream().limit(Math.max(1, Math.min(limit, all.size()))).toList();
    }

    private Map<String,Object> q(int idx, String type, String text) {
        return Map.of("idx", idx, "type", type, "text", text);
    }
}
