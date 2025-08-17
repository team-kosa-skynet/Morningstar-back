package com.gaebang.backend.domain.interview.service;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 매우 단순한 키워드 수집기(초기 버전). 필요시 Jackson으로 JSON 파싱/머지 추천 */
public final class SimpleProfileUpdater {

    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}+#\\.]+");

    private SimpleProfileUpdater() {}

    public static String updateJson(String existingJson, String transcript) {
        Set<String> keys = new HashSet<>();
        if (existingJson != null && existingJson.contains("\"keywords\"")) {
            // 초간단 파싱(실무는 꼭 Jackson 사용 권장)
            String[] parts = existingJson.split("\\[|\\]");
            if (parts.length >= 2) {
                String inner = parts[1];
                for (String s : inner.split(",")) {
                    String k = s.replace("\"", "").trim();
                    if (!k.isBlank()) keys.add(k);
                }
            }
        }
        if (transcript != null) {
            String lower = transcript.toLowerCase();
            if (lower.contains("java")) keys.add("Java");
            if (lower.contains("spring")) keys.add("Spring");
            if (lower.contains("redis")) keys.add("Redis");
            if (lower.contains("mysql")) keys.add("MySQL");
            if (lower.contains("kafka")) keys.add("Kafka");
            if (lower.contains("트래픽")) keys.add("트래픽");
            if (lower.contains("결제")) keys.add("결제");
            // 필요 키워드는 점진 추가
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"keywords\":[");
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(",");
            sb.append("\"").append(k).append("\"");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }
}
