package com.gaebang.backend.global.infrastructure.redis.cache;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.stream.Collectors;

public final class Keys {
    private Keys() {}

    public static String normalizeCond(String cond) {
        return cond == null ? "" : cond.trim().toLowerCase(Locale.ROOT);
    }

    public static String condHash(String cond) {
        return normalizeCond(cond);
        // 해시 사용을 원하면 아래로 교체:
        // try {
        //     var md = java.security.MessageDigest.getInstance("SHA-256");
        //     byte[] d = md.digest(normalizeCond(cond).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        //     StringBuilder sb = new StringBuilder();
        //     for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        //     return sb.toString(); // 16 hex chars
        // } catch (Exception e) { return normalizeCond(cond); }
    }

    public static String canonicalSort(Sort sort) {
        if (sort == null || sort.isUnsorted()) return "unsorted";
        return sort.stream()
            .map(o -> o.getProperty() + "," + o.getDirection().name())
            .sorted()
            .collect(Collectors.joining("|"));
    }

    public static String boardsKey(String ver, String cond, Pageable p) {
        return "getBoards:v=" + ver
             + ":cond=" + condHash(cond)
             + ":p=" + p.getPageNumber()
             + ":s=" + p.getPageSize()
             + ":sort=" + canonicalSort(p.getSort());
    }
}