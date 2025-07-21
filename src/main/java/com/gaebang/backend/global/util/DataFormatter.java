package com.gaebang.backend.global.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DataFormatter {
    public static String getFormattedCreatedAt(LocalDateTime createdAt) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        return createdAt.format(formatter);
    }

    public static String getFormattedCreatedAtWithTime(LocalDateTime createdAt) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
        return createdAt.format(formatter);
    }
}
