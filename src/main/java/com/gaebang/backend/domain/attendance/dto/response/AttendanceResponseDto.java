package com.gaebang.backend.domain.attendance.dto.response;

import com.gaebang.backend.domain.attendance.entity.Attendance;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;

public record AttendanceResponseDto (
        Long attendanceId,
        Long memberId,
        String attendanceDate,
        boolean isNewAttendance,    // 새 출석인지 여부
        Integer pointsEarned        // 획득한 포인트 (0이면 중복 출석)
) {

    public static AttendanceResponseDto fromEntity(Attendance attendance, boolean isNew) {
        return new AttendanceResponseDto(
                attendance.getAttendanceId(),
                attendance.getMember().getId(),
                attendance.getAttendanceDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                isNew,
                isNew ? 50 : 0
        );
    }
}

