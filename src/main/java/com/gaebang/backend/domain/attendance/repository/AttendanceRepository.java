package com.gaebang.backend.domain.attendance.repository;

import com.gaebang.backend.domain.attendance.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import java.time.LocalDate;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    // 특정 회원의 특정 날짜 출석 여부 확인
    boolean existsByMemberIdAndAttendanceDate(Long memberId, LocalDate attendanceDate);

    // 특정 회원의 특정 날짜 출석 정보 조회                                                                                                                          │ │
    Optional<Attendance> findByMemberIdAndAttendanceDate(Long memberId, LocalDate attendanceDate);
}
