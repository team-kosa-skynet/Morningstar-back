package com.gaebang.backend.domain.attendance.service;

import com.gaebang.backend.domain.attendance.dto.response.AttendanceResponseDto;
import com.gaebang.backend.domain.attendance.entity.Attendance;
import com.gaebang.backend.domain.attendance.repository.AttendanceRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.entity.PointType;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final PointService pointService;

    // 해당 날짜에 첫 로그인시 출석 테이블에 로그인 로그 저장 및 포인트 지급
    @Transactional
    public AttendanceResponseDto createAttendance(PrincipalDetails principalDetails) {
        // 유저 아이디를 받는다.
        Member member = principalDetails.getMember();

        if (member == null) {
            throw new UserInvalidAccessException();
        }

        // 오늘날짜
        LocalDate today = LocalDate.now();

        boolean isExistedAttendance = attendanceRepository.existsByMemberIdAndAttendanceDate(member.getId(), today);

        if (!isExistedAttendance) {
            Attendance newAttendance = Attendance.builder()
                    .member(member)
                    .attendanceDate(today)
                    .build();

            // 출석 테이블에 저장.
            attendanceRepository.save(newAttendance);

            PointRequestDto pointRequestDto = PointRequestDto.builder()
                    .amount(50)
                    .type(PointType.ATTENDANCE)
                    .build();

            pointService.createPoint(pointRequestDto, principalDetails);

            return AttendanceResponseDto.fromEntity(newAttendance);
        }

        // 의미는 없지만 return은 해줘야 하니까...
        return AttendanceResponseDto.fromEntity();
    }

}
