package com.gaebang.backend.domain.newsData.util;

public class HtmlUtils {

    // HTML 태그 제거
    public static String removeHtmlTags(String text) {
        if (text == null) return null;
        return text.replaceAll("<[^>]*>", "");
    }

    // HTML 엔티티 디코딩
    public static String decodeHtmlEntities(String text) {
        if (text == null) return null;

        return text.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    // 통합 처리 메서드
    public static String cleanText(String text) {
        if (text == null) return null;

        // 1. HTML 태그 제거
        String cleaned = removeHtmlTags(text);

        // 2. HTML 엔티티 디코딩
        cleaned = decodeHtmlEntities(cleaned);

        // 3. 연속된 공백 정리
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned;
    }
}
