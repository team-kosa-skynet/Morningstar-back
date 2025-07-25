package com.gaebang.backend.domain.attendance.dto.response;

import com.gaebang.backend.domain.attendance.entity.Attendance;
import com.gaebang.backend.domain.member.entity.Member;

import java.time.LocalDate;

public record AttendanceResponseDto (
        Long attendanceId,
        Member member,
        LocalDate attendanceDate
) {

    public static AttendanceResponseDto fromEntity(Attendance attendance) {
        return new AttendanceResponseDto(
                attendance.getAttendanceId(),
                attendance.getMember(),
                attendance.getAttendanceDate()
        );
    }

    public static AttendanceResponseDto fromEntity() {
        return new AttendanceResponseDto(
                0L,
                null,
                null
        );
    }

}

