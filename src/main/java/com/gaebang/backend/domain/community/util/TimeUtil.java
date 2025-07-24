package com.gaebang.backend.domain.community.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

@Component
public class TimeUtil {

    private final DateTimeFormatter absoluteTimeFormatter;

    public TimeUtil() {
        this.absoluteTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    }

    /**
     * String 날짜를 받아서 바로 표시용 시간으로 변환
     * 프로젝션에서 String으로 받은 날짜를 처리할 때 사용
     */
    public String getDisplayTimeFromString(String createdAtString) {
        LocalDateTime createdAt = parseCreatedAt(createdAtString);
        return getDisplayTime(createdAt);
    }

    /**
     * LocalDateTime을 받아서 표시용 시간으로 변환
     */
    public String getDisplayTime(LocalDateTime createdAt) {
        String relativeTime = getRelativeTime(createdAt);
        return relativeTime != null ? relativeTime : formatAbsoluteTime(createdAt);
    }

    /**
     * 상대적 시간 계산 (1시간 미만만)
     */
    public String getRelativeTime(LocalDateTime pastTime) {
        LocalDateTime now = LocalDateTime.now();
        long minutes = ChronoUnit.MINUTES.between(pastTime, now);

        if (minutes < 1) {
            return "방금 전";
        } else if (minutes < 60) {
            return minutes + "분 전";
        } else {
            return null; // 1시간 이상은 절대 시간 사용
        }
    }

    /**
     * 절대 시간 포맷팅
     */
    public String formatAbsoluteTime(LocalDateTime dateTime) {
        return dateTime.format(absoluteTimeFormatter);
    }

    /**
     * 상대적 시간인지 절대적 시간인지 확인
     */
    public boolean isRelativeTime(LocalDateTime pastTime) {
        long minutes = ChronoUnit.MINUTES.between(pastTime, LocalDateTime.now());
        return minutes < 60;
    }

    /**
     * String 날짜가 상대적 시간인지 확인
     */
    public boolean isRelativeTimeFromString(String createdAtString) {
        LocalDateTime createdAt = parseCreatedAt(createdAtString);
        return isRelativeTime(createdAt);
    }

    /**
     * String을 LocalDateTime으로 변환
     * 다양한 날짜 형식을 지원
     */
    public LocalDateTime parseCreatedAt(String createdAtString) {
        if (createdAtString == null || createdAtString.trim().isEmpty()) {
            throw new IllegalArgumentException("날짜 문자열이 null이거나 비어있습니다.");
        }

        // 여러 가능한 패턴들
        DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),  // 마이크로초 포함
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),     // 밀리초 포함
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),         // 초까지
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),       // 공백으로 구분
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),           // 초까지 공백구분
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),              // 분까지
                DateTimeFormatter.ISO_LOCAL_DATE_TIME                         // 기본 ISO 형식
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(createdAtString.trim(), formatter);
            } catch (DateTimeParseException e) {
                // 다음 형식 시도
                continue;
            }
        }

        // 모든 시도가 실패한 경우
        throw new IllegalArgumentException("지원하지 않는 날짜 형식입니다: " + createdAtString);
    }
}
